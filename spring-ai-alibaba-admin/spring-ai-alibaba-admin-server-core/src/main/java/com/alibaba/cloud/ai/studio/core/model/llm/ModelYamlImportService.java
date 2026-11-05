/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.studio.core.model.llm;

import com.alibaba.cloud.ai.studio.core.base.entity.ModelEntity;
import com.alibaba.cloud.ai.studio.core.base.manager.ProviderManager;
import com.alibaba.cloud.ai.studio.core.base.mapper.ModelMapper;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ParameterRule;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class ModelYamlImportService {
    public static final String SOURCE = "uploaded_yaml";
    @Resource private ModelMapper modelMapper;
    @Resource private ProviderManager providerManager;

    /** One transaction per batch. Validate the whole batch before performing any writes. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importModels(String provider, List<ModelYamlParser.Source> files, boolean overwrite, boolean dryRun) {
        var context = RequestContextHolder.getRequestContext();
        if (context == null || context.getWorkspaceId() == null || context.getWorkspaceId().isBlank())
            throw new IllegalArgumentException("缺少工作空间上下文");
        var detail = providerManager.getProviderDetail(provider, false);
        String protocol = ModelProviderConfig.normalize(detail.getProtocol());
        if (!Set.of("dashscope", "deepseek").contains(protocol))
            throw new IllegalArgumentException("仅支持通义千问 DashScope 和 DeepSeek 供应商");
        ModelYamlParser.Parsed parsed = ModelYamlParser.parse(files);
        Map<String, ModelEntity> existing = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var definition : parsed.models()) {
            ModelEntity entity = modelMapper.selectOne(new QueryWrapper<ModelEntity>().eq("workspace_id", context.getWorkspaceId())
                    .eq("provider", provider).eq("model_id", definition.modelId()));
            if (entity != null && !overwrite) throw new IllegalArgumentException("模型已存在，请开启覆盖参数选项: " + definition.modelId());
            if (entity != null && !"llm".equals(entity.getType())) throw new IllegalArgumentException("已有同名模型不是 LLM: " + definition.modelId());
            existing.put(definition.modelId(), entity);
            rows.add(Map.of("model_id", definition.modelId(), "file", definition.filename(), "action", entity == null ? "create" : "update",
                    "parameter_count", definition.rules().size(), "parameters", definition.rules()));
        }
        if (!dryRun) for (var definition : parsed.models()) {
            ModelEntity entity = existing.get(definition.modelId());
            boolean create = entity == null;
            if (create) {
                entity = new ModelEntity();
                entity.setWorkspaceId(context.getWorkspaceId()); entity.setProvider(provider); entity.setModelId(definition.modelId());
                entity.setType("llm"); entity.setSource("custom"); entity.setGmtCreate(new Date());
                entity.setEnable(!Boolean.TRUE.equals(definition.document().get("deprecated")));
                entity.setName(modelName(definition));
                entity.setMode("chat");
                entity.setTags(String.join(",", tags(definition.document())));
            }
            if (definition.document().get("features") instanceof Collection<?>)
                entity.setTags(String.join(",", tags(definition.document())));
            if (definition.document().get("model_properties") instanceof Map<?, ?> properties
                    && Set.of("chat", "completion").contains(Objects.toString(properties.get("mode"), "")))
                entity.setMode(String.valueOf(properties.get("mode")));
            Map<String, Object> metadata = new LinkedHashMap<>(metadata(entity.getParameterRules()));
            metadata.put("schema_version", 3); metadata.put("provider", provider); metadata.put("model_id", definition.modelId());
            metadata.put("protocol", protocol); metadata.put("parameter_source", SOURCE);
            metadata.put("raw_metadata", definition.document()); metadata.put("raw_yaml", definition.yaml());
            metadata.put("import_file", definition.filename()); metadata.put("yaml_sha256", definition.sha256());
            metadata.put("captured_at", Instant.now().toString());
            if ("dashscope".equals(protocol)) {
                Object endpoint = definition.document().get("endpoint_type");
                if (endpoint == null && !Set.of("text-generation", "multimodal-generation").contains(Objects.toString(metadata.get("endpoint_type"), "")))
                    endpoint = tags(definition.document()).contains("vision") ? "multimodal-generation" : "text-generation";
                if (endpoint != null) { metadata.put("endpoint_type", endpoint); metadata.put("route_source", "yaml_definition"); }
            }
            else metadata.put("endpoint_type", "chat-completions");
            List<ParameterRule> rules = new ArrayList<>(definition.rules());
            rules.add(new ParameterRule().setCode(ModelParameterRuleService.META_KEY).setName("Model YAML snapshot")
                    .setType("Object").setDefaultValue(metadata));
            entity.setParameterRules(rules); entity.setGmtModified(new Date());
            if ((create ? modelMapper.insert(entity) : modelMapper.updateById(entity)) <= 0)
                throw new IllegalStateException("模型 YAML 写入失败，整批回滚: " + definition.modelId());
        }
        return Map.of("provider", provider, "dry_run", dryRun, "total_models", rows.size(), "details", rows,
                "ignored_files", parsed.ignoredFiles());
    }

    public static Map<String, Object> metadata(List<ParameterRule> rules) {
        if (rules != null) for (ParameterRule rule : rules) {
            if (ModelParameterRuleService.META_KEY.equals(rule.getCode()) && rule.getDefaultValue() instanceof Map<?, ?> raw)
                return ModelYamlParser.map(raw, "metadata");
        }
        return Map.of();
    }
    public static boolean isImported(List<ParameterRule> rules) { return SOURCE.equals(metadata(rules).get("parameter_source")); }

    public static void validateParameters(List<ParameterRule> rules, Map<String, Object> parameters) {
        if (!isImported(rules)) return;
        Map<String, ParameterRule> allowed = new LinkedHashMap<>();
        rules.stream().filter(rule -> !rule.getCode().startsWith("__")).forEach(rule -> allowed.put(rule.getCode(), rule));
        for (var entry : parameters.entrySet()) {
            if (entry.getKey().startsWith("__")) continue;
            ParameterRule rule = allowed.get(entry.getKey());
            if (rule == null) throw new IllegalArgumentException("参数不在当前模型 YAML 中，请重新打开 LLM 节点并保存: " + entry.getKey());
            ModelYamlParser.validate(rule, entry.getValue());
        }
        for (ParameterRule rule : allowed.values()) {
            if (!rule.isRequired() || parameters.containsKey(rule.getCode())) continue;
            if (rule.getDefaultValue() == null) throw new IllegalArgumentException("缺少 YAML 必填参数: " + rule.getCode());
            parameters.put(rule.getCode(), rule.getDefaultValue());
        }
    }
    private static List<String> tags(Map<String, Object> document) {
        Set<String> tags = new LinkedHashSet<>();
        if (document.get("features") instanceof Collection<?> features) for (Object feature : features) {
            switch (String.valueOf(feature)) {
                case "vision" -> tags.add("vision");
                case "tool-call", "multi-tool-call", "stream-tool-call" -> tags.add("function_call");
                case "agent-thought" -> tags.add("reasoning");
                case "web-search" -> tags.add("web_search");
                default -> { }
            }
        }
        return new ArrayList<>(tags);
    }
    private static String modelName(ModelYamlParser.Definition definition) {
        if (definition.document().get("label") instanceof Map<?, ?> labels) {
            Object name = labels.get("en_US"); if (name == null) name = labels.get("zh_Hans");
            if (name != null && !String.valueOf(name).isBlank()) return String.valueOf(name);
        }
        return definition.modelId();
    }
}
