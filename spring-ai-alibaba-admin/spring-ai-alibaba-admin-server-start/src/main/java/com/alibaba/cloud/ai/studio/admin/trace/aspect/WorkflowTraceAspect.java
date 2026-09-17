package com.alibaba.cloud.ai.studio.admin.trace.aspect;

import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.service.WorkflowTraceManager;
import com.alibaba.cloud.ai.studio.runtime.domain.app.ApplicationVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Workflow entry trace aspect.
 *
 * The annotation is the primary extension point. The direct runTask execution pointcut
 * is kept as a fallback so a future refactor does not silently disable tracing.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class WorkflowTraceAspect {

    private final WorkflowTraceManager traceManager;

    @Around(
            "@annotation(com.alibaba.cloud.ai.studio.core.workflow.trace.annotation.WorkflowTrace) "
                    + "|| execution(* com.alibaba.cloud.ai.studio.core.workflow.runtime.WorkflowExecuteManager.runTask(..))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        WorkflowContext context = findWorkflowContext(joinPoint.getArgs());
        ApplicationVersion appVersion = findApplicationVersion(joinPoint.getArgs());
        Object rawInput = joinPoint.getArgs().length > 1 ? joinPoint.getArgs()[1] : null;

        try {
            if (context != null && appVersion != null && context.getTraceId() == null) {
                traceManager.startTrace(appVersion, context, rawInput);
            }
        }
        catch (Exception e) {
            // Trace creation must not block the workflow.
            log.error("create workflow trace failed", e);
        }

        try {
            Object result = joinPoint.proceed();
            traceManager.refreshTrace(context);
            return result;
        }
        catch (Throwable e) {
            try {
                traceManager.finishException(context, e);
            }
            catch (Exception traceError) {
                log.error("finish workflow trace on entry exception failed", traceError);
            }
            throw e;
        }
    }

    private WorkflowContext findWorkflowContext(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof WorkflowContext context) {
                return context;
            }
        }
        return null;
    }

    private ApplicationVersion findApplicationVersion(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof ApplicationVersion appVersion) {
                return appVersion;
            }
        }
        return null;
    }
}
