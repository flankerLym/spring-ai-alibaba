package com.alibaba.cloud.ai.studio.admin.conversation;

import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.conversation.ConversationManager;
import com.alibaba.cloud.ai.studio.core.conversation.ConversationPersistenceScope;
import com.alibaba.cloud.ai.studio.core.utils.common.IdGenerator;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
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

/**
 * Low-intrusion conversation lifecycle integration.
 *
 * 1. Workflow runTask: creates/touches conversation_record and generates a numeric
 *    conversation id when omitted.
 * 2. Normal END: persists one user/assistant round after END succeeds.
 * 3. Auto END: handleNodeResult is called directly by WorkflowExecuteManager, so a
 *    second pointcut covers that path as well.
 *
 * No WorkflowExecuteManager/AbstractExecuteProcessor source change is required.
 */
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

		RequestContext requestContext = RequestContextHolder.getRequestContext();
		String userId = requestContext == null ? null : requestContext.getAccountId();

		conversationManager.prepareConversation(
				appVersion.getAppId(),
				conversationId,
				workflowContext.getInvokeSource(),
				userId);

		return joinPoint.proceed(args);
	}

	/**
	 * Normal END path: END executes through AbstractExecuteProcessor.execute().
	 */
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
				conversationManager.saveWorkflowRound(context);
			}
			return result;
		}
		finally {
			ConversationPersistenceScope.exitWorkflowEnd();
		}
	}

	/**
	 * Auto END path: WorkflowExecuteManager calls EndExecuteProcessor.handleNodeResult()
	 * directly, bypassing execute(). This pointcut keeps persistence behavior identical.
	 */
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
				conversationManager.saveWorkflowRound(context);
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
