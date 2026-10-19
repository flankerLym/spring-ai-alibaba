package com.alibaba.cloud.ai.studio.admin.trace.aspect;

import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowSpanContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.enums.SpanKind;
import com.alibaba.cloud.ai.studio.core.workflow.trace.reporter.SpanReporter;
import com.alibaba.cloud.ai.studio.core.workflow.trace.service.WorkflowTraceManager;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentResponse;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Creates the workflow MODEL_CALL span and lightweight timing child spans for each
 * subscription to ModelExecuteManager.stream().
 *
 * <p>The parent NODE span is captured before Reactor changes threads. Timing callbacks
 * therefore keep using explicit span objects and do not depend on ThreadLocal
 * propagation.</p>
 */
@Aspect
@Component
@RequiredArgsConstructor
public class ModelCallTraceAspect {

    private static final String FIRST_TOKEN_PHASE = "FIRST_TOKEN";

    private static final String GENERATION_PHASE = "GENERATION";

    private final WorkflowTraceManager traceManager;

    @Around("@annotation(com.alibaba.cloud.ai.studio.core.workflow.trace.annotation.WorkflowModelCall)")
    @SuppressWarnings("unchecked")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        WorkflowSpanContext parent = SpanReporter.current();

        Object result = joinPoint.proceed();
        if (parent == null || !(result instanceof Flux<?>)) {
            return result;
        }

        Object[] args = joinPoint.getArgs();
        String provider = args.length > 0 && args[0] != null ? String.valueOf(args[0]) : null;
        String modelId = args.length > 1 && args[1] != null ? String.valueOf(args[1]) : null;

        Flux<AgentResponse> source = (Flux<AgentResponse>) result;

        return Flux.defer(() -> {
            WorkflowSpanContext modelSpan =
                    traceManager.startModelCallSpan(parent, provider, modelId);

            if (modelSpan == null) {
                return source;
            }

            WorkflowSpanContext firstTokenSpan = startPhaseSpan(modelSpan, provider, modelId, FIRST_TOKEN_PHASE);
            WorkflowSpanContext[] generationSpan = new WorkflowSpanContext[1];

            return source
                    .doOnNext(response -> {
                        traceManager.recordModelResponse(modelSpan, response);

                        SpanReporter.put(
                                modelSpan,
                                "provider_response_id",
                                response.getProviderResponseId()
                        );

                        if (firstTokenSpan != null
                                && !firstTokenSpan.getFinished().get()
                                && hasFirstToken(response)) {
                            SpanReporter.put(firstTokenSpan, "first_token_received", true);
                            SpanReporter.put(firstTokenSpan, "finish_signal", FIRST_TOKEN_PHASE);
                            traceManager.finishModelCallSpan(firstTokenSpan, FIRST_TOKEN_PHASE);

                            generationSpan[0] = startPhaseSpan(
                                    modelSpan, provider, modelId, GENERATION_PHASE);
                            if (generationSpan[0] != null) {
                                SpanReporter.put(generationSpan[0], "first_token_received", true);
                            }
                        }
                    })
                    .doOnError(error -> {
                        if (firstTokenSpan != null && !firstTokenSpan.getFinished().get()) {
                            traceManager.recordSpanError(firstTokenSpan, error);
                        }
                        if (generationSpan[0] != null && !generationSpan[0].getFinished().get()) {
                            traceManager.recordSpanError(generationSpan[0], error);
                        }
                        traceManager.recordSpanError(modelSpan, error);
                    })
                    .doFinally(signalType -> {
                        if (firstTokenSpan != null && !firstTokenSpan.getFinished().get()) {
                            SpanReporter.put(firstTokenSpan, "first_token_received", false);
                            SpanReporter.put(firstTokenSpan, "finish_signal", signalType.name());
                            traceManager.finishModelCallSpan(firstTokenSpan, signalType.name());
                        }
                        if (generationSpan[0] != null && !generationSpan[0].getFinished().get()) {
                            SpanReporter.put(generationSpan[0], "finish_signal", signalType.name());
                            traceManager.finishModelCallSpan(generationSpan[0], signalType.name());
                        }
                        traceManager.finishModelCallSpan(modelSpan, signalType.name());
                    });
        });
    }

    /**
     * Reuse the existing MODEL_CALL span lifecycle so phase spans participate in the
     * same active-span accounting, persistence barrier and idempotent finish logic.
     *
     * <p>A lightweight parent view is used so creating a phase does not consume the
     * NODE span's model-attempt sequence. The returned span is then retyped as MODEL_PHASE.</p>
     */
    private WorkflowSpanContext startPhaseSpan(
            WorkflowSpanContext modelSpan,
            String provider,
            String modelId,
            String phase) {

        WorkflowSpanContext parentView = new WorkflowSpanContext();
        parentView.setSpanId(modelSpan.getSpanId());
        parentView.setTraceId(modelSpan.getTraceId());
        parentView.setNodeId(modelSpan.getNodeId());
        parentView.setNodeName(modelSpan.getNodeName());
        parentView.setNodeType(modelSpan.getNodeType());

        WorkflowSpanContext phaseSpan =
                traceManager.startModelCallSpan(parentView, provider, modelId);
        if (phaseSpan == null) {
            return null;
        }

        phaseSpan.setSpanKind(SpanKind.MODEL_PHASE.name());
        phaseSpan.setSpanName("MODEL_" + phase);
        phaseSpan.setAttemptNo(modelSpan.getAttemptNo());
        SpanReporter.put(phaseSpan, "phase", phase);
        return phaseSpan;
    }

    /**
     * Count the first meaningful model output event rather than metadata-only/usage
     * chunks as the first token.
     */
    private boolean hasFirstToken(AgentResponse response) {
        if (response == null || !response.isSuccess() || response.getMessage() == null) {
            return false;
        }

        Object content = response.getMessage().getContent();
        if (content != null && !String.valueOf(content).isEmpty()) {
            return true;
        }

        String reasoningContent = response.getMessage().getReasoningContent();
        return reasoningContent != null && !reasoningContent.isEmpty();
    }

}
