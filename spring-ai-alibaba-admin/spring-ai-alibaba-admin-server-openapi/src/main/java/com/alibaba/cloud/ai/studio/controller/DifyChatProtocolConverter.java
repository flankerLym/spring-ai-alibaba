package com.alibaba.cloud.ai.studio.controller;

import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.WorkflowStatus;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.TaskRunParam;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.WorkflowRequest;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.WorkflowResponse;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Converts the internal workflow wire model to the Dify Chatflow protocol. */
public final class DifyChatProtocolConverter {

    private final DifyChatRequest request;
    private final String appId;
    private String conversationId;
    private final String messageId = UUID.randomUUID().toString();
    private final String workflowRunId = UUID.randomUUID().toString();
    private final long startedAtMillis = System.currentTimeMillis();
    private final long createdAt = startedAtMillis / 1000L;
    private final Map<String, String> nodeExecutionIds = new LinkedHashMap<>();
    private final Map<String, Integer> nodeIndexes = new LinkedHashMap<>();
    private final Map<String, Long> nodeStartedAt = new LinkedHashMap<>();
    private final Set<String> startedNodes = new LinkedHashSet<>();
    private final StringBuilder answer = new StringBuilder();
    private final Map<String, StringBuilder> outputNodeContent = new LinkedHashMap<>();
    private final Map<String, StringBuilder> nodeContent = new LinkedHashMap<>();
    private String lastOutputNodeId;

    private String taskId = UUID.randomUUID().toString();
    private boolean workflowStartedSent;
    private boolean finishedSent;

    public DifyChatProtocolConverter(DifyChatRequest request, String appId) {
        this.request = request;
        this.appId = appId;
        this.conversationId = StringUtils.isNotBlank(request.getConversationId())
                ? request.getConversationId().trim() : UUID.randomUUID().toString();
    }

    /** Convert the Dify request body to the existing internal workflow request. */
    public WorkflowRequest toWorkflowRequest() {
        WorkflowRequest result = new WorkflowRequest();
        result.setAppId(appId);
        result.setUserId(StringUtils.trimToNull(request.getUser()));
        result.setConversationId(conversationId);
        result.setTraceId(StringUtils.trimToNull(request.getTraceId()));
        result.setAutoGenerateName(request.getAutoGenerateName());
        result.setStream(isStreaming(request.getResponseMode()));
        result.setDraft(false);

        List<TaskRunParam> params = new ArrayList<>();
        Map<String, Object> inputs = request.getInputs() == null
                ? Collections.emptyMap() : request.getInputs();
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            if (StringUtils.isBlank(entry.getKey())) {
                continue;
            }
            params.add(parameter(entry.getKey(), inferType(entry.getValue()), entry.getValue(), "user"));
        }

        // Dify system variables are exposed by the workflow engine under ${sys.*}.
        params.add(parameter("query", "String", request.getQuery(), "sys"));
        if (StringUtils.isNotBlank(request.getUser())) {
            params.add(parameter("user_id", "String", request.getUser().trim(), "sys"));
        }
        params.add(parameter("conversation_id", "String", conversationId, "sys"));
        if (request.getFiles() != null && !request.getFiles().isEmpty()) {
            params.add(parameter("files", "Array<Object>", normalizeFiles(request.getFiles()), "sys"));
        }

        result.setInputParams(params);
        return result;
    }

    /** Dify blocking-mode ChatCompletionResponse. */
    public Object toBlockingResponse(WorkflowResponse response) {
        refreshIdentifiers(response);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "message");
        body.put("task_id", taskId);
        body.put("id", messageId);
        body.put("message_id", messageId);
        body.put("conversation_id", conversationId);
        body.put("mode", "chat");
        body.put("answer", asText(response == null ? null : response.getBusinessContent()));
        body.put("metadata", metadata());
        body.put("created_at", createdAt);
        return body;
    }

    /**
     * Convert one internal stream item to zero or more Dify SSE payloads. The transport
     * sends each returned JSON object as a separate SSE data frame.
     */
    public synchronized List<String> toStreamEvents(WorkflowResponse response) {
        List<Map<String, Object>> events = new ArrayList<>();
        refreshIdentifiers(response);

        if (!workflowStartedSent) {
            events.add(workflowStartedEvent());
            workflowStartedSent = true;
        }

        WorkflowStatus status = response == null ? null : response.getStatus();
        Error error = response == null ? null : response.getError();
        if (error != null || status == WorkflowStatus.FAILED) {
            Error effectiveError = error == null
                    ? null : error;
            events.add(workflowFinishedEvent("failed", effectiveError == null
                    ? "Workflow execution failed" : effectiveError.getMessage()));
            events.add(errorEvent(effectiveError));
            finishedSent = true;
            return serialize(events);
        }

        if (status == WorkflowStatus.PAUSE) {
            events.add(workflowFinishedEvent("stopped", "Workflow paused before returning a final answer"));
            events.add(messageEndEvent());
            finishedSent = true;
            return serialize(events);
        }

        if (response != null && StringUtils.isNotBlank(response.getNodeId())) {
            String nodeId = response.getNodeId();
            if (startedNodes.add(nodeId)) {
                events.add(nodeStartedEvent(response));
            }

            String chunk = asText(response.getBusinessContent());
            String nodeType = StringUtils.defaultString(response.getNodeType()).toLowerCase();
            if (!chunk.isEmpty()) {
                nodeContent.computeIfAbsent(nodeId, ignored -> new StringBuilder()).append(chunk);
            }
            if (!chunk.isEmpty() && "output".equals(nodeType)) {
                outputNodeContent.computeIfAbsent(nodeId, ignored -> new StringBuilder()).append(chunk);
                lastOutputNodeId = nodeId;
            }
            else if (!chunk.isEmpty() && "end".equals(nodeType)) {
                answer.append(chunk);
                events.add(messageEvent(chunk));
            }
            else if (!chunk.isEmpty() && !"input".equals(nodeType)) {
                // Preserve user-visible content for node types that are not part of the
                // standard Start/Output/End workflow shape.
                answer.append(chunk);
                events.add(messageEvent(chunk));
            }

            if (Boolean.TRUE.equals(response.getNodeIsCompleted())
                    || "success".equalsIgnoreCase(response.getNodeStatus())) {
                events.add(nodeFinishedEvent(response, "succeeded"));
            }
            else if ("fail".equalsIgnoreCase(response.getNodeStatus())) {
                events.add(nodeFinishedEvent(response, "failed"));
            }
        }
        else if (response != null) {
            String chunk = asText(response.getBusinessContent());
            if (!chunk.isEmpty()) {
                answer.append(chunk);
                events.add(messageEvent(chunk));
            }
        }

        if (status == WorkflowStatus.COMPLETED && !finishedSent) {
            // The internal blocking aggregator prefers End-node content, otherwise the
            // last Output node. Buffer Output-node deltas until completion so a workflow
            // containing both Output and End nodes cannot duplicate the answer stream.
            if (answer.length() == 0 && lastOutputNodeId != null) {
                String fallbackAnswer = outputNodeContent.getOrDefault(lastOutputNodeId, new StringBuilder()).toString();
                if (!fallbackAnswer.isEmpty()) {
                    answer.append(fallbackAnswer);
                    events.add(messageEvent(fallbackAnswer));
                }
            }
            events.add(workflowFinishedEvent("succeeded", null));
            events.add(messageEndEvent());
            finishedSent = true;
        }
        return serialize(events);
    }

    /** Called when the internal stream completes without a terminal item (for example STOP). */
    public synchronized List<String> onStreamComplete() {
        if (finishedSent) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> events = new ArrayList<>();
        if (!workflowStartedSent) {
            events.add(workflowStartedEvent());
            workflowStartedSent = true;
        }
        events.add(workflowFinishedEvent("stopped", "Workflow stream ended before a successful completion event"));
        events.add(messageEndEvent());
        finishedSent = true;
        return serialize(events);
    }

    /** Dify HTTP error body for blocking requests and validation failures. */
    public Object toErrorResponse(Error error) {
        if (error == null) {
            return errorResponse(500, "internal_server_error", "Internal server error");
        }
        int status = error.getStatusCode() == null ? 500 : error.getStatusCode();
        return errorResponse(status, difyErrorCode(error.getCode()),
                StringUtils.defaultIfBlank(error.getMessage(), "Workflow execution failed"));
    }

    public static Map<String, Object> errorResponse(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("code", code);
        body.put("message", message);
        return body;
    }

    private Map<String, Object> workflowStartedEvent() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", workflowRunId);
        data.put("workflow_id", appId);
        data.put("created_at", createdAt);
        return event("workflow_started", data);
    }

    private Map<String, Object> nodeStartedEvent(WorkflowResponse response) {
        String nodeId = response.getNodeId();
        int index = indexFor(nodeId);
        long now = System.currentTimeMillis() / 1000L;
        nodeStartedAt.putIfAbsent(nodeId, now);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", nodeExecutionIds.computeIfAbsent(nodeId, ignored -> UUID.randomUUID().toString()));
        data.put("node_id", nodeId);
        putIfNotNull(data, "node_type", response.getNodeType());
        putIfNotNull(data, "title", response.getNodeName());
        data.put("index", index);
        data.put("inputs", Collections.emptyMap());
        data.put("created_at", now);
        return event("node_started", data);
    }

    private Map<String, Object> nodeFinishedEvent(WorkflowResponse response, String status) {
        String nodeId = response.getNodeId();
        long now = System.currentTimeMillis() / 1000L;
        long nodeCreatedAt = nodeStartedAt.getOrDefault(nodeId, now);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", nodeExecutionIds.computeIfAbsent(nodeId, ignored -> UUID.randomUUID().toString()));
        data.put("node_id", nodeId);
        putIfNotNull(data, "node_type", response.getNodeType());
        putIfNotNull(data, "title", response.getNodeName());
        data.put("index", indexFor(nodeId));
        data.put("inputs", Collections.emptyMap());

        // WorkflowResponse exposes node content, not the full node variable map.
        // Report observed content only; do not fabricate token usage or process data.
        Map<String, Object> outputs = new LinkedHashMap<>();
        StringBuilder nodeOutput = nodeContent.get(nodeId);
        if (nodeOutput != null && nodeOutput.length() > 0) {
            outputs.put("output", nodeOutput.toString());
        }
        data.put("outputs", outputs);
        data.put("process_data", Collections.emptyMap());
        if ("failed".equals(status)) {
            putIfNotNull(data, "error", response.getBusinessErrorMessage());
        }

        data.put("status", status);
        data.put("elapsed_time", Math.max(0L, now - nodeCreatedAt));
        data.put("created_at", nodeCreatedAt);
        data.put("finished_at", now);
        return event("node_finished", data);
    }

    private Map<String, Object> workflowFinishedEvent(String status, String errorMessage) {
        long nowMillis = System.currentTimeMillis();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", workflowRunId);
        data.put("workflow_id", appId);
        data.put("status", status);
        data.put("outputs", status.equals("succeeded")
                ? Collections.singletonMap("answer", answer.toString()) : Collections.emptyMap());
        if (StringUtils.isNotBlank(errorMessage)) {
            data.put("error", errorMessage);
        }
        data.put("elapsed_time", Math.max(0.0d, (nowMillis - startedAtMillis) / 1000.0d));
        data.put("total_steps", nodeIndexes.size());
        data.put("created_at", createdAt);
        data.put("finished_at", nowMillis / 1000L);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "workflow_finished");
        event.put("task_id", taskId);
        event.put("workflow_run_id", workflowRunId);
        event.put("data", data);
        return event;
    }

    private Map<String, Object> messageEvent(String chunk) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "message");
        body.put("task_id", taskId);
        body.put("message_id", messageId);
        body.put("conversation_id", conversationId);
        body.put("answer", chunk);
        body.put("created_at", System.currentTimeMillis() / 1000L);
        return body;
    }

    private Map<String, Object> messageEndEvent() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "message_end");
        body.put("task_id", taskId);
        body.put("id", messageId);
        body.put("message_id", messageId);
        body.put("conversation_id", conversationId);
        body.put("metadata", metadata());
        body.put("created_at", System.currentTimeMillis() / 1000L);
        return body;
    }

    private Map<String, Object> errorEvent(Error error) {
        int status = error == null || error.getStatusCode() == null ? 500 : error.getStatusCode();
        String code = error == null ? "completion_request_error" : difyErrorCode(error.getCode());
        String message = error == null ? "Workflow execution failed"
                : StringUtils.defaultIfBlank(error.getMessage(), "Workflow execution failed");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "error");
        body.put("task_id", taskId);
        body.put("message_id", messageId);
        body.put("status", status);
        body.put("code", code);
        body.put("message", message);
        return body;
    }

    private Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        // The current internal WorkflowResponse does not expose usage accounting or
        // Dify retriever-resource records. Do not fabricate token counts or citations.
        metadata.put("usage", Collections.emptyMap());
        metadata.put("retriever_resources", Collections.emptyList());
        return metadata;
    }

    private Map<String, Object> event(String eventName, Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", eventName);
        body.put("task_id", taskId);
        body.put("workflow_run_id", workflowRunId);
        body.put("data", data);
        return body;
    }

    private void refreshIdentifiers(WorkflowResponse response) {
        if (response == null) {
            return;
        }
        if (StringUtils.isNotBlank(response.getTaskId())) {
            taskId = response.getTaskId();
        }
        if (StringUtils.isNotBlank(response.getConversationId())) {
            conversationId = response.getConversationId();
        }
    }

    private int indexFor(String nodeId) {
        return nodeIndexes.computeIfAbsent(nodeId, ignored -> nodeIndexes.size());
    }

    private static TaskRunParam parameter(String key, String type, Object value, String source) {
        TaskRunParam param = new TaskRunParam();
        param.setKey(key);
        param.setType(type);
        param.setValue(value);
        param.setSource(source);
        return param;
    }

    private static List<Map<String, Object>> normalizeFiles(List<Map<String, Object>> files) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> file : files) {
            if (file == null) {
                continue;
            }

            // Preserve the Dify file object and add aliases used by existing SAA nodes.
            Map<String, Object> value = new LinkedHashMap<>(file);
            Object name = file.get("name");
            if (name == null) {
                name = file.get("filename");
            }
            putIfNotNull(value, "name", name);

            Object transferMethodValue = file.get("transfer_method");
            String transferMethod = transferMethodValue == null ? "" : transferMethodValue.toString();
            if ("remote_url".equalsIgnoreCase(transferMethod)) {
                value.put("source", "remoteUrl");
            }
            else if ("local_file".equalsIgnoreCase(transferMethod)) {
                value.put("source", "localFile");
                putIfNotNull(value, "upload_file_id", file.get("upload_file_id"));
            }
            normalized.add(value);
        }
        return normalized;
    }

    private static String inferType(Object value) {
        if (value instanceof Boolean) return "Boolean";
        if (value instanceof Number) return "Number";
        if (value instanceof Map<?, ?>) return "Object";
        if (value instanceof List<?> list) {
            if (list.stream().allMatch(item -> item == null || item instanceof String)) return "Array<String>";
            if (list.stream().allMatch(item -> item == null || item instanceof Number)) return "Array<Number>";
            if (list.stream().allMatch(item -> item == null || item instanceof Boolean)) return "Array<Boolean>";
            return "Array<Object>";
        }
        return "String";
    }

    private static boolean isStreaming(String responseMode) {
        return StringUtils.isBlank(responseMode) || "streaming".equalsIgnoreCase(responseMode.trim());
    }


    public static String difyErrorCode(String internalCode) {
        String code = StringUtils.defaultString(internalCode).toLowerCase();
        if (code.contains("invalid_param") || code.contains("invalid_params")
                || code.contains("missing_params")) {
            return "invalid_param";
        }
        if (code.contains("app_unavailable") || code.contains("app_not_found")
                || code.contains("api_key") || code.contains("apikey")) {
            return "app_unavailable";
        }
        if (code.contains("workflow_not_found")) {
            return "workflow_not_found";
        }
        if (code.contains("provider_not_initialize")) {
            return "provider_not_initialize";
        }
        if (code.contains("provider_quota_exceeded")) {
            return "provider_quota_exceeded";
        }
        if (code.contains("model_currently_not_support")) {
            return "model_currently_not_support";
        }
        return "completion_request_error";
    }

    private static String asText(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return text;
        return JsonUtils.toJson(value);
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private static List<String> serialize(List<Map<String, Object>> events) {
        List<String> result = new ArrayList<>(events.size());
        for (Map<String, Object> event : events) {
            result.add(JsonUtils.toJson(event));
        }
        return result;
    }
}
