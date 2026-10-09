package com.alibaba.cloud.ai.studio.core.conversation;

import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** PostgreSQL is the source of truth; Redis only accelerates the conversation snapshot. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSessionVariablesService {

    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final String PREFIX = "conversation:session-variables:";

    private final JdbcTemplate jdbcTemplate;
    private final RedisManager redisManager;

    /** Called before Start initializes variables. A cache miss always falls back to PostgreSQL. */
    public Map<String, Object> load(WorkflowContext context) {
        String key = key(context);
        String json = null;
        try {
            json = redisManager.get(key);
        }
        catch (RuntimeException e) {
            log.warn("Session variable Redis read failed, fallback to PostgreSQL, conversationId={}",
                    context.getConversationId(), e);
        }
        if (json == null) {
            Long conversationId = id(context.getConversationId(), "conversationId");
            Long appId = id(context.getAppId(), "appId");
            var rows = jdbcTemplate.query(
                    "SELECT session_variables::text FROM conversation_record WHERE id = ? AND app_id = ?",
                    (rs, rowNum) -> rs.getString(1), conversationId, appId);
            if (rows.isEmpty()) {
                throw new BizException(ErrorCode.INVALID_PARAMS.toError(
                        "conversationId", "conversation not found or does not belong to app"));
            }
            // NULL marks sessions which have not yet been migrated from legacy per-key Redis.
            json = rows.get(0) == null ? "null" : rows.get(0);
            try {
                redisManager.put(key, json, CACHE_TTL);
            }
            catch (RuntimeException e) {
                log.warn("Session variable Redis warmup failed, conversationId={}",
                        context.getConversationId(), e);
            }
        }
        if ("null".equals(json)) {
            return null; // Transitional read of legacy per-variable Redis keys in Start.
        }
        Map<String, Object> value = JsonUtils.fromJsonToMap(json);
        return value == null ? new LinkedHashMap<>() : value;
    }

    /** Store only session-scoped variables, never the entire WorkflowContext. */
    @Transactional(rollbackFor = Exception.class)
    public void save(WorkflowContext context) {
        if (context == null || context.getVariablesMap() == null) {
            return;
        }
        Object values = context.getVariablesMap().get("conversation");
        if (!(values instanceof Map<?, ?>)) {
            values = context.getVariablesMap().get("session");
        }
        if (!(values instanceof Map<?, ?> variables)) {
            return;
        }
        String json = JsonUtils.toJson(variables);
        Long conversationId = id(context.getConversationId(), "conversationId");
        Long appId = id(context.getAppId(), "appId");
        int updated = jdbcTemplate.update(
                "UPDATE conversation_record SET session_variables = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND app_id = ?",
                json, conversationId, appId);
        if (updated != 1) {
            throw new BizException(ErrorCode.INVALID_PARAMS.toError(
                    "conversationId", "conversation not found or does not belong to app"));
        }

        String cacheKey = key(context);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redisManager.delete(cacheKey);
                    redisManager.put(cacheKey, json, CACHE_TTL);
                }
                catch (RuntimeException e) {
                    log.warn("Session variable Redis refresh failed, PostgreSQL remains authoritative, "
                            + "conversationId={}", conversationId, e);
                }
            }
        });
    }

    private String key(WorkflowContext context) {
        if (context == null || StringUtils.isAnyBlank(
                context.getWorkspaceId(), context.getAppId(), context.getConversationId())) {
            throw new BizException(ErrorCode.INVALID_PARAMS.toError(
                    "conversationId", "workspaceId/appId/conversationId must not be blank"));
        }
        return PREFIX + context.getWorkspaceId() + ":" + context.getAppId() + ":" + context.getConversationId();
    }

    private Long id(String value, String name) {
        try {
            return Long.valueOf(value);
        }
        catch (NumberFormatException e) {
            throw new BizException(ErrorCode.INVALID_PARAMS.toError(name, "must be numeric bigint"));
        }
    }
}
