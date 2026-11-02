/*
 * Model parameter schema and provider metadata are stored in the existing
 * PostgreSQL model.parameter_rules JSONB column. No schema migration required.
 */
package com.alibaba.cloud.ai.studio.core.model.llm;

import com.alibaba.cloud.ai.studio.core.base.entity.ModelEntity;
import com.alibaba.cloud.ai.studio.core.base.manager.ProviderManager;
import com.alibaba.cloud.ai.studio.core.base.mapper.ModelMapper;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ModelCredential;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ParameterRule;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ProviderConfigInfo;
import com.alibaba.cloud.ai.studio.runtime.enums.ParameterTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class ModelParameterRuleService {

    public static final String META_KEY = "__model_metadata__";

    @Resource private ModelMapper modelMapper;
    @Resource private ProviderManager providerManager;

    /** Called after model registration and lazily for models registered before this feature. */
    public List<ParameterRule> getOrCreate(String provider, String modelId) {
        ModelEntity entity = findModel(provider, modelId);
        if (entity == null) return List.of();
        List<ParameterRule> rules = entity.getParameterRules();
        if (hasMetadata(rules)) return rules;

        synchronized (this) {
            entity = findModel(provider, modelId);
            if (entity == null) return List.of();
            rules = entity.getParameterRules();
            if (hasMetadata(rules)) return rules;

            List<ParameterRule> previousRules = rules == null ? List.of() : new ArrayList<>(rules);
            rules = resolve(provider, modelId, entity.getType(), entity.getTags());
            preserveEndpointId(previousRules, rules);
            entity.setParameterRules(rules);
            refreshTagsFromMetadata(entity, rules);
            modelMapper.updateById(entity);
            return rules;
        }
    }

    /** Invoked by ModelParameterRuleAspect after add/update/enable. */
    public void ensure(com.alibaba.cloud.ai.studio.core.model.llm.domain.ModelConfigInfo model) {
        if (model == null
                || (Boolean.FALSE.equals(model.getEnable()) && model.getEndpointId() == null)
                || !"llm".equalsIgnoreCase(model.getType())
                || StringUtils.isAnyBlank(model.getProvider(), model.getModelId())) return;

        getOrCreate(model.getProvider(), model.getModelId());
        if (model.getEndpointId() != null) {
            persistEndpointId(model.getProvider(), model.getModelId(), model.getEndpointId());
        }
    }

    private void persistEndpointId(String provider, String modelId, String endpointId) {
        ModelEntity entity = findModel(provider, modelId);
        if (entity == null || entity.getParameterRules() == null) return;
        for (ParameterRule rule : entity.getParameterRules()) {
            if (!META_KEY.equals(rule.getCode()) || !(rule.getDefaultValue() instanceof Map<?, ?> stored)) continue;
            Map<String, Object> metadata = copyMap(stored);
            if (StringUtils.isBlank(endpointId)) metadata.remove("endpoint_id");
            else metadata.put("endpoint_id", endpointId.trim());
            rule.setDefaultValue(metadata);
            modelMapper.updateById(entity);
            return;
        }
    }

    public List<ParameterRule> resolve(String provider, String modelId, String modelType) {
        return resolve(provider, modelId, modelType, null);
    }

    private List<ParameterRule> resolve(String provider, String modelId, String modelType, String tags) {
        if (!"llm".equalsIgnoreCase(modelType)) return new ArrayList<>();

        ProviderConfigInfo detail = providerManager.getProviderDetail(provider, false);
        String protocol = ModelProviderConfig.normalize(detail == null ? null : detail.getProtocol());
        ModelCredential credential = detail == null ? null : detail.getCredential();
        Map<String, Object> remote = loadRemoteMetadata(protocol, credential, modelId);

        List<ParameterRule> rules = remoteParameterRules(remote);
        String parameterSource;
        if (!rules.isEmpty()) {
            parameterSource = "provider_model_schema";
        }
        else {
            rules = protocolDefaults(protocol, tags);
            parameterSource = "provider_protocol_defaults";
        }

        // These are the parameters that the corresponding runtime adapter can carry.
        // DashScope uses its own native options object, so only expose parameters
        // explicitly supported by that adapter. OpenAI-compatible transports can
        // forward model-specific fields through extra_body.
        if ("dashscope".equals(protocol)) {
            Set<String> supported = Set.of("temperature", "top_p", "max_tokens", "top_k", "seed",
                    "repetition_penalty", "enable_thinking", "enable_search");
            rules.removeIf(rule -> !supported.contains(rule.getCode()));
        }

        Object info = remote.get("model_info");
        if (info instanceof Map<?, ?> bounds && bounds.get("max_output_tokens") instanceof Number max) {
            for (ParameterRule rule : rules) {
                if ("max_tokens".equals(rule.getCode()) && max.longValue() > 0
                        && max.longValue() <= Integer.MAX_VALUE) rule.setMax(max.intValue());
            }
        }

        String endpoint = endpointType(protocol, remote);
        String routeSource = remote.isEmpty() ? "unverified" : "provider_catalog_modality";
        if ("dashscope".equals(protocol) && "unknown".equals(endpoint)) {
            String probed = probeDashScopeEndpoint(credential, modelId);
            if (!"unknown".equals(probed)) {
                endpoint = probed;
                routeSource = "provider_probe_verified";
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schema_version", 2);
        metadata.put("provider", provider);
        metadata.put("model_id", modelId);
        metadata.put("protocol", protocol);
        metadata.put("endpoint_type", endpoint);
        metadata.put("route_source", routeSource);
        metadata.put("parameter_source", parameterSource);
        metadata.put("catalog_status", remote.isEmpty() ? "unavailable" : "found");
        metadata.put("captured_at", Instant.now().toString());
        metadata.put("raw_metadata", remote);

        List<ParameterRule> result = new ArrayList<>(rules);
        result.removeIf(rule -> META_KEY.equals(rule.getCode()));
        result.add(new ParameterRule().setCode(META_KEY).setName("Model protocol snapshot")
                .setDescription("Internal workflow execution metadata; not a model argument")
                .setType(ParameterTypeEnum.STRING.getCode()).setDefaultValue(metadata).setRequired(false));
        return result;
    }

    private List<ParameterRule> protocolDefaults(String protocol, String tags) {
        List<ParameterRule> rules = new ArrayList<>();
        switch (protocol) {
            case "dashscope" -> {
                number(rules, "temperature", 0.7, 0, 2, 2, "控制生成随机性");
                number(rules, "top_p", 0.8, 0, 1, 2, "核采样阈值");
                number(rules, "top_k", 1, 1, 100, 0, "限制候选 token 数量");
                number(rules, "max_tokens", 512, 1, 8192, 0, "最大生成 token 数");
                number(rules, "seed", 1234, 0, 2147483647, 0, "随机种子");
                number(rules, "repetition_penalty", 1, 0, 2, 2, "重复内容惩罚");
                bool(rules, "enable_thinking", false, "是否启用思考模式");
                bool(rules, "enable_search", false, "是否启用联网搜索");
            }
            case "doubao" -> {
                number(rules, "temperature", 0.7, 0, 2, 2, "控制生成随机性");
                number(rules, "top_p", 0.9, 0, 1, 2, "核采样阈值");
                number(rules, "max_tokens", 4096, 1, 32768, 0, "最大生成 token 数");
                number(rules, "presence_penalty", 0, -2, 2, 2, "话题新鲜度惩罚");
                number(rules, "frequency_penalty", 0, -2, 2, 2, "频率惩罚");
                number(rules, "repetition_penalty", 1, 0, 2, 2, "重复内容惩罚");
                choice(rules, "thinking", "思考模式", "disabled",
                        List.of("enabled", "disabled"), "思考模式开关");
                choice(rules, "reasoning_effort", "思考强度", "medium",
                        List.of("low", "medium", "high"), "推理强度");
                choice(rules, "response_format", "响应格式", "text",
                        List.of("text", "json_object"), "指定输出格式");
                stringList(rules, "stop", "停止生成标记，多个值用逗号分隔");
            }
            case "deepseek" -> {
                number(rules, "temperature", 1, 0, 2, 2, "控制生成随机性");
                number(rules, "top_p", 1, 0, 1, 2, "核采样阈值");
                number(rules, "max_tokens", 4096, 1, 8192, 0, "最大生成 token 数");
                number(rules, "presence_penalty", 0, -2, 2, 2, "话题新鲜度惩罚");
                number(rules, "frequency_penalty", 0, -2, 2, 2, "频率惩罚");
                number(rules, "seed", 1234, 0, 2147483647, 0, "随机种子");
                choice(rules, "response_format", "响应格式", "text",
                        List.of("text", "json_object"), "指定输出格式");
                stringList(rules, "stop", "停止生成标记，多个值用逗号分隔");
            }
            default -> {
                number(rules, "temperature", 1, 0, 2, 2, "控制生成随机性");
                number(rules, "top_p", 1, 0, 1, 2, "核采样阈值");
                number(rules, "max_tokens", 512, 1, 32768, 0, "最大生成 token 数");
                number(rules, "presence_penalty", 0, -2, 2, 2, "话题新鲜度惩罚");
                number(rules, "frequency_penalty", 0, -2, 2, 2, "频率惩罚");
                number(rules, "seed", 1234, 0, 2147483647, 0, "随机种子");
                choice(rules, "response_format", "响应格式", "text",
                        List.of("text", "json_object"), "指定输出格式");
                stringList(rules, "stop", "停止生成标记，多个值用逗号分隔");
            }
        }
        return rules;
    }

    private void number(List<ParameterRule> rules, String code, Number value, int min, int max,
                        int precision, String description) {
        rules.add(new ParameterRule().setCode(code).setName(code).setDescription(description)
                .setType("Number").setDefaultValue(value).setMin(min).setMax(max)
                .setPrecision(precision).setRequired(false));
    }

    private void bool(List<ParameterRule> rules, String code, boolean value, String description) {
        rules.add(new ParameterRule().setCode(code).setName(code).setDescription(description)
                .setType("Boolean").setDefaultValue(value).setRequired(false));
    }

    private void choice(List<ParameterRule> rules, String code, String name, String value,
                        List<Object> options, String description) {
        rules.add(new ParameterRule().setCode(code).setName(name).setDescription(description)
                .setType("String").setDefaultValue(value).setOptions(options).setRequired(false));
    }

    private void stringList(List<ParameterRule> rules, String code, String description) {
        rules.add(new ParameterRule().setCode(code).setName(code).setDescription(description)
                .setType("Array<String>").setDefaultValue(List.of()).setRequired(false));
    }

    private List<ParameterRule> remoteParameterRules(Map<String, Object> remote) {
        Object source = remote.get("parameter_rules");
        if (!(source instanceof Collection<?>)) source = remote.get("parameters");
        if (!(source instanceof Collection<?>)) source = remote.get("supported_parameters");
        if (!(source instanceof Collection<?> list)) return new ArrayList<>();

        List<ParameterRule> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String key = firstString(m, "code", "name", "key", "parameter");
            if (key.isBlank() || key.startsWith("__")) continue;

            Object rawOptions = firstValue(m, "options", "choices", "enum");
            List<Object> options = new ArrayList<>();
            if (rawOptions instanceof Collection<?> values) options.addAll(values);

            String rawType = firstString(m, "type", "value_type").toLowerCase(Locale.ROOT);
            String type;
            if (!options.isEmpty()) type = "String";
            else type = switch (rawType) {
                case "bool", "boolean" -> "Boolean";
                case "int", "integer", "float", "double", "number", "numeric" -> "Number";
                case "array", "list", "string_list" -> "Array<String>";
                case "object", "dict", "map" -> "Object";
                default -> "String";
            };

            Object defaultValue = firstValue(m, "default_value", "defaultValue", "default");
            ParameterRule rule = new ParameterRule().setCode(key)
                    .setName(firstString(m, "label", "display_name", "name").isBlank()
                            ? key : firstString(m, "label", "display_name", "name"))
                    .setType(type)
                    .setRequired(Boolean.TRUE.equals(firstValue(m, "required", "is_required")))
                    .setDescription(firstString(m, "description", "help", "desc"))
                    .setDefaultValue(defaultValue);
            if (!options.isEmpty()) rule.setOptions(options);
            if (firstValue(m, "min", "minimum") instanceof Number n) rule.setMin(n.intValue());
            if (firstValue(m, "max", "maximum") instanceof Number n) rule.setMax(n.intValue());
            if (firstValue(m, "precision", "decimal_places") instanceof Number n) rule.setPrecision(n.intValue());
            result.add(rule);
        }
        return result;
    }

    private String endpointType(String protocol, Map<String, Object> remote) {
        if (!"dashscope".equals(protocol)) return "chat-completions";
        if (remote.isEmpty()) return "unknown";
        Object inference = remote.get("inference_metadata");
        Map<?, ?> metadata = inference instanceof Map<?, ?> value ? value : Map.of();
        Object modalities = metadata.get("request_modality");
        if (modalities == null) modalities = remote.get("input_modalities");
        if (modalities == null) return "unknown";
        Set<String> detected = new LinkedHashSet<>();
        if (modalities instanceof Collection<?> list) {
            for (Object item : list) detected.add(String.valueOf(item).toLowerCase(Locale.ROOT));
        } else detected.add(String.valueOf(modalities).toLowerCase(Locale.ROOT));
        String joined = String.join(",", detected);
        if (joined.contains("image") || joined.contains("video") || joined.contains("audio"))
            return "multimodal-generation";
        if (joined.contains("text")) return "text-generation";
        return "unknown";
    }

    private Map<String, Object> loadRemoteMetadata(String protocol, ModelCredential credential, String modelId) {
        if (credential == null || StringUtils.isAnyBlank(credential.getApiKey(), credential.getEndpoint()))
            return Map.of();
        if ("doubao".equals(protocol)) return Map.of(); // Ark has no generic authenticated model catalog.
        try {
            String endpoint = credential.getEndpoint().trim().replaceAll("/+$", "")
                    .replaceFirst("/chat/completions$", "");
            String path;
            if ("dashscope".equals(protocol)) {
                endpoint = endpoint.replaceFirst("/compatible-mode(?:/v1)?$", "")
                        .replaceFirst("/api/v1$", "");
                path = "/api/v1/models";
            } else {
                if (!endpoint.endsWith("/v1")) endpoint += "/v1";
                path = "/models";
            }

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(3));
            factory.setReadTimeout(Duration.ofSeconds(8));
            RestClient client = RestClient.builder().requestFactory(factory).build();
            String url = endpoint + path + ("dashscope".equals(protocol)
                    ? "?model=" + URLEncoder.encode(modelId, StandardCharsets.UTF_8) + "&page_no=1&page_size=20" : "");
            Map<?, ?> response = client.get().uri(URI.create(url))
                    .header("Authorization", "Bearer " + credential.getApiKey())
                    .retrieve().body(Map.class);
            for (Object record : records(response)) {
                if (!(record instanceof Map<?, ?> m)) continue;
                String id = firstString(m, "model", "id", "model_id", "model_name");
                if (modelId.equals(id)) return copyMap(m);
            }
        } catch (Exception e) {
            log.warn("Remote model metadata unavailable: providerProtocol={}, model={}: {}",
                    protocol, modelId, e.getMessage());
        }
        return Map.of();
    }

    private String probeDashScopeEndpoint(ModelCredential credential, String modelId) {
        if (credential == null || StringUtils.isAnyBlank(credential.getEndpoint(), credential.getApiKey()))
            return "unknown";
        String root = credential.getEndpoint().trim().replaceAll("/+$", "")
                .replaceFirst("/chat/completions$", "")
                .replaceFirst("/compatible-mode(?:/v1)?$", "")
                .replaceFirst("/api/v1$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(8));
        RestClient client = RestClient.builder().requestFactory(factory).build();
        for (String route : List.of("text-generation", "multimodal-generation")) {
            Object content = "text-generation".equals(route) ? "ping" : List.of(Map.of("text", "ping"));
            Map<String, Object> payload = Map.of("model", modelId,
                    "input", Map.of("messages", List.of(Map.of("role", "user", "content", content))),
                    "parameters", Map.of("max_tokens", 1, "result_format", "message"));
            try {
                client.post().uri(URI.create(root + "/api/v1/services/aigc/" + route + "/generation"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + credential.getApiKey())
                        .body(payload).retrieve().toBodilessEntity();
                return route;
            } catch (Exception e) {
                log.info("DashScope endpoint probe failed: route={}, model={}, reason={}",
                        route, modelId, e.getMessage());
            }
        }
        return "unknown";
    }

    private List<?> records(Object response) {
        if (!(response instanceof Map<?, ?> map)) return List.of();
        for (String field : List.of("models", "data")) {
            Object list = map.get(field);
            if (list instanceof List<?> values) return values;
            if (list instanceof Map<?, ?> nested) {
                List<?> found = records(nested);
                if (!found.isEmpty()) return found;
            }
        }
        Object output = map.get("output");
        return output == null ? Collections.emptyList() : records(output);
    }

    private boolean hasMetadata(List<ParameterRule> rules) {
        if (rules == null) return false;
        return rules.stream().anyMatch(rule -> {
            if (!META_KEY.equals(rule.getCode()) || !(rule.getDefaultValue() instanceof Map<?, ?> metadata)) {
                return false;
            }
            Object version = metadata.get("schema_version");
            return version instanceof Number number && number.intValue() >= 2;
        });
    }

    private void preserveEndpointId(List<ParameterRule> previousRules, List<ParameterRule> nextRules) {
        if (previousRules == null || nextRules == null) return;
        Object oldValue = previousRules.stream().filter(rule -> META_KEY.equals(rule.getCode()))
                .map(ParameterRule::getDefaultValue).filter(Map.class::isInstance).findFirst().orElse(null);
        if (!(oldValue instanceof Map<?, ?> oldMetadata) || oldMetadata.get("endpoint_id") == null) return;
        for (ParameterRule rule : nextRules) {
            if (!META_KEY.equals(rule.getCode()) || !(rule.getDefaultValue() instanceof Map<?, ?> newMetadata)) continue;
            Map<String, Object> metadata = copyMap(newMetadata);
            metadata.putIfAbsent("endpoint_id", oldMetadata.get("endpoint_id"));
            rule.setDefaultValue(metadata);
            return;
        }
    }

    private void refreshTagsFromMetadata(ModelEntity entity, List<ParameterRule> rules) {
        Object metadataValue = rules.stream().filter(r -> META_KEY.equals(r.getCode()))
                .map(ParameterRule::getDefaultValue).findFirst().orElse(null);
        if (!(metadataValue instanceof Map<?, ?> metadata)) return;
        Object raw = metadata.get("raw_metadata");
        if (!(raw instanceof Map<?, ?> remote) || remote.isEmpty()) return;

        Set<String> tags = new LinkedHashSet<>();
        Object features = remote.get("features");
        if (features instanceof Collection<?> values) {
            for (Object feature : values) {
                String value = String.valueOf(feature).toLowerCase(Locale.ROOT);
                if (value.contains("function") || value.contains("tool")) tags.add("function_call");
                if (value.contains("search")) tags.add("web_search");
                if (value.contains("reasoning")) tags.add("reasoning");
            }
        }
        Object inference = remote.get("inference_metadata");
        if (inference instanceof Map<?, ?> config && config.get("request_modality") instanceof Collection<?> values) {
            for (Object modality : values) {
                if ("image".equalsIgnoreCase(String.valueOf(modality))
                        || "video".equalsIgnoreCase(String.valueOf(modality))) tags.add("vision");
            }
        }
        if (!tags.isEmpty()) entity.setTags(String.join(",", tags));
    }

    private ModelEntity findModel(String provider, String modelId) {
        if (StringUtils.isAnyBlank(provider, modelId)) return null;
        QueryWrapper<ModelEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("provider", provider).eq("model_id", modelId);
        var context = RequestContextHolder.getRequestContext();
        if (context != null && StringUtils.isNotBlank(context.getWorkspaceId()))
            wrapper.eq("workspace_id", context.getWorkspaceId());
        return modelMapper.selectOne(wrapper);
    }

    private static String firstString(Map<?, ?> map, String... keys) {
        Object value = firstValue(map, keys);
        return value == null ? "" : String.valueOf(value);
    }

    private static Object firstValue(Map<?, ?> map, String... keys) {
        for (String key : keys) if (map.containsKey(key) && map.get(key) != null) return map.get(key);
        return null;
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
