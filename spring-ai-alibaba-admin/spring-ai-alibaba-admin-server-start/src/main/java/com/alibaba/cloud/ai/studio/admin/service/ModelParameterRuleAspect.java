/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.admin.service;

import com.alibaba.cloud.ai.studio.core.model.llm.ModelParameterRuleService;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ModelConfigInfo;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ParameterRule;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keeps the new model-level parameter schema in sync without changing the
 * existing model-management and parameter-rules controller contracts.
 */
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
		if (success && Boolean.TRUE.equals(modelConfigInfo.getEnable())) {
			ruleService.ensure(modelConfigInfo);
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
