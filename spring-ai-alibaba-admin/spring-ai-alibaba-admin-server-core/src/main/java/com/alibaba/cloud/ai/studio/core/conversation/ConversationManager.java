package com.alibaba.cloud.ai.studio.core.conversation;

import com.alibaba.cloud.ai.studio.core.base.entity.ConversationMessageEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ConversationRecordEntity;
import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.base.mapper.ConversationMessageMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.ConversationRecordMapper;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.utils.common.IdGenerator;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants.APPCODE_CONVERSATION_ID_TEMPLATE;
import static com.alibaba.cloud.ai.studio.core.workflow.constants.WorkflowConstants.SYS_QUERY_KEY;

/**
 * Unified conversation/message persistence service.
 *
 * Workflow/Agent/ChatMemory should use this service instead of directly operating
 * conversation tables.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationManager {

	private static final String NORMAL = "normal";
	private static final String SUCCESS = "success";
	private static final String MEMORY_SOURCE = "memory";
	private static final String CONVERSATION_CHAT_MEMORY_PREFIX = "conversation_chat:%s";

	private final ConversationRecordMapper conversationRecordMapper;
	private final ConversationMessageMapper conversationMessageMapper;
	private final RedisManager redisManager;

	@Transactional(rollbackFor = Exception.class)
	public Long prepareConversation(String appId, String conversationId, String invokeSource, String userId) {
		Long app = requireLong(appId, "app_id");
		Long conversation = requireLong(conversationId, "conversation_id");
		String user = normalizeUserId(userId);

		ConversationRecordEntity entity = conversationRecordMapper.selectById(conversation);
		Date now = new Date();

		if (entity == null) {
			entity = new ConversationRecordEntity();
			entity.setId(conversation);
			entity.setAppId(app);
			entity.setStatus(NORMAL);
			entity.setInvokeSource(invokeSource);
			entity.setMessageCount(0);
			entity.setUserId(user);
			entity.setCreatedAt(now);
			entity.setUpdatedAt(now);
			conversationRecordMapper.insert(entity);
			return conversation;
		}

		checkApp(entity, app);
		if (StringUtils.isNotBlank(invokeSource)) {
			entity.setInvokeSource(invokeSource);
		}
		if (user != null) {
			entity.setUserId(user);
		}
		entity.setUpdatedAt(now);
		conversationRecordMapper.updateById(entity);
		return conversation;
	}

	@Transactional(rollbackFor = Exception.class)
	public void saveWorkflowUserMessage(WorkflowContext context) {
		if (context == null || StringUtils.isBlank(context.getAppId())
				|| StringUtils.isBlank(context.getConversationId())) {
			return;
		}

		Long appId = requireLong(context.getAppId(), "app_id");
		Long conversationId = requireLong(context.getConversationId(), "conversation_id");
		String requestId = context.getRequestId();
		String userId = normalizeUserId(context.getUserId());
		String invokeSource = resolveWorkflowInvokeSource(context);

		// 先锁定会话，避免同一轮请求重试造成重复的问答记录和轮次计数。
		ConversationRecordEntity record = lockOrCreateConversation(appId, conversationId, invokeSource, userId);
		if (findTurnByRequest(appId, conversationId, requestId) != null) {
			return;
		}

		String question = resolveWorkflowInput(context);
		ConversationMessageEntity turn = newTurn(appId, conversationId, question,
				context.getTraceId(), requestId, userId);
		conversationMessageMapper.insert(turn);
		fillConversationName(record, question);
		// message_count 现在是问答轮数，写入提问时 +1，补写回答不再 +1。
		updateConversation(record, invokeSource, userId, 1);
	}

	@Transactional(rollbackFor = Exception.class)
	public void saveWorkflowAssistantMessage(WorkflowContext context) {
		if (context == null || StringUtils.isBlank(context.getAppId())
				|| StringUtils.isBlank(context.getConversationId())) {
			return;
		}

		Long appId = requireLong(context.getAppId(), "app_id");
		Long conversationId = requireLong(context.getConversationId(), "conversation_id");
		String userId = normalizeUserId(context.getUserId());
		String requestId = context.getRequestId();
		String invokeSource = resolveWorkflowInvokeSource(context);
		ConversationRecordEntity record = lockOrCreateConversation(appId, conversationId, invokeSource, userId);

		ConversationMessageEntity turn = findTurnByRequest(appId, conversationId, requestId);
		String answer = context.getTaskResult() == null ? "" : context.getTaskResult();
		int newTurns = 0;
		if (turn == null) {
			// 容错：个别执行入口没有提前写入问题，也只会生成一条完整问答记录。
			turn = newTurn(appId, conversationId, resolveWorkflowInput(context),
					context.getTraceId(), requestId, userId);
			turn.setAnswer(answer);
			turn.setStatus(SUCCESS);
			conversationMessageMapper.insert(turn);
			fillConversationName(record, turn.getQuestion());
			newTurns = 1;
		}
		else if (!SUCCESS.equals(turn.getStatus())) {
			turn.setAnswer(answer);
			turn.setStatus(SUCCESS);
			if (StringUtils.isNotBlank(context.getTraceId())) {
				turn.setTraceId(context.getTraceId());
			}
			turn.setUpdatedAt(new Date());
			conversationMessageMapper.updateById(turn);
		}
		else {
			// END 的回调或重试可能多次触发，已完成轮次保持幂等。
			invalidateMemoryCache(appId, conversationId);
			return;
		}
		updateConversation(record, invokeSource, userId, newTurns);
		// 下次 ChatMemory.get 从问答表还原 user/assistant 消息，避免旧缓存重复。
		invalidateMemoryCache(appId, conversationId);
	}

	/** Finalizes a pending question if execution failed or was stopped before reaching END. */
	@Transactional(rollbackFor = Exception.class)
	public void finishWorkflowFailedMessage(WorkflowContext context) {
		if (context == null || StringUtils.isAnyBlank(
				context.getAppId(), context.getConversationId(), context.getRequestId())) {
			return;
		}
		Long appId = requireLong(context.getAppId(), "app_id");
		Long conversationId = requireLong(context.getConversationId(), "conversation_id");
		ConversationRecordEntity record = conversationRecordMapper.selectByIdForUpdate(conversationId);
		if (record == null) {
			return;
		}
		checkApp(record, appId);
		ConversationMessageEntity turn = findTurnByRequest(appId, conversationId, context.getRequestId());
		if (turn == null || SUCCESS.equals(turn.getStatus())) {
			return;
		}
		turn.setStatus("fail");
		turn.setUpdatedAt(new Date());
		conversationMessageMapper.updateById(turn);
		invalidateMemoryCache(appId, conversationId);
	}

	@Transactional(rollbackFor = Exception.class)
	public void appendMemoryMessages(String memoryConversationId, List<Message> messages) {
		if (messages == null || messages.isEmpty()) {
			return;
		}
		ConversationKey key = parseMemoryConversationId(memoryConversationId);
		if (key == null) {
			log.warn("Skip DB conversation persistence: unsupported memory conversation id={}", memoryConversationId);
			return;
		}

		RequestContext requestContext = RequestContextHolder.getRequestContext();
		String userId = resolvePersistentUserId(requestContext);
		String invokeSource = resolveInvokeSource(requestContext);
		ConversationRecordEntity record = lockOrCreateConversation(
				key.appId(), key.conversationId(), invokeSource, userId);
		ConversationMessageEntity pending = null;
		int newTurns = 0;

		for (Message message : messages) {
			if (message == null) {
				continue;
			}
			String role = role(message);
			String content = message.getText() == null ? "" : message.getText();
			if ("user".equals(role)) {
				// 新问题开启一个问答轮次；允许回答暂时为空。
				pending = newTurn(key.appId(), key.conversationId(), content, null, null, userId);
				conversationMessageMapper.insert(pending);
				fillConversationName(record, content);
				newTurns++;
			}
			else if ("assistant".equals(role)) {
				if (pending == null) {
					// Agent 可能分两次 ChatMemory.add(user)、add(assistant)。
					pending = findPendingTurn(key.appId(), key.conversationId());
				}
				if (pending == null) {
					// 不丢失单独传入的 AI 消息；问题缺失时以空文本占位。
					pending = newTurn(key.appId(), key.conversationId(), "", null, null, userId);
					conversationMessageMapper.insert(pending);
					newTurns++;
				}
				pending.setAnswer(content);
				pending.setStatus(SUCCESS);
				pending.setUpdatedAt(new Date());
				conversationMessageMapper.updateById(pending);
				pending = null;
			}
			// system/tool 没有 question/answer 的对应字段；保持在 ChatMemory 缓存中，不伪造问答行。
		}
		if (newTurns > 0) {
			updateConversation(record, invokeSource, userId, newTurns);
		}
	}

	public List<ConversationMessageEntity> loadMemoryMessages(String memoryConversationId, int limit) {
		if (limit <= 0) {
			return List.of();
		}
		ConversationKey key = parseMemoryConversationId(memoryConversationId);
		if (key == null) {
			return List.of();
		}
		// 数据库一行是一个问答轮次，ChatMemory 中仍按两条 Spring AI 消息计数。
		int roundLimit = Math.max(1, (limit + 1) / 2);
		List<ConversationMessageEntity> rows = conversationMessageMapper.selectList(
				Wrappers.<ConversationMessageEntity>lambdaQuery()
						.eq(ConversationMessageEntity::getAppId, key.appId())
						.eq(ConversationMessageEntity::getConversationId, key.conversationId())
						.eq(ConversationMessageEntity::getStatus, SUCCESS)
						.orderByDesc(ConversationMessageEntity::getCreatedAt)
						.orderByDesc(ConversationMessageEntity::getId)
						.last("limit " + roundLimit));
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		List<ConversationMessageEntity> result = new ArrayList<>(rows);
		Collections.reverse(result);
		return result;
	}

	@Transactional(rollbackFor = Exception.class)
	public void clearMemory(String memoryConversationId) {
		ConversationKey key = parseMemoryConversationId(memoryConversationId);
		if (key != null) {
			conversationMessageMapper.delete(
					Wrappers.<ConversationMessageEntity>lambdaQuery()
							.eq(ConversationMessageEntity::getAppId, key.appId())
							.eq(ConversationMessageEntity::getConversationId, key.conversationId()));

			ConversationRecordEntity record = conversationRecordMapper.selectById(key.conversationId());
			if (record != null && Objects.equals(record.getAppId(), key.appId())) {
				record.setMessageCount(0);
				record.setUpdatedAt(new Date());
				conversationRecordMapper.updateById(record);
			}
		}

		redisManager.delete(memoryRedisKey(memoryConversationId));
	}

	private ConversationMessageEntity findTurnByRequest(Long appId, Long conversationId, String requestId) {
		if (StringUtils.isBlank(requestId)) {
			return null;
		}
		List<ConversationMessageEntity> rows = conversationMessageMapper.selectList(
				Wrappers.<ConversationMessageEntity>lambdaQuery()
						.eq(ConversationMessageEntity::getAppId, appId)
						.eq(ConversationMessageEntity::getConversationId, conversationId)
						.eq(ConversationMessageEntity::getRequestId, requestId)
						.orderByDesc(ConversationMessageEntity::getCreatedAt)
						.orderByDesc(ConversationMessageEntity::getId)
						.last("limit 1"));
		return rows == null || rows.isEmpty() ? null : rows.get(0);
	}

	private ConversationMessageEntity findPendingTurn(Long appId, Long conversationId) {
		List<ConversationMessageEntity> rows = conversationMessageMapper.selectList(
				Wrappers.<ConversationMessageEntity>lambdaQuery()
						.eq(ConversationMessageEntity::getAppId, appId)
						.eq(ConversationMessageEntity::getConversationId, conversationId)
						.eq(ConversationMessageEntity::getStatus, "processing")
						.isNull(ConversationMessageEntity::getAnswer)
						.isNull(ConversationMessageEntity::getRequestId)
						.orderByDesc(ConversationMessageEntity::getCreatedAt)
						.orderByDesc(ConversationMessageEntity::getId)
						.last("limit 1"));
		return rows == null || rows.isEmpty() ? null : rows.get(0);
	}

		private ConversationRecordEntity lockOrCreateConversation(
			Long appId, Long conversationId, String invokeSource, String userId) {

		ConversationRecordEntity record = conversationRecordMapper.selectByIdForUpdate(conversationId);
		if (record != null) {
			checkApp(record, appId);
			return record;
		}

		Date now = new Date();
		record = new ConversationRecordEntity();
		record.setId(conversationId);
		record.setAppId(appId);
		record.setStatus(NORMAL);
		record.setInvokeSource(invokeSource);
		record.setMessageCount(0);
		record.setUserId(userId);
		record.setCreatedAt(now);
		record.setUpdatedAt(now);
		conversationRecordMapper.insert(record);
		return record;
	}

	private ConversationMessageEntity newTurn(Long appId, Long conversationId, String question,
			String traceId, String requestId, String userId) {
		Date now = new Date();
		ConversationMessageEntity entity = new ConversationMessageEntity();
		entity.setMessageId(IdGenerator.id());
		entity.setAppId(appId);
		entity.setConversationId(conversationId);
		entity.setQuestion(question == null ? "" : question);
		entity.setAnswer(null);
		entity.setTraceId(traceId);
		entity.setRequestId(requestId);
		entity.setUserId(userId);
		entity.setStatus("processing");
		entity.setCreatedAt(now);
		entity.setUpdatedAt(now);
		return entity;
	}

	private void updateConversation(ConversationRecordEntity record, String invokeSource,
			String userId, int newTurns) {
		if (newTurns > 0) {
			record.setMessageCount(safeCount(record) + newTurns);
		}
		record.setUpdatedAt(new Date());
		if (StringUtils.isNotBlank(invokeSource)) {
			record.setInvokeSource(invokeSource);
		}
		if (userId != null) {
			record.setUserId(userId);
		}
		conversationRecordMapper.updateById(record);
	}

	private String resolveWorkflowInput(WorkflowContext context) {
		Object input = null;

		if (context.getSysMap() != null) {
			input = context.getSysMap().get(SYS_QUERY_KEY);
		}
		if (isBlank(input) && context.getUserMap() != null) {
			input = context.getUserMap().get("user_text");
		}
		if (isBlank(input) && context.getUserMap() != null) {
			input = context.getUserMap().get("userText");
		}
		if (isBlank(input) && context.getUserMap() != null) {
			input = context.getUserMap().get("query");
		}

		return input == null ? "" : String.valueOf(input);
	}

	private boolean isBlank(Object value) {
		return value == null || StringUtils.isBlank(String.valueOf(value));
	}

	private void fillConversationName(ConversationRecordEntity record, String content) {
		if (record == null || StringUtils.isNotBlank(record.getName()) || StringUtils.isBlank(content)) {
			return;
		}
		String name = content.trim().replaceAll("\\s+", " ");
		record.setName(name.length() <= 255 ? name : name.substring(0, 255));
	}

	private String role(Message message) {
		if (message.getMessageType() == null) {
			return "user";
		}
		return message.getMessageType().name().toLowerCase(Locale.ROOT);
	}

	private int safeCount(ConversationRecordEntity record) {
		return record.getMessageCount() == null ? 0 : record.getMessageCount();
	}

	private void checkApp(ConversationRecordEntity record, Long appId) {
		if (!Objects.equals(record.getAppId(), appId)) {
			throw new BizException(ErrorCode.INVALID_PARAMS.toError(
					"conversation_id", "conversation does not belong to current app"));
		}
	}

	private Long requireLong(String value, String field) {
		if (StringUtils.isBlank(value)) {
			throw new BizException(ErrorCode.INVALID_PARAMS.toError(field, "must not be blank"));
		}
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException e) {
			throw new BizException(ErrorCode.INVALID_PARAMS.toError(
					field, "must be a numeric bigint id"));
		}
	}

	private String normalizeUserId(String value) {
		if (StringUtils.isBlank(value)) {
			return null;
		}
		String normalized = value.trim();
		if (normalized.length() > 32) {
			throw new BizException(ErrorCode.INVALID_PARAMS.toError("userId", "must be 32 characters or fewer"));
		}
		return normalized;
	}

	private String resolveWorkflowInvokeSource(WorkflowContext context) {
		if (context != null && context.getApiKeyId() != null
				&& StringUtils.isNotBlank(context.getApiKeyCompanyName())) {
			return context.getApiKeyCompanyName().trim();
		}
		return context == null ? null : context.getInvokeSource();
	}

	private String resolvePersistentUserId(RequestContext context) {
		if (context == null) {
			return null;
		}
		if (context.getApiKeyId() != null) {
			return normalizeUserId(context.getUserId());
		}
		return normalizeUserId(context.getAccountId());
	}

	private String resolveInvokeSource(RequestContext context) {
		if (context != null && context.getApiKeyId() != null
				&& StringUtils.isNotBlank(context.getApiKeyCompanyName())) {
			return context.getApiKeyCompanyName().trim();
		}
		return MEMORY_SOURCE;
	}

	private ConversationKey parseMemoryConversationId(String memoryConversationId) {
		if (StringUtils.isBlank(memoryConversationId)) {
			return null;
		}

		int index = memoryConversationId.indexOf('_');
		if (index <= 0 || index >= memoryConversationId.length() - 1) {
			return null;
		}

		try {
			Long appId = Long.parseLong(memoryConversationId.substring(0, index));
			Long conversationId = Long.parseLong(memoryConversationId.substring(index + 1));
			return new ConversationKey(appId, conversationId);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private String memoryConversationId(Long appId, Long conversationId) {
		return String.format(APPCODE_CONVERSATION_ID_TEMPLATE, appId, conversationId);
	}

	private void invalidateMemoryCache(Long appId, Long conversationId) {
		try {
			redisManager.delete(memoryRedisKey(memoryConversationId(appId, conversationId)));
		}
		catch (RuntimeException e) {
			// PostgreSQL is authoritative; Redis failure must not roll back persisted question/answer.
			log.warn("Conversation memory cache invalidation failed, conversationId={}", conversationId, e);
		}
	}

	private String memoryRedisKey(String memoryConversationId) {
		return String.format(CONVERSATION_CHAT_MEMORY_PREFIX, memoryConversationId);
	}

	private record ConversationKey(Long appId, Long conversationId) {
	}
}
