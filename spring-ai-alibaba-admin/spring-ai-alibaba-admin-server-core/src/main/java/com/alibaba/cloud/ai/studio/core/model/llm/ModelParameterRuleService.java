/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.core.model.llm;

import com.alibaba.cloud.ai.studio.core.base.entity.ModelEntity;
import com.alibaba.cloud.ai.studio.core.base.mapper.ModelMapper;
import com.alibaba.cloud.ai.studio.core.base.manager.ProviderManager;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ModelConfigInfo;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ModelCredential;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ParameterRule;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ProviderConfigInfo;
import com.alibaba.cloud.ai.studio.runtime.enums.ParameterTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds and persists model-level parameter schemas.
 *
 * The provider model catalog is queried on first creation/first schema read.
 * Catalog APIs expose model capabilities rather than a complete request JSON
 * schema, so the result combines official model metadata with provider-specific
 * request parameter rules.
 */
@Slf4j
@Component
public class ModelParameterRuleService {

	@Resource
	private ModelMapper modelMapper;

	@Resource
	private ProviderManager providerManager;

	@Resource
	private ModelFactory modelFactory;

	@Resource
	private Map<String, ModelProvider> providerMap;

	/**
	 * Returns stored rules. If the model is an old row without rules, create them
	 * lazily so existing enabled models are migrated without a manual data script.
	 */
	public List<ParameterRule> getOrCreate(String provider, String modelId) {
		ModelEntity entity = findModel(provider, modelId);
		if (entity == null) {
			return List.of();
		}
		if (entity.getParameterRules() != null && !entity.getParameterRules().isEmpty()) {
			return entity.getParameterRules();
		}

		List<ParameterRule> rules = resolve(provider, modelId, entity.getType());
		if (!rules.isEmpty()) {
			entity.setParameterRules(rules);
			modelMapper.updateById(entity);
		}
		return rules;
	}

	/**
	 * Generates rules after a model is added or explicitly enabled.
	 */
	public void ensure(ModelConfigInfo modelConfigInfo) {
		if (modelConfigInfo == null || !Boolean.TRUE.equals(modelConfigInfo.getEnable())
				&& modelConfigInfo.getEnable() != null) {
			return;
		}
		if (!ModelConfigInfo.ModelTypeEnum.llm.name().equalsIgnoreCase(modelConfigInfo.getType())
				|| StringUtils.isBlank(modelConfigInfo.getModelId())) {
			return;
		}

		ModelEntity entity = findModel(modelConfigInfo.getProvider(), modelConfigInfo.getModelId());
		if (entity == null || (entity.getParameterRules() != null && !entity.getParameterRules().isEmpty())) {
			return;
		}

		List<ParameterRule> rules = resolve(entity.getProvider(), entity.getModelId(), entity.getType());
		if (!rules.isEmpty()) {
			entity.setParameterRules(rules);
			modelMapper.updateById(entity);
		}
	}

	/**
	 * Resolves a model-specific schema. Official provider metadata is used when
	 * available; failure to query a catalog never prevents model configuration.
	 */
	public List<ParameterRule> resolve(String provider, String modelId, String modelType) {
		if (!ModelConfigInfo.ModelTypeEnum.llm.name().equalsIgnoreCase(modelType)) {
			return providerRules(provider, modelId, modelType);
		}

		String type = ModelProviderConfig.normalize(provider);
		Set<String> tags = loadCatalogTags(provider, modelId, type);
		return switch (type) {
			case "dashscope" -> dashScopeRules(modelId, tags);
			case "deepseek" -> deepSeekRules(modelId);
			case "doubao" -> doubaoRules(modelId);
			default -> providerRules(provider, modelId, modelType);
		};
	}

	private List<ParameterRule> providerRules(String provider, String modelId, String modelType) {
		ModelProvider providerInstance = providerMap.get(provider + "Provider");
		if (providerInstance == null) {
			providerInstance = providerMap.get("OpenAIProvider");
		}
		return providerInstance == null ? List.of() : providerInstance.getParameterRules(modelId,
				StringUtils.defaultIfBlank(modelType, ModelConfigInfo.ModelTypeEnum.llm.name()));
	}

	private List<ParameterRule> dashScopeRules(String modelId, Set<String> tags) {
		String model = lower(modelId);
		List<ParameterRule> rules = new ArrayList<>();
		rules.add(number("temperature", "temperature", 0.7, 0, 2, 2, "采样温度"));
		rules.add(number("top_p", "top_p", 0.8, 0, 1, 2, "核采样概率"));
		rules.add(number("max_tokens", "max_tokens", 512, 1, 131072, 0, "最大输出 Token 数"));
		rules.add(number("presence_penalty", "presence_penalty", 0, -2, 2, 2, "存在惩罚"));
		rules.add(number("seed", "seed", null, null, null, 0, "随机种子"));

		if (model.contains("qwen") || model.contains("qwq") || model.contains("qvq")) {
			rules.add(number("top_k", "top_k", 20, 0, 100, 0, "采样候选集大小"));
			rules.add(number("repetition_penalty", "repetition_penalty", 1.05, 1, 2, 2, "重复惩罚"));
		}
		if (tags.contains("reasoning") || model.contains("qwen3") || model.contains("qwq")
				|| model.contains("qwen-plus")) {
			rules.add(booleanRule("enable_thinking", "enable_thinking", true, "是否开启思考模式"));
		}
		if (tags.contains("web_search")) {
			rules.add(booleanRule("enable_search", "enable_search", false, "是否启用联网搜索"));
		}
		return rules;
	}

	private List<ParameterRule> deepSeekRules(String modelId) {
		String model = lower(modelId);
		List<ParameterRule> rules = new ArrayList<>();
		rules.add(number("temperature", "temperature", 1.0, 0, 2, 2, "采样温度"));
		rules.add(number("top_p", "top_p", 1.0, 0, 1, 2, "核采样概率"));
		rules.add(number("max_tokens", "max_tokens", model.contains("v4") ? 65536 : 8192, 1,
				model.contains("v4") ? 393216 : 131072, 0, "最大输出 Token 数"));

		// The current Spring AI 1.1.2 DeepSeek adapter exposes these options directly.
		// v4 has different restrictions, so do not show the legacy penalties there.
		if (!model.contains("v4")) {
			rules.add(number("frequency_penalty", "frequency_penalty", 0, -2, 2, 2, "频率惩罚"));
			rules.add(number("presence_penalty", "presence_penalty", 0, -2, 2, 2, "存在惩罚"));
		}
		rules.add(booleanRule("logprobs", "logprobs", false, "是否返回 Token 对数概率"));
		rules.add(number("top_logprobs", "top_logprobs", 0, 0, 20, 0, "返回概率数量"));
		return rules;
	}

	private List<ParameterRule> doubaoRules(String modelId) {
		String model = lower(modelId);
		List<ParameterRule> rules = new ArrayList<>();
		rules.add(number("temperature", "temperature", 1.0, 0, 2, 2, "采样温度"));
		rules.add(number("top_p", "top_p", 0.95, 0, 1, 2, "核采样概率"));
		rules.add(number("max_tokens", "max_tokens", 4096, 1, 131072, 0, "最大回答 Token 数"));
		rules.add(stringRule("thinking", "thinking", "enabled",
				List.<Object>of("enabled", "disabled", "auto"), "深度思考模式"));
		rules.add(stringRule("reasoning_effort", "reasoning_effort", "high",
				List.<Object>of("none", "minimal", "low", "medium", "high", "xhigh", "max"), "思考强度"));

		if (!model.contains("doubao-seed-2.0") && !model.contains("doubao-seed-2-")) {
			rules.add(number("frequency_penalty", "frequency_penalty", 0, -2, 2, 2, "频率惩罚"));
			rules.add(number("presence_penalty", "presence_penalty", 0, -2, 2, 2, "存在惩罚"));
		}
		return rules;
	}

	private ParameterRule number(String code, String name, Object defaultValue, Integer min, Integer max,
			int precision, String description) {
		return new ParameterRule().setCode(code).setName(name).setType(ParameterTypeEnum.NUMBER.getCode())
				.setDefaultValue(defaultValue).setMin(min).setMax(max).setPrecision(precision).setRequired(false)
				.setDescription(description);
	}

	private ParameterRule booleanRule(String code, String name, boolean defaultValue, String description) {
		return new ParameterRule().setCode(code).setName(name).setType(ParameterTypeEnum.BOOLEAN.getCode())
				.setDefaultValue(defaultValue).setRequired(false).setDescription(description);
	}

	private ParameterRule stringRule(String code, String name, String defaultValue, List<Object> options,
			String description) {
		return new ParameterRule().setCode(code).setName(name).setType(ParameterTypeEnum.STRING.getCode())
				.setDefaultValue(defaultValue).setOptions(options).setRequired(false).setDescription(description);
	}

	private ModelEntity findModel(String provider, String modelId) {
		if (StringUtils.isAnyBlank(provider, modelId)) {
			return null;
		}
		QueryWrapper<ModelEntity> wrapper = new QueryWrapper<>();
		wrapper.eq("provider", provider).eq("model_id", modelId);
		var context = RequestContextHolder.getRequestContext();
		if (context != null && StringUtils.isNotBlank(context.getWorkspaceId())) {
			wrapper.eq("workspace_id", context.getWorkspaceId());
		}
		return modelMapper.selectOne(wrapper);
	}

	private Set<String> loadCatalogTags(String provider, String modelId, String protocol) {
		try {
			ProviderConfigInfo detail = providerManager.getProviderDetail(provider, false);
			ModelCredential credential = detail == null ? null : detail.getCredential();
			if (credential == null || StringUtils.isBlank(credential.getApiKey())) {
				return Set.of();
			}
			for (Map<String, Object> model : modelFactory.listProviderModels(protocol, credential)) {
				if (modelId.equals(String.valueOf(model.get("model_id")))) {
					Object value = model.get("tags");
					if (value instanceof Collection<?> collection) {
						Set<String> tags = new LinkedHashSet<>();
						for (Object tag : collection) {
							if (tag != null) {
								tags.add(tag.toString().toLowerCase(Locale.ROOT));
							}
						}
						return tags;
					}
				}
			}
		}
		catch (Exception e) {
			log.debug("查询模型官方元数据失败，使用本地参数规则: provider={}, model={}", provider, modelId, e);
		}
		return Set.of();
	}

	private String lower(String value) {
		return StringUtils.defaultString(value).toLowerCase(Locale.ROOT);
	}

}
