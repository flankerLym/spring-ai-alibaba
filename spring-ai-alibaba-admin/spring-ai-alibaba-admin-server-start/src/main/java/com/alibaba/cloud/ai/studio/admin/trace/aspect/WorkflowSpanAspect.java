package com.alibaba.cloud.ai.studio.admin.trace.aspect;

import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowSpanContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.reporter.SpanReporter;
import com.alibaba.cloud.ai.studio.core.workflow.trace.service.WorkflowTraceManager;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Unified NODE span aspect.
 *
 * <p>The workflow API stream and the workflow trace have independent lifecycles:
 * the stream may complete as soon as a terminal workflow event is delivered, while
 * NODE/MODEL_CALL spans are closed only by actual node/model execution completion.</p>
 *
 * <p>ConversationPersistenceAspect uses HIGHEST_PRECEDENCE + 50. This aspect runs
 * immediately after it so both advices are applied deterministically to processor
 * execute().</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class WorkflowSpanAspect {

    private final WorkflowTraceManager traceManager;

    /**
     * Keep the original annotation extension point and an execution fallback.
     *
     * The explicit public-void execution signature is intentional: all workflow nodes
     * execute through AbstractExecuteProcessor.execute(graph,node,context).
     */
    @Around(
            "@annotation(com.alibaba.cloud.ai.studio.core.workflow.trace.annotation.WorkflowSpan) "
                    + "|| execution(public void "
                    + "com.alibaba.cloud.ai.studio.core.workflow.processor.AbstractExecuteProcessor+.execute(..))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Node node = findNode(joinPoint.getArgs());
        WorkflowContext context = findWorkflowContext(joinPoint.getArgs());

        if (node == null || context == null) {
            return joinPoint.proceed();
        }

        if (context.getTraceId() == null) {
            log.warn("workflow node has no traceId, nodeId={}, nodeType={}, taskId={}",
                    node.getId(), node.getType(), context.getTaskId());
            return joinPoint.proceed();
        }

        WorkflowSpanContext parent = SpanReporter.current();
        WorkflowSpanContext span = traceManager.startNodeSpan(context, node, parent);

        if (span == null) {
            log.warn("workflow node span was not created, traceId={}, nodeId={}, nodeType={}, taskId={}",
                    context.getTraceId(), node.getId(), node.getType(), context.getTaskId());
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
            }
            catch (Exception e) {
                // Trace errors never alter node execution.
                log.error("finish workflow node span failed, traceId={}, nodeId={}",
                        context.getTraceId(), node.getId(), e);
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
