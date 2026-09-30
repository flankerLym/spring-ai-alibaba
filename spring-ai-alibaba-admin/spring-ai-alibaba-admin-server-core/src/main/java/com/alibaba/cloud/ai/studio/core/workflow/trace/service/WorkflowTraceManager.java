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

        log.debug("workflow trace started, traceId={}, appId={}, invokeSource={}",
                trace.getTraceId(), trace.getAppId(), trace.getInvokeSource());

        return trace;
    }

    /** Reserve before submitting to the executor; release on completion or rejection. */
    public WorkflowTraceContext retainExecution(WorkflowContext context) {
        WorkflowTraceContext trace = getTrace(context);
        if (trace == null) {
            return null;
        }
        synchronized (trace) {
            if (trace.getPersisted().get()) {
                return null;
            }
            trace.getPendingExecutions().incrementAndGet();
            return trace;
        }
    }

    public void releaseExecution(WorkflowTraceContext trace) {
        if (trace != null) {
            trace.getPendingExecutions().decrementAndGet();
            tryPersist(trace);
        }
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
        if (trace == null || node == null) {
            if (context != null && StringUtils.isNotBlank(context.getTraceId())) {
                log.warn("cannot start NODE span because trace is not in registry, traceId={}, nodeId={}, taskId={}",
                        context.getTraceId(), node == null ? null : node.getId(), context.getTaskId());
            }
            return null;
        }

        /*
         * Span admission and activeSpans++ must be atomic with tryPersist().
         * Otherwise an API/event thread can finish the trace between the old
         * finishRequested check and activeSpans++, leaving the later node span detached.
         */
        synchronized (trace) {
            if (trace.getPersisted().get()) {
                log.warn("cannot start NODE span because trace is already persisted, traceId={}, nodeId={}",
                        trace.getTraceId(), node.getId());
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
                trace.setRootSpanId(span.getSpanId());
            }

            trace.getActiveSpans().incrementAndGet();

            log.debug("workflow NODE span started, traceId={}, spanId={}, nodeId={}, activeSpans={}",
                    trace.getTraceId(), span.getSpanId(), node.getId(), trace.getActiveSpans().get());

            return span;
        }
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
            log.error("NODE span cannot finish because trace is missing from registry, traceId={}, spanId={}, nodeId={}",
                    span.getTraceId(), span.getSpanId(), span.getNodeId());
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

                /*
                 * One malformed diagnostic payload must never make the whole real NODE span
                 * disappear. Each optional diagnostic field is filled independently.
                 */
                try {
                    span.setInputData(normalizeJson(result.getInput()));
                }
                catch (Exception e) {
                    log.warn("normalize NODE span input failed, traceId={}, spanId={}",
                            span.getTraceId(), span.getSpanId(), e);
                }

                try {
                    span.setOutputData(normalizeJson(result.getOutput()));
                }
                catch (Exception e) {
                    log.warn("normalize NODE span output failed, traceId={}, spanId={}",
                            span.getTraceId(), span.getSpanId(), e);
                }

                span.setErrorCode(result.getErrorCode());
                span.setErrorMessage(result.getErrorInfo());

                if (result.getError() != null) {
                    try {
                        span.setErrorData(JsonUtils.toJson(result.getError()));
                    }
                    catch (Exception e) {
                        log.warn("serialize NODE span error failed, traceId={}, spanId={}",
                                span.getTraceId(), span.getSpanId(), e);
                    }
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
        }
        finally {
            /*
             * This is a real span that did start. Always collect it even if optional
             * diagnostic enrichment failed above.
             */
            trace.getSpans().add(span);
            int active = trace.getActiveSpans().decrementAndGet();

            log.debug("workflow NODE span finished, traceId={}, spanId={}, nodeId={}, activeSpans={}",
                    trace.getTraceId(), span.getSpanId(), span.getNodeId(), active);

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
        if (trace == null) {
            log.warn("cannot start MODEL_CALL span because trace is missing, traceId={}, parentSpanId={}",
                    parentSpan.getTraceId(), parentSpan.getSpanId());
            return null;
        }

        synchronized (trace) {
            if (trace.getPersisted().get()) {
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
            log.error("MODEL_CALL span cannot finish because trace is missing, traceId={}, spanId={}",
                    span.getTraceId(), span.getSpanId());
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
        }
        finally {
            trace.getSpans().add(span);
            trace.getActiveSpans().decrementAndGet();
            tryPersist(trace);
        }
    }

    public void requestFinish(WorkflowContext context, TraceFinishReason reason) {
        requestFinish(context, reason, null);
    }

    public void finishException(WorkflowContext context, Throwable error) {
        requestFinish(context, TraceFinishReason.EXCEPTION, error);
    }

    /**
     * Global trace completion is owned by the workflow execution lifecycle
     * (WorkflowExecuteManager async finally), not by the API/SSE lifecycle.
     */
    public void finishIfNecessary(WorkflowContext context) {
        WorkflowTraceContext trace = getTrace(context);
        if (trace == null) {
            if (context != null && StringUtils.isNotBlank(context.getTraceId())) {
                log.error("workflow finish cannot find trace in registry, traceId={}, taskId={}",
                        context.getTraceId(), context.getTaskId());
            }
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
        if (trace == null) {
            return;
        }

        /*
         * Use the same monitor as span admission. This makes
         * "activeSpans == 0 -> freeze persistence" atomic against a new span start.
         */
        synchronized (trace) {
            if (!trace.getFinishRequested().get()
                    || trace.getActiveSpans().get() != 0
                    || trace.getPendingExecutions().get() != 0
                    || trace.getPersisted().get()) {
                return;
            }
            trace.getPersisted().set(true);
        }

        boolean saved = false;
        try {
            aggregate(trace);

            log.debug("persist workflow trace, traceId={}, rootSpanId={}, spanCount={}, modelCallCount={}",
                    trace.getTraceId(), trace.getRootSpanId(), trace.getSpanCount(), trace.getModelCallCount());

            traceStore.save(trace);
            saved = true;
            log.info("workflow trace persisted, traceId={}, taskId={}, invokeSource={}, spanCount={}, modelCallCount={}",
                    trace.getTraceId(), trace.getTaskId(), trace.getInvokeSource(),
                    trace.getSpanCount(), trace.getModelCallCount());
        }
        catch (Exception e) {
            /*
             * Do not permanently poison/remove the trace on a failed DB write.
             * A later finish callback may retry.
             */
            trace.getPersisted().set(false);
            log.error("persist workflow trace failed, traceId={}", trace.getTraceId(), e);
        }
        finally {
            if (saved) {
                traceRegistry.remove(trace.getTraceId());
            }
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
