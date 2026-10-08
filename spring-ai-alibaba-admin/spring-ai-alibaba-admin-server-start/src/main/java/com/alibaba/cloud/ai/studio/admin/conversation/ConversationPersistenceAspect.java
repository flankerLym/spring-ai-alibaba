package com.alibaba.cloud.ai.studio.admin.conversation;

import com.alibaba.cloud.ai.studio.core.conversation.ConversationManager;
import com.alibaba.cloud.ai.studio.core.conversation.ConversationPersistenceScope;
import com.alibaba.cloud.ai.studio.core.utils.common.IdGenerator;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.runtime.domain.app.ApplicationVersion;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeStatusEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Conversation lifecycle integration. */
@Aspect
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class ConversationPersistenceAspect {

    private final ConversationManager conversationManager;

    @Around("@annotation(com.alibaba.cloud.ai.studio.core.conversation.annotation.Conversation)")
    public Object aroundRunTask(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        ApplicationVersion appVersion = findApplicationVersion(args);
        WorkflowContext workflowContext = findWorkflowContext(args);

        if (appVersion == null || workflowContext == null || args.length < 3) {
            return joinPoint.proceed();
        }

        String conversationId = args[2] == null ? null : String.valueOf(args[2]);
        if (StringUtils.isBlank(conversationId)) {
            conversationId = IdGenerator.idStr();
            args[2] = conversationId;
        }

        conversationManager.prepareConversation(
                appVersion.getAppId(),
                conversationId,
                workflowContext.getInvokeSource(),
                workflowContext.getUserId());

        return joinPoint.proceed(args);
    }

    @Around("execution(* com.alibaba.cloud.ai.studio.core.workflow.processor.AbstractExecuteProcessor+.execute(..))")
    public Object aroundNodeExecute(ProceedingJoinPoint joinPoint) throws Throwable {
        Node node = findNode(joinPoint.getArgs());
        WorkflowContext context = findWorkflowContext(joinPoint.getArgs());

        if (!isEnd(node) || context == null) {
            return joinPoint.proceed();
        }

        ConversationPersistenceScope.enterWorkflowEnd();
        try {
            Object result = joinPoint.proceed();
            if (NodeStatusEnum.SUCCESS.getCode().equals(context.getTaskStatus())) {
                conversationManager.saveWorkflowAssistantMessage(context);
            }
            return result;
        }
        finally {
            ConversationPersistenceScope.exitWorkflowEnd();
        }
    }

    @Around("execution(* com.alibaba.cloud.ai.studio.core.workflow.processor.AbstractExecuteProcessor+.handleNodeResult(..))")
    public Object aroundHandleNodeResult(ProceedingJoinPoint joinPoint) throws Throwable {
        Node node = findNode(joinPoint.getArgs());
        WorkflowContext context = findWorkflowContext(joinPoint.getArgs());

        if (!isEnd(node) || context == null || ConversationPersistenceScope.isWorkflowEnd()) {
            return joinPoint.proceed();
        }

        ConversationPersistenceScope.enterWorkflowEnd();
        try {
            Object result = joinPoint.proceed();
            if (NodeStatusEnum.SUCCESS.getCode().equals(context.getTaskStatus())) {
                conversationManager.saveWorkflowAssistantMessage(context);
            }
            return result;
        }
        finally {
            ConversationPersistenceScope.exitWorkflowEnd();
        }
    }

    private boolean isEnd(Node node) {
        return node != null && NodeTypeEnum.END.getCode().equals(node.getType());
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

    private ApplicationVersion findApplicationVersion(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof ApplicationVersion appVersion) {
                return appVersion;
            }
        }
        return null;
    }
}
