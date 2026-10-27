package com.alibaba.cloud.ai.studio.controller;

import com.alibaba.cloud.ai.studio.core.base.service.ApiKeyService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.utils.common.IdGenerator;
import com.alibaba.cloud.ai.studio.infrastructure.transport.OpenApiSseTransport;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.WorkflowRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Dify-compatible Chatflow API adapter.
 *
 * This class only adapts the external protocol. Workflow execution remains on the
 * existing OpenApiSseTransport -> OpenApiCompletionService -> WorkflowService path.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "chat")
@RequestMapping("/api/v1/dify")
public class DifyChatController {

    private final OpenApiSseTransport transport;
    private final ApiKeyService apiKeyService;

    @PostMapping("/workflow/completions")
    public Object completion(@RequestBody DifyChatRequest request,
                             HttpServletResponse response,
                             @RequestHeader(value = "X-Trace-Id", required = false) String traceIdHeader,
                             @RequestParam(value = "trace_id", required = false) String traceIdQuery) {
        RequestContext context = RequestContextHolder.getRequestContext();
        if (request == null) {
            response.setStatus(400);
            return DifyChatProtocolConverter.errorResponse(400, "invalid_param", "Request body is required");
        }

        String responseMode = StringUtils.isBlank(request.getResponseMode())
                ? "streaming" : request.getResponseMode().trim().toLowerCase();
        if (!"blocking".equals(responseMode) && !"streaming".equals(responseMode)) {
            response.setStatus(400);
            return DifyChatProtocolConverter.errorResponse(400, "invalid_param",
                    "response_mode must be either 'blocking' or 'streaming'");
        }
        request.setResponseMode(responseMode);
        if (StringUtils.isBlank(request.getQuery())) {
            response.setStatus(400);
            return DifyChatProtocolConverter.errorResponse(400, "invalid_param", "query is required");
        }
        if (StringUtils.isBlank(request.getUser())) {
            response.setStatus(400);
            return DifyChatProtocolConverter.errorResponse(400, "invalid_param", "user is required");
        }
        if (context == null || context.getApiKeyId() == null) {
            response.setStatus(401);
            return DifyChatProtocolConverter.errorResponse(401, "app_unavailable",
                    "A valid API key is required");
        }

        // Dify trace id precedence: header > query parameter > body.
        String traceId = StringUtils.isNotBlank(traceIdHeader) ? traceIdHeader
                : StringUtils.isNotBlank(traceIdQuery) ? traceIdQuery : request.getTraceId();
        if (StringUtils.isNotBlank(traceId)) {
            context.setRequestId(traceId.trim());
        }

        Set<String> allowedAppIds = apiKeyService.getAccessibleAppIds(
                context.getApiKeyId(), context.getWorkspaceId());
        if (CollectionUtils.size(allowedAppIds) != 1) {
            response.setStatus(400);
            return DifyChatProtocolConverter.errorResponse(400, "app_unavailable",
                    "This API key must be bound to exactly one application");
        }

        // The app id always comes from API-key authorization, never from request JSON.
        String appId = allowedAppIds.iterator().next();
        if (StringUtils.isBlank(appId)) {
            response.setStatus(400);
            return DifyChatProtocolConverter.errorResponse(400, "app_unavailable",
                    "The API key is not bound to a valid application");
        }

        /*
         * Dify conversation_id is an opaque string (often a UUID), while the existing
         * application conversation persistence expects a numeric BIGINT-compatible ID.
         * Normalize it at this protocol boundary, before creating WorkflowRequest.
         *
         * A valid positive numeric ID is preserved so multi-turn conversations continue.
         * For a missing/legacy non-numeric ID, allocate an internal ID; it is returned in
         * the Dify response and the client should use that returned value on the next turn.
         * No database schema or native application endpoint is changed.
         */
        request.setConversationId(normalizeConversationId(request.getConversationId()));

        DifyChatProtocolConverter converter = new DifyChatProtocolConverter(request, appId);
        WorkflowRequest workflowRequest = converter.toWorkflowRequest();
        return transport.workflowEvents(workflowRequest, response,
                converter::toBlockingResponse,
                converter::toStreamEvents,
                converter::onStreamComplete,
                converter::toErrorResponse);
    }

    private static String normalizeConversationId(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return IdGenerator.idStr();
        }

        String candidate = conversationId.trim();
        try {
            long numericId = Long.parseLong(candidate);
            if (numericId > 0) {
                return Long.toString(numericId);
            }
        }
        catch (NumberFormatException ignored) {
            // Dify's opaque UUID/string IDs are not valid internal BIGINT identifiers.
        }

        return IdGenerator.idStr();
    }
}
