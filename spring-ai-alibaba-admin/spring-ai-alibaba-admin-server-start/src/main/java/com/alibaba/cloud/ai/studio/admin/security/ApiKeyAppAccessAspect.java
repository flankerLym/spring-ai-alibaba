/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.cloud.ai.studio.admin.security;

import com.alibaba.cloud.ai.studio.core.base.entity.AppEntity;
import com.alibaba.cloud.ai.studio.core.base.mapper.AppMapper;
import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.base.service.ApiKeyService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.utils.common.BeanCopierUtils;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.app.AppQuery;
import com.alibaba.cloud.ai.studio.runtime.domain.app.Application;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.AsyncResultRequest;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.TaskStopRequest;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.runtime.enums.AppStatus;
import com.alibaba.cloud.ai.studio.runtime.enums.CommonStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants.WORKFLOW_TASK_CONTEXT_PREFIX;

/**
 * Enforces app-level API-key permissions at the AppService boundary.
 * Console/session calls have no apiKeyId and keep the original behavior.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class ApiKeyAppAccessAspect {

    private final ApiKeyService apiKeyService;

    private final AppMapper appMapper;

    private final RedisManager redisManager;

    @Before("execution(* com.alibaba.cloud.ai.studio.core.base.service.impl.AppServiceImpl.getApp(..)) && args(appId)")
    public void beforeGetApp(String appId) {
        checkAccess(appId);
    }

    @Before("execution(* com.alibaba.cloud.ai.studio.core.base.service.impl.AppServiceImpl.getAppVersion(..)) && args(appId, versionId)")
    public void beforeGetAppVersion(String appId, String versionId) {
        checkAccess(appId);
    }

    @Before("execution(* com.alibaba.cloud.ai.studio.controller.ChatController.getAsyncResults(..)) && args(request)")
    public void beforeGetAsyncResults(AsyncResultRequest request) {
        if (request != null) {
            checkTaskAccess(request.getTaskId());
        }
    }

    @Before("execution(* com.alibaba.cloud.ai.studio.controller.ChatController.stopCompletion(..)) && args(request)")
    public void beforeStopCompletion(TaskStopRequest request) {
        if (request != null) {
            checkTaskAccess(request.getTaskId());
        }
    }

    @Around("execution(* com.alibaba.cloud.ai.studio.core.base.service.impl.AppServiceImpl.listApps(..)) && args(query)")
    public Object aroundListApps(ProceedingJoinPoint joinPoint, AppQuery query) throws Throwable {
        RequestContext context = RequestContextHolder.getRequestContext();
        if (!isApiKeyCall(context)) {
            return joinPoint.proceed();
        }

        Set<String> allowedAppIds = apiKeyService.getAccessibleAppIds(context.getApiKeyId(), context.getWorkspaceId());
        if (allowedAppIds == null) {
            // ALL scope: keep the exact original AppService implementation.
            return joinPoint.proceed();
        }

        if (allowedAppIds.isEmpty()) {
            return new PagingList<>(query.getCurrent(), query.getSize(), 0L, List.of());
        }

        // CUSTOM scope must be applied before pagination; filtering a page afterwards
        // would make page size/total/next-page semantics incorrect.
        Page<AppEntity> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<AppEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AppEntity::getWorkspaceId, context.getWorkspaceId());
        queryWrapper.in(AppEntity::getAppId, allowedAppIds);

        if (StringUtils.isNotBlank(query.getName())) {
            queryWrapper.like(AppEntity::getName, query.getName());
        }
        if (StringUtils.isNotBlank(query.getType())) {
            queryWrapper.eq(AppEntity::getType, query.getType());
        }
        if (query.getStatus() == null || query.getStatus() == AppStatus.DELETED) {
            queryWrapper.ne(AppEntity::getStatus, CommonStatus.DELETED.getStatus());
        }
        else {
            queryWrapper.eq(AppEntity::getStatus, query.getStatus().getStatus());
        }
        queryWrapper.orderByDesc(AppEntity::getId);

        IPage<AppEntity> pageResult = appMapper.selectPage(page, queryWrapper);
        List<Application> apps = new ArrayList<>();
        for (AppEntity entity : pageResult.getRecords()) {
            apps.add(BeanCopierUtils.copy(entity, Application.class));
        }
        return new PagingList<>(query.getCurrent(), query.getSize(), pageResult.getTotal(), apps);
    }

    private void checkTaskAccess(String taskId) {
        RequestContext context = RequestContextHolder.getRequestContext();
        if (!isApiKeyCall(context) || StringUtils.isBlank(taskId)) {
            return;
        }
        WorkflowContext workflowContext = redisManager.get(
                WORKFLOW_TASK_CONTEXT_PREFIX + context.getWorkspaceId() + "_" + taskId);
        if (workflowContext != null && StringUtils.isNotBlank(workflowContext.getAppId())) {
            apiKeyService.checkAppAccess(context.getApiKeyId(), context.getWorkspaceId(), workflowContext.getAppId());
        }
    }

    private void checkAccess(String appId) {
        RequestContext context = RequestContextHolder.getRequestContext();
        if (!isApiKeyCall(context) || StringUtils.isBlank(appId)) {
            return;
        }
        apiKeyService.checkAppAccess(context.getApiKeyId(), context.getWorkspaceId(), appId);
    }

    private boolean isApiKeyCall(RequestContext context) {
        return context != null && context.getApiKeyId() != null;
    }
}
