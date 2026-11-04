/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.admin.service;

import com.alibaba.cloud.ai.studio.core.model.llm.ModelParameterRuleService;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ProviderConfigInfo;
import lombok.extern.slf4j.Slf4j;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ModelConfigInfo;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ParameterRule;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Keeps the new model-level parameter schema in sync without changing the
 * existing model-management and parameter-rules controller contracts.
 */
@Slf4j
@Aspect
@Component
public class ModelParameterRuleAspect {

	private final ModelParameterRuleService ruleService;

	public ModelParameterRuleAspect(ModelParameterRuleService ruleService) {
		this.ruleService = ruleService;
	}

	@AfterReturning(
			pointcut = "execution(boolean com.alibaba.cloud.ai.studio.core.base.manager.ModelManager.addModel(..))"
					+ " && args(modelConfigInfo)",
			returning = "success")
	public void afterAdd(ModelConfigInfo modelConfigInfo, boolean success) {
		if (success) {
			ruleService.ensure(modelConfigInfo);
		}
	}

	@AfterReturning(
			pointcut = "execution(boolean com.alibaba.cloud.ai.studio.core.base.manager.ModelManager.updateModel(..))"
					+ " && args(modelConfigInfo)",
			returning = "success")
	public void afterEnable(ModelConfigInfo modelConfigInfo, boolean success) {
		if (success && (Boolean.TRUE.equals(modelConfigInfo.getEnable()) || modelConfigInfo.getEndpointId() != null)) {
			ruleService.ensure(modelConfigInfo);
		}
	}

	
    @AfterReturning(
            pointcut = "execution(boolean com.alibaba.cloud.ai.studio.core.base.manager.ProviderManager.updateProvider(..))"
                    + " && args(providerConfigInfo)",
            returning = "success")
    public void afterProviderUpdate(ProviderConfigInfo providerConfigInfo, boolean success) {
        if (!success || providerConfigInfo == null
                || StringUtils.isBlank(providerConfigInfo.getProvider())) {
            return;
        }

        try {
            Map<String, Object> summary =
                    ruleService.refreshProviderModels(providerConfigInfo.getProvider());
            log.info("Provider configuration saved; model parameter refresh summary={}", summary);
        }
        catch (Exception error) {
            // Provider credentials have already been saved. A refresh error must not
            // turn a successful provider update into a failed save response.
            log.error("Provider model parameter refresh failed: provider={}",
                    providerConfigInfo.getProvider(), error);
        }
    }

@Around("execution(* com.alibaba.cloud.ai.studio.admin.builder.controller.ProviderController.getModelParamRules(..))")
	public Object loadModelRulesFirst(ProceedingJoinPoint joinPoint) throws Throwable {
		Object[] args = joinPoint.getArgs();
		if (args.length < 2 || !(args[0] instanceof String provider) || !(args[1] instanceof String modelId)) {
			return joinPoint.proceed();
		}

		List<ParameterRule> rules = ruleService.getOrCreate(provider, modelId);
		return rules.isEmpty() ? joinPoint.proceed() : Result.success(rules);
	}

}
