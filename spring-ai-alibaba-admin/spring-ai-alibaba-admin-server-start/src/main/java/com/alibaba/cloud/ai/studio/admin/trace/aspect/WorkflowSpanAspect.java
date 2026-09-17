package com.alibaba.cloud.ai.studio.admin.trace.aspect;

import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowSpanContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.enums.TraceFinishReason;
import com.alibaba.cloud.ai.studio.core.workflow.trace.reporter.SpanReporter;
import com.alibaba.cloud.ai.studio.core.workflow.trace.service.WorkflowTraceManager;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeResult;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeStatusEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Unified NODE span aspect.
 *
 * It intercepts the template method in AbstractExecuteProcessor, so individual node
 * processors do not need trace code.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class WorkflowSpanAspect {

    private final WorkflowTraceManager traceManager;

    @Around(
            "@annotation(com.alibaba.cloud.ai.studio.core.workflow.trace.annotation.WorkflowSpan) "
                    + "|| execution(* com.alibaba.cloud.ai.studio.core.workflow.processor.AbstractExecuteProcessor+.execute(..))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Node node = findNode(joinPoint.getArgs());
        WorkflowContext context = findWorkflowContext(joinPoint.getArgs());

        if (node == null || context == null || context.getTraceId() == null) {
            return joinPoint.proceed();
        }

        WorkflowSpanContext parent = SpanReporter.current();
        WorkflowSpanContext span = traceManager.startNodeSpan(context, node, parent);

        if (span == null) {
            return joinPoint.proceed();
        }

        SpanReporter.push(span);
        try {
            return joinPoint.proceed();
        }
        catch (Throwable e) {
            traceManager.recordSpanError(span, e);
            throw e;
        }
        finally {
            try {
                NodeResult result = context.getNodeResultMap().get(node.getId());
                traceManager.finishNodeSpan(context, span, result);

                if (NodeTypeEnum.END.getCode().equals(node.getType())
                        && result != null
                        && NodeStatusEnum.SUCCESS.getCode().equals(result.getNodeStatus())) {
                    traceManager.requestFinish(context, TraceFinishReason.END);
                }
            }
            catch (Exception e) {
                // Trace errors never alter node execution.
                log.error("finish workflow node span failed, nodeId={}", node.getId(), e);
            }
            finally {
                SpanReporter.pop(span);
            }
        }
    }

    private Node findNode(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Node node) {
                return node;
            }
        }
        return null;
    }

    private WorkflowContext findWorkflowContext(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof WorkflowContext context) {
                return context;
            }
        }
        return null;
    }
}
