package com.alibaba.cloud.ai.studio.admin.trace.aspect;

import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowSpanContext;
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
 * Creates one MODEL_CALL span for every subscription to ModelExecuteManager.stream().
 *
 * The parent NODE span is captured before Reactor changes threads. Reactive callbacks
 * therefore do not depend on ThreadLocal propagation.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class ModelCallTraceAspect {

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

            return source
                    .doOnNext(response -> traceManager.recordModelResponse(modelSpan, response))
                    .doOnError(error -> traceManager.recordSpanError(modelSpan, error))
                    .doFinally(signalType ->
                            traceManager.finishModelCallSpan(modelSpan, signalType.name()));
        });
    }
}
