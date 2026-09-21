package com.alibaba.cloud.ai.studio.core.conversation;

import com.alibaba.cloud.ai.studio.core.base.entity.ConversationMessageEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ConversationRecordEntity;
import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.base.mapper.ConversationMessageMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.ConversationRecordMapper;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
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

	private static final String TEXT = "text";

	private static final String MEMORY_SOURCE = "memory";

	private static final String CONVERSATION_CHAT_MEMORY_PREFIX = "conversation_chat:%s";

	private final ConversationRecordMapper conversationRecordMapper;

	private final ConversationMessageMapper conversationMessageMapper;

	private final RedisManager redisManager;

	/**
	 * Creates or touches a conversation when a request enters the workflow.
	 */
	@Transactional(rollbackFor = Exception.class)
	public Long prepareConversation(String appId, String conversationId, String invokeSource, String userId) {
		Long app = requireLong(appId, "app_id");
		Long conversation = requireLong(conversationId, "conversation_id");
		Long user = nullableLong(userId);

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

	/**
	 * Persists the current workflow user message before asynchronous node execution.
	 *
	 * This must be called after old history has been loaded. Otherwise a cold Redis cache
	 * may reload the current user message from DB and incorrectly treat it as history of
	 * the same request.
	 */
	@Transactional(rollbackFor = Exception.class)
	public void saveWorkflowUserMessage(WorkflowContext context) {
		if (context == null || StringUtils.isBlank(context.getAppId())
				|| StringUtils.isBlank(context.getConversationId())) {
			return;
		}

		Long appId = requireLong(context.getAppId(), "app_id");
		Long conversationId = requireLong(context.getConversationId(), "conversation_id");
		Long userId = nullableLong(context.getAccountId());
		String requestId = context.getRequestId();

		ConversationRecordEntity record =
				lockOrCreateConversation(appId, conversationId, context.getInvokeSource(), userId);

		if (messageExists(conversationId, requestId, "user")) {
			return;
		}

		int sequence = safeCount(record) + 1;
		String input = resolveWorkflowInput(context);

		ConversationMessageEntity userMessage = newMessage(
				appId,
				conversationId,
				sequence,
				"user",
				input,
				context.getTraceId(),
				requestId);
		conversationMessageMapper.insert(userMessage);

		fillConversationName(record, input);
		record.setMessageCount(sequence);
		record.setUpdatedAt(new Date());
		if (StringUtils.isNotBlank(context.getInvokeSource())) {
			record.setInvokeSource(context.getInvokeSource());
		}
		if (userId != null) {
			record.setUserId(userId);
		}
		conversationRecordMapper.updateById(record);
	}

	/**
	 * Persists only the assistant message after END succeeds.
	 *
	 * The matching user message has already been persisted before workflow execution.
	 * The request id makes both the normal END and auto END paths idempotent.
	 */
	@Transactional(rollbackFor = Exception.class)
	public void saveWorkflowAssistantMessage(WorkflowContext context) {
		if (context == null || StringUtils.isBlank(context.getAppId())
				|| StringUtils.isBlank(context.getConversationId())) {
			return;
		}

		Long appId = requireLong(context.getAppId(), "app_id");
		Long conversationId = requireLong(context.getConversationId(), "conversation_id");
		Long userId = nullableLong(context.getAccountId());
		String requestId = context.getRequestId();

		ConversationRecordEntity record =
				lockOrCreateConversation(appId, conversationId, context.getInvokeSource(), userId);

		if (messageExists(conversationId, requestId, "assistant")) {
			redisManager.delete(memoryRedisKey(memoryConversationId(appId, conversationId)));
			return;
		}

		ConversationMessageEntity userMessage = findMessage(conversationId, requestId, "user");
		int sequence = safeCount(record) + 1;
		String output = context.getTaskResult() == null ? "" : context.getTaskResult();

		ConversationMessageEntity assistantMessage = newMessage(
				appId,
				conversationId,
				sequence,
				"assistant",
				output,
				context.getTraceId(),
				requestId);
		if (userMessage != null) {
			assistantMessage.setParentMessageId(userMessage.getId());
		}
		conversationMessageMapper.insert(assistantMessage);

		record.setMessageCount(sequence);
		record.setUpdatedAt(new Date());
		if (StringUtils.isNotBlank(context.getInvokeSource())) {
			record.setInvokeSource(context.getInvokeSource());
		}
		if (userId != null) {
			record.setUserId(userId);
		}
		conversationRecordMapper.updateById(record);

		// END may already have appended the completed round to Redis. Remove the cache so
		// the next request reloads the authoritative DB history without duplicates.
		redisManager.delete(memoryRedisKey(memoryConversationId(appId, conversationId)));
	}

	/**
	 * Durable write used by generic Spring AI ChatMemory callers such as Agent.
	 *
	 * Workflow END calls are excluded by ConversationPersistenceScope and the workflow
	 * assistant row is persisted once by saveWorkflowAssistantMessage(), carrying
	 * trace/request metadata.
	 */
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

		ConversationRecordEntity record = lockOrCreateConversation(
				key.appId(), key.conversationId(), MEMORY_SOURCE, null);

		int sequence = safeCount(record) + 1;
		Long lastUserMessageId = null;
		int inserted = 0;

		for (Message message : messages) {
			if (message == null) {
				continue;
			}

			ConversationMessageEntity entity = newMessage(
					key.appId(),
					key.conversationId(),
					sequence++,
					role(message),
					message.getText(),
					null,
					null);

			if ("assistant".equals(entity.getRole())) {
				entity.setParentMessageId(lastUserMessageId);
			}

			conversationMessageMapper.insert(entity);
			if ("user".equals(entity.getRole())) {
				lastUserMessageId = entity.getId();
				fillConversationName(record, entity.getContent());
			}
			inserted++;
		}

		if (inserted > 0) {
			record.setMessageCount(safeCount(record) + inserted);
			record.setUpdatedAt(new Date());
			conversationRecordMapper.updateById(record);
		}
	}

	/**
	 * Loads recent messages for ChatMemory DB fallback.
	 */
	public List<ConversationMessageEntity> loadMemoryMessages(String memoryConversationId, int limit) {
		if (limit <= 0) {
			return List.of();
		}

		ConversationKey key = parseMemoryConversationId(memoryConversationId);
		if (key == null) {
			return List.of();
		}

		List<ConversationMessageEntity> rows = conversationMessageMapper.selectList(
				Wrappers.<ConversationMessageEntity>lambdaQuery()
						.eq(ConversationMessageEntity::getAppId, key.appId())
						.eq(ConversationMessageEntity::getConversationId, key.conversationId())
						.eq(ConversationMessageEntity::getStatus, SUCCESS)
						.orderByDesc(ConversationMessageEntity::getSequence)
						.orderByDesc(ConversationMessageEntity::getId)
						.last("limit " + limit));

		if (rows == null || rows.isEmpty()) {
			return List.of();
		}

		List<ConversationMessageEntity> result = new ArrayList<>(rows);
		Collections.reverse(result);
		return result;
	}

	/**
	 * Clears durable conversation messages and resets message_count.
	 */
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

	private boolean messageExists(Long conversationId, String requestId, String role) {
		if (StringUtils.isBlank(requestId)) {
			return false;
		}
		Long existing = conversationMessageMapper.selectCount(
				Wrappers.<ConversationMessageEntity>lambdaQuery()
						.eq(ConversationMessageEntity::getConversationId, conversationId)
						.eq(ConversationMessageEntity::getRequestId, requestId)
						.eq(ConversationMessageEntity::getRole, role));
		return existing != null && existing > 0;
	}

	private ConversationMessageEntity findMessage(Long conversationId, String requestId, String role) {
		if (StringUtils.isBlank(requestId)) {
			return null;
		}
		List<ConversationMessageEntity> rows = conversationMessageMapper.selectList(
				Wrappers.<ConversationMessageEntity>lambdaQuery()
						.eq(ConversationMessageEntity::getConversationId, conversationId)
						.eq(ConversationMessageEntity::getRequestId, requestId)
						.eq(ConversationMessageEntity::getRole, role)
						.orderByDesc(ConversationMessageEntity::getId)
						.last("limit 1"));
		return rows == null || rows.isEmpty() ? null : rows.get(0);
	}

	private ConversationRecordEntity lockOrCreateConversation(
			Long appId, Long conversationId, String invokeSource, Long userId) {

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

	private ConversationMessageEntity newMessage(
			Long appId, Long conversationId, int sequence, String role,
			String content, String traceId, String requestId) {

		Date now = new Date();
		ConversationMessageEntity entity = new ConversationMessageEntity();
		entity.setAppId(appId);
		entity.setConversationId(conversationId);
		entity.setSequence(sequence);
		entity.setRole(role);
		entity.setContent(content == null ? "" : content);
		entity.setContentType(TEXT);
		entity.setTraceId(traceId);
		entity.setRequestId(requestId);
		entity.setStatus(SUCCESS);
		entity.setCreatedAt(now);
		entity.setUpdatedAt(now);
		return entity;
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

	private Long nullableLong(String value) {
		if (StringUtils.isBlank(value)) {
			return null;
		}
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException e) {
			return null;
		}
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

	private String memoryRedisKey(String memoryConversationId) {
		return String.format(CONVERSATION_CHAT_MEMORY_PREFIX, memoryConversationId);
	}

	private record ConversationKey(Long appId, Long conversationId) {
	}

}
