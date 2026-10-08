package com.alibaba.cloud.ai.studio.admin.security;

import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentRequest;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.WorkflowRequest;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Binds third-party caller metadata to the request/workflow context.
 *
 * OpenAPI userId comes from the request body. For API-key workflow calls, invokeSource
 * becomes the API key company_name instead of the generic "api" value.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OpenApiCallerContextAspect {

    private static final int MAX_USER_ID_LENGTH = 32;

    @Before("execution(* com.alibaba.cloud.ai.studio.controller.ChatController.*(..))")
    public void bindOpenApiUserId(JoinPoint joinPoint) {
        RequestContext context = RequestContextHolder.getRequestContext();
        if (context == null || context.getApiKeyId() == null) {
            return;
        }

        String userId = null;
        for (Object arg : joinPoint.getArgs()) {
            if (arg instanceof WorkflowRequest request) {
                userId = request.getUserId();
                break;
            }
            if (arg instanceof AgentRequest request) {
                userId = request.getUserId();
                break;
            }
        }
        context.setUserId(normalizeUserId(userId));
    }

    @Around("execution(* com.alibaba.cloud.ai.studio.core.workflow.runtime.WorkflowExecuteManager.runTask(..))")
    public Object bindWorkflowCaller(ProceedingJoinPoint joinPoint) throws Throwable {
        WorkflowContext workflowContext = findWorkflowContext(joinPoint.getArgs());
        RequestContext requestContext = RequestContextHolder.getRequestContext();

        if (workflowContext != null) {
            Long apiKeyId = workflowContext.getApiKeyId();
            if (apiKeyId == null && requestContext != null) {
                apiKeyId = requestContext.getApiKeyId();
            }

            if (apiKeyId != null) {
                String userId = workflowContext.getUserId();
                if (StringUtils.isBlank(userId) && requestContext != null) {
                    userId = requestContext.getUserId();
                }
                workflowContext.setUserId(normalizeUserId(userId));

                String companyName = StringUtils.trimToNull(workflowContext.getApiKeyCompanyName());
                if (companyName == null && requestContext != null) {
                    companyName = StringUtils.trimToNull(requestContext.getApiKeyCompanyName());
                }
                workflowContext.setInvokeSource(companyName == null ? "api" : companyName);
            }
            else if (StringUtils.isBlank(workflowContext.getUserId()) && requestContext != null) {
                // Preserve the previous console behavior: the signed-in account is the user.
                workflowContext.setUserId(StringUtils.trimToNull(requestContext.getAccountId()));
            }
        }

        return joinPoint.proceed();
    }

    private WorkflowContext findWorkflowContext(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof WorkflowContext context) {
                return context;
            }
        }
        return null;
    }

    private String normalizeUserId(String userId) {
        if (StringUtils.isBlank(userId)) {
            return null;
        }
        String normalized = userId.trim();
        if (normalized.length() > MAX_USER_ID_LENGTH) {
            throw new BizException(ErrorCode.INVALID_PARAMS
                .toError("userId", "userId must be 32 characters or fewer"));
        }
        return normalized;
    }
}
