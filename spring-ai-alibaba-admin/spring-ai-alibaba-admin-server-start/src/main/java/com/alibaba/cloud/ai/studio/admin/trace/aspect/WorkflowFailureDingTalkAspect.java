package com.alibaba.cloud.ai.studio.admin.trace.aspect;

import com.alibaba.cloud.ai.studio.admin.trace.service.DingTalkWorkflowFailureNotifier;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Sends one notification after the workflow lifecycle has finalized a failed status. */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class WorkflowFailureDingTalkAspect {

    private final DingTalkWorkflowFailureNotifier notifier;

    @AfterReturning(
            pointcut = "execution(* com.alibaba.cloud.ai.studio.core.workflow.trace.service.WorkflowTraceManager.finishIfNecessary(..))"
                    + " && args(context)",
            argNames = "context")
    public void afterWorkflowFinished(WorkflowContext context) {
        if (context == null || !NodeStatusEnum.FAIL.getCode().equals(context.getTaskStatus())) {
            return;
        }
        notifier.notifyFailure(context);
    }
}
