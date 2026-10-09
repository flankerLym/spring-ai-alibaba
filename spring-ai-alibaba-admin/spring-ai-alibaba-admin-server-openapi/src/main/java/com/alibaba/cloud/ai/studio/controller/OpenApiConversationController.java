package com.alibaba.cloud.ai.studio.controller;

import com.alibaba.cloud.ai.studio.core.base.entity.AppEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ConversationMessageEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ConversationRecordEntity;
import com.alibaba.cloud.ai.studio.core.base.mapper.AppMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.ConversationMessageMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.ConversationRecordMapper;
import com.alibaba.cloud.ai.studio.core.base.service.ApiKeyService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.openapi.OpenApiResult;
import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.enums.AppStatus;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read-only OpenAPI endpoints for conversations and their ordered history. */
@RestController
@RequiredArgsConstructor
@Tag(name = "openapi-conversations")
@RequestMapping("/api/v1/conversations")
public class OpenApiConversationController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ConversationRecordMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final AppMapper appMapper;
    private final ApiKeyService apiKeyService;

    /** Query conversations with arbitrary combinations of filters (within authorized apps). */
    @PostMapping("/query")
    @Operation(summary = "Query conversations with pagination and filters")
    public OpenApiResult<PagingList<ConversationView>> query(@RequestBody ConversationQuery filter) {
        if (filter == null) {
            throw invalid("filter", "request body is required");
        }
        int pageNum = page(filter.getPageNum());
        int pageSize = size(filter.getPageSize());
        checkCountRange(filter.getMinMessageCount(), filter.getMaxMessageCount());
        Date start = parseTime(filter.getStartTime(), "startTime");
        Date end = parseTime(filter.getEndTime(), "endTime");
        checkTimeRange(start, end);

        RequestContext context = requireCaller();
        Set<Long> visibleApps = visibleAppIds(context);
        Set<String> requestedApps = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(filter.getAppId())) {
            requestedApps.add(filter.getAppId().trim());
        }
        if (filter.getAppIds() != null) {
            for (String id : filter.getAppIds()) {
                if (StringUtils.isBlank(id)) {
                    throw invalid("appIds", "app ID must not be blank");
                }
                requestedApps.add(id.trim());
            }
        }
        if (!requestedApps.isEmpty()) {
            Set<Long> wanted = new LinkedHashSet<>();
            for (String id : requestedApps) {
                // Never accept an application outside this API key's workspace/scope.
                Long numericId = numericId(id, "appId");
                if (!visibleApps.contains(numericId)) {
                    throw new BizException(ErrorCode.PERMISSION_DENIED.toError());
                }
                wanted.add(numericId);
            }
            visibleApps.retainAll(wanted);
        }
        if (visibleApps.isEmpty()) {
            return OpenApiResult.success(context.getRequestId(),
                    new PagingList<>(pageNum, pageSize, 0L, List.of()));
        }

        LambdaQueryWrapper<ConversationRecordEntity> query = new LambdaQueryWrapper<>();
        query.in(ConversationRecordEntity::getAppId, visibleApps);
        if (StringUtils.isNotBlank(filter.getConversationId())) {
            query.eq(ConversationRecordEntity::getId, numericId(filter.getConversationId(), "conversationId"));
        }
        query.eq(StringUtils.isNotBlank(filter.getUserId()), ConversationRecordEntity::getUserId,
                StringUtils.trimToNull(filter.getUserId()));
        query.eq(StringUtils.isNotBlank(filter.getInvokeSource()), ConversationRecordEntity::getInvokeSource,
                StringUtils.trimToNull(filter.getInvokeSource()));
        query.eq(StringUtils.isNotBlank(filter.getStatus()), ConversationRecordEntity::getStatus,
                StringUtils.trimToNull(filter.getStatus()));
        query.like(StringUtils.isNotBlank(filter.getName()), ConversationRecordEntity::getName,
                StringUtils.trimToNull(filter.getName()));
        query.ge(start != null, ConversationRecordEntity::getCreatedAt, start);
        query.le(end != null, ConversationRecordEntity::getCreatedAt, end);
        query.ge(filter.getMinMessageCount() != null, ConversationRecordEntity::getMessageCount,
                filter.getMinMessageCount());
        query.le(filter.getMaxMessageCount() != null, ConversationRecordEntity::getMessageCount,
                filter.getMaxMessageCount());

        boolean asc = "asc".equalsIgnoreCase(StringUtils.defaultIfBlank(filter.getSortOrder(), "desc"));
        if (!asc && !"desc".equalsIgnoreCase(StringUtils.defaultIfBlank(filter.getSortOrder(), "desc"))) {
            throw invalid("sortOrder", "supported values: asc, desc");
        }
        String sortBy = StringUtils.defaultIfBlank(filter.getSortBy(), "updatedAt");
        switch (sortBy) {
            case "createdAt" -> query.orderBy(true, asc, ConversationRecordEntity::getCreatedAt);
            case "updatedAt" -> query.orderBy(true, asc, ConversationRecordEntity::getUpdatedAt);
            case "messageCount" -> query.orderBy(true, asc, ConversationRecordEntity::getMessageCount);
            default -> throw invalid("sortBy", "supported values: createdAt, updatedAt, messageCount");
        }
        query.orderBy(true, asc, ConversationRecordEntity::getId);

        Page<ConversationRecordEntity> result = conversationMapper.selectPage(new Page<>(pageNum, pageSize), query);
        List<ConversationView> records = result.getRecords().stream().map(this::conversationView).toList();
        return OpenApiResult.success(context.getRequestId(),
                new PagingList<>(pageNum, pageSize, result.getTotal(), records));
    }

    /** Retrieve an authorized conversation and a page of its messages. */
    @PostMapping("/messages/query")
    @Operation(summary = "Retrieve conversation details and paginated message history")
    public OpenApiResult<ConversationDetail> messages(@RequestBody MessageQuery filter) {
        if (filter == null || StringUtils.isBlank(filter.getConversationId())) {
            throw new BizException(ErrorCode.MISSING_PARAMS.toError("conversationId"));
        }
        int pageNum = page(filter.getPageNum());
        int pageSize = size(filter.getPageSize());
        Date start = parseTime(filter.getStartTime(), "startTime");
        Date end = parseTime(filter.getEndTime(), "endTime");
        checkTimeRange(start, end);
        String order = StringUtils.defaultIfBlank(filter.getSortOrder(), "asc");
        if (!"asc".equalsIgnoreCase(order) && !"desc".equalsIgnoreCase(order)) {
            throw invalid("sortOrder", "supported values: asc, desc");
        }

        RequestContext caller = requireCaller();
        Long conversationId = numericId(filter.getConversationId(), "conversationId");
        ConversationRecordEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw invalid("conversationId", "conversation not found");
        }
        // Both workspace ownership and the API key's current app access are required.
        if (!appInWorkspace(caller.getWorkspaceId(), conversation.getAppId())) {
            throw new BizException(ErrorCode.PERMISSION_DENIED.toError());
        }
        apiKeyService.checkAppAccess(caller.getApiKeyId(), caller.getWorkspaceId(),
                conversation.getAppId().toString());

        LambdaQueryWrapper<ConversationMessageEntity> query = new LambdaQueryWrapper<>();
        query.eq(ConversationMessageEntity::getConversationId, conversationId)
                .eq(ConversationMessageEntity::getAppId, conversation.getAppId());
        query.eq(StringUtils.isNotBlank(filter.getRole()), ConversationMessageEntity::getRole,
                StringUtils.trimToNull(filter.getRole()));
        query.eq(StringUtils.isNotBlank(filter.getStatus()), ConversationMessageEntity::getStatus,
                StringUtils.trimToNull(filter.getStatus()));
        query.ge(start != null, ConversationMessageEntity::getCreatedAt, start);
        query.le(end != null, ConversationMessageEntity::getCreatedAt, end);
        boolean asc = "asc".equalsIgnoreCase(order);
        query.orderBy(true, asc, ConversationMessageEntity::getSequence)
                .orderBy(true, asc, ConversationMessageEntity::getMessageId);

        Page<ConversationMessageEntity> result = messageMapper.selectPage(new Page<>(pageNum, pageSize), query);
        ConversationDetail detail = new ConversationDetail();
        detail.setConversation(conversationView(conversation));
        detail.setMessages(new PagingList<>(pageNum, pageSize, result.getTotal(),
                result.getRecords().stream().map(this::messageView).toList()));
        return OpenApiResult.success(caller.getRequestId(), detail);
    }

    private Set<Long> visibleAppIds(RequestContext context) {
        // Resolve actual workspace membership, even when the API key has ALL scope.
        List<AppEntity> apps = appMapper.selectList(new LambdaQueryWrapper<AppEntity>()
                .select(AppEntity::getAppId)
                .eq(AppEntity::getWorkspaceId, context.getWorkspaceId())
                .ne(AppEntity::getStatus, AppStatus.DELETED));
        Set<String> scope = apiKeyService.getAccessibleAppIds(context.getApiKeyId(), context.getWorkspaceId());
        Set<Long> result = new LinkedHashSet<>();
        for (AppEntity app : apps) {
            if (scope == null || scope.contains(app.getAppId())) {
                try {
                    result.add(Long.valueOf(app.getAppId()));
                } catch (NumberFormatException ignored) {
                    // Conversation app_id is BIGINT; a non-numeric app ID has no matching rows.
                }
            }
        }
        return result;
    }

    private boolean appInWorkspace(String workspaceId, Long appId) {
        return appMapper.selectCount(new LambdaQueryWrapper<AppEntity>()
                .eq(AppEntity::getWorkspaceId, workspaceId)
                .eq(AppEntity::getAppId, appId.toString())
                .ne(AppEntity::getStatus, AppStatus.DELETED)) > 0;
    }

    private RequestContext requireCaller() {
        RequestContext context = RequestContextHolder.getRequestContext();
        if (context == null || context.getApiKeyId() == null || StringUtils.isBlank(context.getWorkspaceId())) {
            throw new BizException(ErrorCode.INVALID_API_KEY.toError());
        }
        return context;
    }

    private Long numericId(String value, String field) {
        if (StringUtils.isBlank(value)) {
            throw invalid(field, "must be a numeric bigint ID");
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalid(field, "must be a positive numeric bigint ID");
        }
    }

    private int page(Integer num) {
        int page = num == null ? 1 : num;
        if (page < 1) {
            throw invalid("pageNum", "must be >= 1");
        }
        return page;
    }

    private int size(Integer num) {
        int size = num == null ? DEFAULT_PAGE_SIZE : num;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("pageSize", "must be between 1 and " + MAX_PAGE_SIZE);
        }
        return size;
    }

    private void checkCountRange(Integer min, Integer max) {
        if ((min != null && min < 0) || (max != null && max < 0) ||
                (min != null && max != null && min > max)) {
            throw invalid("messageCount", "invalid message count range");
        }
    }

    private void checkTimeRange(Date start, Date end) {
        if (start != null && end != null && start.after(end)) {
            throw invalid("startTime", "startTime must not be after endTime");
        }
    }

    private Date parseTime(String text, String field) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String value = text.trim();
        try {
            return Date.from(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
            // Supports timezone offset such as 2026-10-09T10:00:00+08:00.
        }
        try {
            return Date.from(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException ignored) {
            // Supports local timestamps in the server's configured timezone.
        }
        try {
            LocalDateTime time = value.contains("T")
                    ? LocalDateTime.parse(value)
                    : LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return Date.from(time.atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException ex) {
            throw invalid(field, "use ISO-8601 or yyyy-MM-dd HH:mm:ss");
        }
    }

    private BizException invalid(String field, String reason) {
        return new BizException(ErrorCode.INVALID_PARAMS.toError(field, reason));
    }

    private ConversationView conversationView(ConversationRecordEntity entity) {
        ConversationView view = new ConversationView();
        view.setConversationId(entity.getId().toString());
        view.setAppId(entity.getAppId().toString());
        view.setUserId(entity.getUserId());
        view.setInvokeSource(entity.getInvokeSource());
        view.setName(entity.getName());
        view.setStatus(entity.getStatus());
        view.setMessageCount(entity.getMessageCount());
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        return view;
    }

    private MessageView messageView(ConversationMessageEntity entity) {
        MessageView view = new MessageView();
        view.setMessageId(entity.getMessageId() == null ? null : entity.getMessageId().toString());
        view.setParentMessageId(entity.getParentMessageId() == null ? null : entity.getParentMessageId().toString());
        view.setSequence(entity.getSequence());
        view.setRole(entity.getRole());
        view.setContent(entity.getContent());
        view.setContentType(entity.getContentType());
        view.setTraceId(entity.getTraceId());
        view.setRequestId(entity.getRequestId());
        view.setUserId(entity.getUserId());
        view.setStatus(entity.getStatus());
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        return view;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenApiResult<Void>> handleException(Exception ex) {
        RequestContext context = RequestContextHolder.getRequestContext();
        Error error = ex instanceof BizException biz ? biz.getError()
                : ex instanceof HttpMessageNotReadableException ? ErrorCode.INVALID_JSON.toError()
                : ErrorCode.SYSTEM_ERROR.toError();
        return ResponseEntity.status(error.getStatusCode())
                .body(OpenApiResult.error(context == null ? "" : context.getRequestId(), error));
    }

    @Data
    public static class ConversationQuery {
        private String appId;
        private List<String> appIds;
        private String conversationId;
        private String userId;
        private String invokeSource;
        private String name;
        private String status;
        private String startTime;
        private String endTime;
        private Integer minMessageCount;
        private Integer maxMessageCount;
        private String sortBy = "updatedAt";
        private String sortOrder = "desc";
        @JsonAlias({"current", "page"})
        private Integer pageNum = 1;
        @JsonAlias("size")
        private Integer pageSize = DEFAULT_PAGE_SIZE;
    }

    @Data
    public static class MessageQuery {
        private String conversationId;
        private String role;
        private String status;
        private String startTime;
        private String endTime;
        private String sortOrder = "asc";
        @JsonAlias({"current", "page"})
        private Integer pageNum = 1;
        @JsonAlias("size")
        private Integer pageSize = DEFAULT_PAGE_SIZE;
    }

    @Data
    public static class ConversationView {
        private String conversationId;
        private String appId;
        private String userId;
        private String invokeSource;
        private String name;
        private String status;
        private Integer messageCount;
        private Date createdAt;
        private Date updatedAt;
    }

    @Data
    public static class MessageView {
        private String messageId;
        private String parentMessageId;
        private Integer sequence;
        private String role;
        private String content;
        private String contentType;
        private String traceId;
        private String requestId;
        private String userId;
        private String status;
        private Date createdAt;
        private Date updatedAt;
    }

    @Data
    public static class ConversationDetail {
        private ConversationView conversation;
        private PagingList<MessageView> messages;
    }
}
