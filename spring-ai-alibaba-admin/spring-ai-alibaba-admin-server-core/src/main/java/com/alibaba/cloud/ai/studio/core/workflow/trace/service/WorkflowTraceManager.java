package com.alibaba.cloud.ai.studio.core.workflow.trace.service;

import com.alibaba.cloud.ai.studio.core.utils.common.IdGenerator;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowSpanContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowTraceContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.enums.SpanKind;
import com.alibaba.cloud.ai.studio.core.workflow.trace.enums.TraceFinishReason;
import com.alibaba.cloud.ai.studio.core.workflow.trace.registry.WorkflowTraceRegistry;
import com.alibaba.cloud.ai.studio.core.workflow.trace.store.WorkflowTraceStore;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentResponse;
import com.alibaba.cloud.ai.studio.runtime.domain.app.ApplicationVersion;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.Usage;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeResult;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeStatusEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowTraceManager {

    private static final String AUTO_END_PREFIX = "End_Auto_";

    private final WorkflowTraceRegistry traceRegistry;
    private final WorkflowTraceStore traceStore;

    public WorkflowTraceContext startTrace(
            ApplicationVersion appVersion,
            WorkflowContext context,
            Object rawInput) {

        if (appVersion == null || context == null) {
            return null;
        }

        WorkflowTraceContext trace = new WorkflowTraceContext();
        trace.setTraceId(IdGenerator.uuid());
        trace.setAppId(appVersion.getAppId());
        trace.setWorkflowVersion(appVersion.getVersion());
        trace.setInvokeSource(context.getInvokeSource());
        trace.setStatus(NodeStatusEnum.EXECUTING.getCode());
        trace.setStartTime(System.currentTimeMillis());
        trace.setTraceData("{}");

        if (rawInput != null) {
            trace.setInputData(JsonUtils.toJson(rawInput));
        }

        context.setTraceId(trace.getTraceId());
        traceRegistry.register(trace);
        return trace;
    }

    public void refreshTrace(WorkflowContext context) {
        WorkflowTraceContext trace = getTrace(context);
        if (trace != null) {
            copyFinalState(trace, context);
        }
    }

    public WorkflowSpanContext startNodeSpan(
            WorkflowContext context,
            Node node,
            WorkflowSpanContext parentSpan) {

        WorkflowTraceContext trace = getTrace(context);
        if (trace == null || node == null || trace.getFinishRequested().get()) {
            return null;
        }

        WorkflowSpanContext span = new WorkflowSpanContext();
        span.setSpanId(IdGenerator.uuid());
        span.setTraceId(trace.getTraceId());
        span.setParentSpanId(parentSpan == null ? null : parentSpan.getSpanId());
        span.setSpanKind(SpanKind.NODE.name());
        span.setSpanName(node.getName() == null ? node.getId() : node.getName());
        span.setSequenceNo(trace.getSequence().getAndIncrement());
        span.setNodeId(node.getId());
        span.setNodeName(node.getName());
        span.setNodeType(node.getType());
        span.setAttemptNo(0);
        span.setStatus(NodeStatusEnum.EXECUTING.getCode());
        span.setStartTime(System.currentTimeMillis());

        if (span.getParentSpanId() == null && trace.getRootSpanId() == null) {
            synchronized (trace) {
                if (trace.getRootSpanId() == null) {
                    trace.setRootSpanId(span.getSpanId());
                }
            }
        }

        trace.getActiveSpans().incrementAndGet();
        return span;
    }

    public void finishNodeSpan(
            WorkflowContext context,
            WorkflowSpanContext span,
            NodeResult result) {

        if (span == null || !span.getFinished().compareAndSet(false, true)) {
            return;
        }

        WorkflowTraceContext trace = traceRegistry.get(span.getTraceId());
        if (trace == null) {
            return;
        }

        try {
            long end = System.currentTimeMillis();
            span.setEndTime(end);
            span.setDurationMs(Math.max(0, end - span.getStartTime()));

            if (result != null) {
                span.setStatus(result.getNodeStatus());
                span.setNodeId(result.getNodeId() == null ? span.getNodeId() : result.getNodeId());
                span.setNodeName(result.getNodeName() == null ? span.getNodeName() : result.getNodeName());
                span.setNodeType(result.getNodeType() == null ? span.getNodeType() : result.getNodeType());
                span.setInputData(normalizeJson(result.getInput()));
                span.setOutputData(normalizeJson(result.getOutput()));
                span.setErrorCode(result.getErrorCode());
                span.setErrorMessage(result.getErrorInfo());

                if (result.getError() != null) {
                    span.setErrorData(JsonUtils.toJson(result.getError()));
                }

                if (result.getRetry() != null
                        && result.getRetry().isHappened()
                        && result.getRetry().getRetryTimes() != null) {
                    span.setAttemptNo(result.getRetry().getRetryTimes());
                }
            }
            else if (StringUtils.isNotBlank(span.getErrorMessage())) {
                span.setStatus(NodeStatusEnum.FAIL.getCode());
            }

            trace.getSpans().add(span);
        }
        finally {
            trace.getActiveSpans().decrementAndGet();
            tryPersist(trace);
        }
    }

    public WorkflowSpanContext startModelCallSpan(
            WorkflowSpanContext parentSpan,
            String provider,
            String modelId) {

        if (parentSpan == null) {
            return null;
        }

        WorkflowTraceContext trace = traceRegistry.get(parentSpan.getTraceId());
        if (trace == null || trace.getFinishRequested().get()) {
            return null;
        }

        WorkflowSpanContext span = new WorkflowSpanContext();
        span.setSpanId(IdGenerator.uuid());
        span.setTraceId(parentSpan.getTraceId());
        span.setParentSpanId(parentSpan.getSpanId());
        span.setSpanKind(SpanKind.MODEL_CALL.name());
        span.setSpanName("MODEL_CALL " + safe(provider) + ":" + safe(modelId));
        span.setSequenceNo(trace.getSequence().getAndIncrement());
        span.setAttemptNo(parentSpan.getChildAttemptSequence().getAndIncrement());

        span.setNodeId(parentSpan.getNodeId());
        span.setNodeName(parentSpan.getNodeName());
        span.setNodeType(parentSpan.getNodeType());

        span.setProvider(provider);
        span.setModelId(modelId);
        span.setModelName(modelId);
        span.setStatus(NodeStatusEnum.EXECUTING.getCode());
        span.setStartTime(System.currentTimeMillis());

        Map<String, Object> input = new HashMap<>();
        input.put("provider", provider);
        input.put("modelId", modelId);
        span.setInputData(JsonUtils.toJson(input));

        trace.getActiveSpans().incrementAndGet();
        return span;
    }

    public void recordModelResponse(WorkflowSpanContext span, AgentResponse response) {
        if (span == null || response == null) {
            return;
        }

        if (!response.isSuccess()) {
            span.setStatus(NodeStatusEnum.FAIL.getCode());
            if (response.getError() != null) {
                span.setErrorMessage(response.getError().getMessage());
                span.setErrorData(JsonUtils.toJson(response.getError()));
            }
            return;
        }

        Usage usage = response.getUsage();
        if (usage != null) {
            span.setPromptTokens(nvl(usage.getPromptTokens()));
            span.setCompletionTokens(nvl(usage.getCompletionTokens()));
            span.setTotalTokens(nvl(usage.getTotalTokens()));
        }
    }

    public void recordSpanError(WorkflowSpanContext span, Throwable error) {
        if (span == null || error == null) {
            return;
        }
        span.setStatus(NodeStatusEnum.FAIL.getCode());
        span.setErrorMessage(error.getMessage());
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("type", error.getClass().getName());
        errorData.put("message", error.getMessage());
        span.setErrorData(JsonUtils.toJson(errorData));
    }

    public void finishModelCallSpan(WorkflowSpanContext span, String signalType) {
        if (span == null || !span.getFinished().compareAndSet(false, true)) {
            return;
        }

        WorkflowTraceContext trace = traceRegistry.get(span.getTraceId());
        if (trace == null) {
            return;
        }

        try {
            long end = System.currentTimeMillis();
            span.setEndTime(end);
            span.setDurationMs(Math.max(0, end - span.getStartTime()));

            if (NodeStatusEnum.EXECUTING.getCode().equals(span.getStatus())) {
                if ("CANCEL".equalsIgnoreCase(signalType)) {
                    span.setStatus(NodeStatusEnum.STOP.getCode());
                }
                else {
                    span.setStatus(NodeStatusEnum.SUCCESS.getCode());
                }
            }

            trace.getSpans().add(span);
        }
        finally {
            trace.getActiveSpans().decrementAndGet();
            tryPersist(trace);
        }
    }

    /**
     * END aspect or workflow async-finally calls this method.
     * Persistence happens only after all active spans have completed.
     */
    public void requestFinish(WorkflowContext context, TraceFinishReason reason) {
        requestFinish(context, reason, null);
    }

    public void finishException(WorkflowContext context, Throwable error) {
        requestFinish(context, TraceFinishReason.EXCEPTION, error);
    }

    public void finishIfNecessary(WorkflowContext context) {
        WorkflowTraceContext trace = getTrace(context);
        if (trace == null) {
            return;
        }

        if (trace.getFinishRequested().get()) {
            tryPersist(trace);
            return;
        }

        TraceFinishReason reason = resolveFinishReason(context);
        requestFinish(context, reason);
    }

    private void requestFinish(
            WorkflowContext context,
            TraceFinishReason reason,
            Throwable exception) {

        WorkflowTraceContext trace = getTrace(context);
        if (trace == null) {
            return;
        }

        copyFinalState(trace, context);

        if (trace.getFinishRequested().compareAndSet(false, true)) {
            trace.setFinishReason(reason.name());
            trace.setEndTime(System.currentTimeMillis());
            trace.setDurationMs(Math.max(0, trace.getEndTime() - trace.getStartTime()));

            if (exception != null) {
                trace.setStatus(NodeStatusEnum.FAIL.getCode());
                trace.setErrorMessage(exception.getMessage());

                Map<String, Object> errorData = new HashMap<>();
                errorData.put("type", exception.getClass().getName());
                errorData.put("message", exception.getMessage());
                trace.setErrorData(JsonUtils.toJson(errorData));
            }
        }

        tryPersist(trace);
    }

    private TraceFinishReason resolveFinishReason(WorkflowContext context) {
        if (context == null) {
            return TraceFinishReason.EXCEPTION;
        }

        String status = context.getTaskStatus();

        if (NodeStatusEnum.STOP.getCode().equals(status)) {
            return TraceFinishReason.STOP;
        }
        if (NodeStatusEnum.PAUSE.getCode().equals(status)) {
            return TraceFinishReason.PAUSE;
        }
        if (NodeStatusEnum.FAIL.getCode().equals(status)) {
            if ("timeout".equalsIgnoreCase(StringUtils.trimToEmpty(context.getErrorInfo()))) {
                return TraceFinishReason.TIMEOUT;
            }
            return TraceFinishReason.FAIL;
        }

        if (context.getNodeResultMap() != null) {
            boolean autoEnd = context.getNodeResultMap().keySet()
                    .stream()
                    .anyMatch(id -> id != null && id.startsWith(AUTO_END_PREFIX));
            if (autoEnd) {
                return TraceFinishReason.AUTO_END;
            }

            boolean normalEnd = context.getNodeResultMap().values()
                    .stream()
                    .filter(Objects::nonNull)
                    .anyMatch(result ->
                            NodeTypeEnum.END.getCode().equals(result.getNodeType())
                                    && NodeStatusEnum.SUCCESS.getCode().equals(result.getNodeStatus()));
            if (normalEnd) {
                return TraceFinishReason.END;
            }
        }

        return TraceFinishReason.SUCCESS;
    }

    private void copyFinalState(WorkflowTraceContext trace, WorkflowContext context) {
        if (trace == null || context == null) {
            return;
        }

        trace.setTaskId(StringUtils.defaultIfBlank(context.getTaskId(), trace.getTraceId()));
        trace.setRequestId(context.getRequestId());
        trace.setAppId(StringUtils.defaultIfBlank(context.getAppId(), trace.getAppId()));
        trace.setConversationId(context.getConversationId());
        trace.setInvokeSource(context.getInvokeSource());
        trace.setStatus(context.getTaskStatus());
        trace.setOutputData(normalizeJson(context.getTaskResult()));
        trace.setErrorCode(context.getErrorCode());
        trace.setErrorMessage(context.getErrorInfo());

        if (context.getError() != null) {
            trace.setErrorData(JsonUtils.toJson(context.getError()));
        }
    }

    private WorkflowTraceContext getTrace(WorkflowContext context) {
        if (context == null || StringUtils.isBlank(context.getTraceId())) {
            return null;
        }
        return traceRegistry.get(context.getTraceId());
    }

    private void tryPersist(WorkflowTraceContext trace) {
        if (trace == null
                || !trace.getFinishRequested().get()
                || trace.getActiveSpans().get() != 0
                || !trace.getPersisted().compareAndSet(false, true)) {
            return;
        }

        try {
            aggregate(trace);
            traceStore.save(trace);
        }
        catch (Exception e) {
            // Trace failure must never fail the workflow itself.
            log.error("persist workflow trace failed, traceId={}", trace.getTraceId(), e);
        }
        finally {
            traceRegistry.remove(trace.getTraceId());
        }
    }

    private void aggregate(WorkflowTraceContext trace) {
        long promptTokens = 0;
        long completionTokens = 0;
        long totalTokens = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        int modelCallCount = 0;

        for (WorkflowSpanContext span : trace.getSpans()) {
            if (SpanKind.MODEL_CALL.name().equals(span.getSpanKind())) {
                modelCallCount++;
                promptTokens += span.getPromptTokens();
                completionTokens += span.getCompletionTokens();
                totalTokens += span.getTotalTokens();
                if (span.getPriceCost() != null) {
                    totalCost = totalCost.add(span.getPriceCost());
                }
            }
        }

        trace.setSpanCount(trace.getSpans().size());
        trace.setModelCallCount(modelCallCount);
        trace.setPromptTokens(promptTokens);
        trace.setCompletionTokens(completionTokens);
        trace.setTotalTokens(totalTokens);
        trace.setTotalCost(totalCost);

        Map<String, Object> data = new HashMap<>();
        data.put("spanCount", trace.getSpanCount());
        data.put("modelCallCount", trace.getModelCallCount());
        trace.setTraceData(JsonUtils.toJson(data));
    }

    /**
     * Keep valid JSON objects/arrays as-is; encode plain text as a JSON string so the
     * PostgreSQL JSONB cast is always valid.
     */
    private String normalizeJson(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            JsonUtils.getObjectMapper().readTree(value);
            return value;
        }
        catch (Exception ignored) {
            return JsonUtils.toJson(value);
        }
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
