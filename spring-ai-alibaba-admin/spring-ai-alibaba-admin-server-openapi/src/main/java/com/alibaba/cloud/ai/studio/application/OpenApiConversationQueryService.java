package com.alibaba.cloud.ai.studio.application;

import com.alibaba.cloud.ai.studio.core.base.entity.ConversationRecordEntity;
import com.alibaba.cloud.ai.studio.core.base.service.ApiKeyService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.domain.model.ConversationSearchCriteria;
import com.alibaba.cloud.ai.studio.domain.model.MessageSearchCriteria;
import com.alibaba.cloud.ai.studio.domain.service.ConversationQueryRules;
import com.alibaba.cloud.ai.studio.domain.service.ConversationScopePolicy;
import com.alibaba.cloud.ai.studio.infrastructure.persistence.ConversationReadRepository;
import com.alibaba.cloud.ai.studio.openapi.dto.conversation.*;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 应用服务：编排条件校验、授权、数据读取与返回组装，查询 SQL 由仓储完成。 */
@Service
@RequiredArgsConstructor
public class OpenApiConversationQueryService {
    private final ConversationQueryRules rules;
    private final ConversationScopePolicy scopePolicy;
    private final ConversationReadRepository repository;
    private final OpenApiConversationViewAssembler assembler;
    private final ApiKeyService apiKeyService;

    public PagingList<ConversationView> query(ConversationQuery filter) {
        if (filter == null) {
            throw rules.invalid("filter", "request body is required");
        }
        int pageNum = rules.page(filter.getPageNum());
        int pageSize = rules.size(filter.getPageSize());
        rules.checkCountRange(filter.getMinMessageCount(), filter.getMaxMessageCount());
        Date start = rules.parseTime(filter.getStartTime(), "startTime");
        Date end = rules.parseTime(filter.getEndTime(), "endTime");
        rules.checkTimeRange(start, end);

        RequestContext context = requireCaller();
        Set<Long> visible = visibleAppIds(context);
        Set<Long> requested = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(filter.getAppId())) {
            requested.add(rules.numericId(filter.getAppId(), "appId"));
        }
        if (filter.getAppIds() != null) {
            for (String id : filter.getAppIds()) {
                if (StringUtils.isBlank(id)) {
                    throw rules.invalid("appIds", "app ID must not be blank");
                }
                requested.add(rules.numericId(id, "appId"));
            }
        }
        Set<Long> scope = scopePolicy.restrict(visible, requested);
        if (scope.isEmpty()) {
            return new PagingList<>(pageNum, pageSize, 0L, List.of());
        }

        ConversationSearchCriteria criteria = new ConversationSearchCriteria(
                scope,
                StringUtils.isBlank(filter.getConversationId()) ? null
                        : rules.numericId(filter.getConversationId(), "conversationId"),
                StringUtils.trimToNull(filter.getUserId()),
                StringUtils.trimToNull(filter.getInvokeSource()),
                StringUtils.trimToNull(filter.getName()),
                StringUtils.trimToNull(filter.getStatus()),
                start, end, filter.getMinMessageCount(), filter.getMaxMessageCount());
        var page = repository.findConversations(criteria, pageNum, pageSize);
        return new PagingList<>(pageNum, pageSize, page.getTotal(),
                page.getRecords().stream().map(assembler::conversationView).toList());
    }

    public ConversationDetail messages(MessageQuery filter) {
        if (filter == null || StringUtils.isBlank(filter.getConversationId())) {
            throw new BizException(ErrorCode.MISSING_PARAMS.toError("conversationId"));
        }
        int pageNum = rules.page(filter.getPageNum());
        int pageSize = rules.size(filter.getPageSize());
        Date start = rules.parseTime(filter.getStartTime(), "startTime");
        Date end = rules.parseTime(filter.getEndTime(), "endTime");
        rules.checkTimeRange(start, end);

        RequestContext caller = requireCaller();
        Long conversationId = rules.numericId(filter.getConversationId(), "conversationId");
        ConversationRecordEntity conversation = repository.findById(conversationId);
        if (conversation == null) {
            throw rules.invalid("conversationId", "conversation not found");
        }
        if (!repository.appInWorkspace(caller.getWorkspaceId(), conversation.getAppId())) {
            throw new BizException(ErrorCode.PERMISSION_DENIED.toError());
        }
        apiKeyService.checkAppAccess(caller.getApiKeyId(), caller.getWorkspaceId(),
                conversation.getAppId().toString());

        // 每条消息是一轮问答，已不存在单消息角色 role。
        MessageSearchCriteria criteria = new MessageSearchCriteria(
                conversationId, conversation.getAppId(),
                StringUtils.trimToNull(filter.getStatus()), start, end);
        var page = repository.findMessages(criteria, pageNum, pageSize);
        ConversationDetail detail = new ConversationDetail();
        detail.setConversation(assembler.conversationView(conversation));
        detail.setMessages(new PagingList<>(pageNum, pageSize, page.getTotal(),
                page.getRecords().stream().map(assembler::messageView).toList()));
        return detail;
    }

    private Set<Long> visibleAppIds(RequestContext context) {
        Set<String> workspaceAppIds = repository.workspaceAppIds(context.getWorkspaceId());
        Set<String> scope = apiKeyService.getAccessibleAppIds(context.getApiKeyId(), context.getWorkspaceId());
        Set<Long> result = new LinkedHashSet<>();
        for (String id : workspaceAppIds) {
            if (scope == null || scope.contains(id)) {
                try {
                    result.add(Long.valueOf(id));
                }
                catch (NumberFormatException ignored) {
                    // 会话 app_id 为 BIGINT；不可转数字的应用没有匹配的会话记录。
                }
            }
        }
        return result;
    }

    private RequestContext requireCaller() {
        RequestContext context = RequestContextHolder.getRequestContext();
        if (context == null || context.getApiKeyId() == null || StringUtils.isBlank(context.getWorkspaceId())) {
            throw new BizException(ErrorCode.INVALID_API_KEY.toError());
        }
        return context;
    }
}
