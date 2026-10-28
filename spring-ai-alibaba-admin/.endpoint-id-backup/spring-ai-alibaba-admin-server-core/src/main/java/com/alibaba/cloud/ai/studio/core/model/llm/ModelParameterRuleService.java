/*
 * Model metadata snapshot in the existing PostgreSQL model.parameter_rules JSONB.
 * No schema migration and no runtime metadata lookup are required.
 */
package com.alibaba.cloud.ai.studio.core.model.llm;

import com.alibaba.cloud.ai.studio.core.base.entity.ModelEntity;
import com.alibaba.cloud.ai.studio.core.base.manager.ProviderManager;
import com.alibaba.cloud.ai.studio.core.base.mapper.ModelMapper;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
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
    @Resource private Map<String, ModelProvider> providerMap;

    /** Existing models are upgraded on the first model-configuration read, not on inference. */
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
            rules = resolve(provider, modelId, entity.getType());
            entity.setParameterRules(rules);
            refreshTagsFromMetadata(entity, rules);
            modelMapper.updateById(entity);
            return rules;
        }
    }

    /** Invoked by the existing ModelParameterRuleAspect after add/enable. */
    public void ensure(ModelConfigInfo model) {
        if (model == null || Boolean.FALSE.equals(model.getEnable())
                || !"llm".equalsIgnoreCase(model.getType())
                || StringUtils.isAnyBlank(model.getProvider(), model.getModelId())) return;
        getOrCreate(model.getProvider(), model.getModelId());
    }

    public List<ParameterRule> resolve(String provider, String modelId, String modelType) {
        if (!"llm".equalsIgnoreCase(modelType)) return defaultRules(provider, modelId, modelType);
        ProviderConfigInfo detail = providerManager.getProviderDetail(provider, false);
        String protocol = ModelProviderConfig.normalize(detail == null ? null : detail.getProtocol());
        ModelCredential credential = detail == null ? null : detail.getCredential();
        Map<String, Object> remote = loadRemoteMetadata(protocol, credential, modelId);
        List<ParameterRule> rules = remoteParameterRules(remote);
        String parameterSource = "provider_catalog";
        if (rules.isEmpty()) {
            rules = defaultRules(provider, modelId, modelType);
            parameterSource = "provider_adapter_defaults";
            // Generic provider-level options are safe defaults, not a claim that
            // any particular vendor model supports every optional setting.
        }
        // Expose only fields the currently installed native client can carry.
        // This is a client protocol constraint, NEVER a model-ID allowlist.
        Set<String> supported = switch (protocol) {
            case "dashscope" -> Set.of("temperature", "top_p", "max_tokens", "top_k", "seed",
                    "repetition_penalty", "enable_thinking", "enable_search");
            case "doubao" -> Set.of("temperature", "top_p", "max_tokens", "presence_penalty",
                    "frequency_penalty", "thinking", "reasoning_effort", "repetition_penalty", "response_format");
            case "deepseek" -> Set.of("temperature", "top_p", "max_tokens", "presence_penalty",
                    "frequency_penalty", "response_format");
            default -> null; // OpenAI-compatible custom providers may use extra_body.
        };
        if (supported != null) rules.removeIf(rule -> !supported.contains(rule.getCode()));
        // Derive the max_tokens UI bound from official catalog metadata where available.
        Object info = remote.get("model_info");
        if (info instanceof Map<?, ?> bounds && bounds.get("max_output_tokens") instanceof Number max) {
            for (ParameterRule rule : rules) {
                if ("max_tokens".equals(rule.getCode()) && max.longValue() > 0
                        && max.longValue() <= Integer.MAX_VALUE) rule.setMax(max.intValue());
            }
        }
        String selectedEndpoint = endpointType(protocol, remote);
        String selectedSource = remote.isEmpty() ? "unverified" : "provider_catalog_modality";
        if ("dashscope".equals(protocol) && "unknown".equals(selectedEndpoint)) {
            String probed = probeDashScopeEndpoint(credential, modelId);
            if (!"unknown".equals(probed)) {
                selectedEndpoint = probed;
                selectedSource = "provider_probe_verified";
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schema_version", 1);
        metadata.put("provider", provider);
        metadata.put("model_id", modelId);
        metadata.put("protocol", protocol);
        metadata.put("endpoint_type", selectedEndpoint);
        metadata.put("route_source", selectedSource);
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

    private boolean hasMetadata(List<ParameterRule> rules) {
        return rules != null && rules.stream().anyMatch(r -> META_KEY.equals(r.getCode())
                && r.getDefaultValue() instanceof Map<?, ?>);
    }

    private List<ParameterRule> defaultRules(String provider, String modelId, String modelType) {
        ModelProvider vendor = providerMap.get(provider + "Provider");
        if (vendor == null) vendor = providerMap.get("OpenAIProvider");
        if (vendor == null) return new ArrayList<>();
        List<ParameterRule> rules = vendor.getParameterRules(modelId,
                StringUtils.defaultIfBlank(modelType, "llm"));
        if (rules == null) return new ArrayList<>();
        // Provider defaults can be static shared objects: never mutate them when
        // applying an individual model's remote token limits.
        List<ParameterRule> copies = new ArrayList<>();
        for (ParameterRule rule : rules) copies.add(new ParameterRule()
                .setCode(rule.getCode()).setName(rule.getName()).setDescription(rule.getDescription())
                .setType(rule.getType()).setDefaultValue(rule.getDefaultValue())
                .setMin(rule.getMin()).setMax(rule.getMax()).setPrecision(rule.getPrecision())
                .setOptions(rule.getOptions()).setRequired(rule.isRequired()).setHelp(rule.getHelp()));
        return copies;
    }

    /** Update feature tags only from supplier-provided facts, never model-name patterns. */
    @SuppressWarnings("unchecked")
    private void refreshTagsFromMetadata(ModelEntity entity, List<ParameterRule> rules) {
        Object metaValue = rules.stream().filter(r -> META_KEY.equals(r.getCode()))
                .map(ParameterRule::getDefaultValue).findFirst().orElse(null);
        if (!(metaValue instanceof Map<?, ?> metadata)) return;
        Object raw = metadata.get("raw_metadata");
        if (!(raw instanceof Map<?, ?> remote) || remote.isEmpty()) return;
        Set<String> tags = new LinkedHashSet<>();
        Object caps = remote.get("features");
        if (caps instanceof Collection<?> features) {
            for (Object feature : features) {
                String text = String.valueOf(feature).toLowerCase(Locale.ROOT);
                if (text.contains("function") || text.contains("tool")) tags.add("function_call");
                if (text.contains("search")) tags.add("web_search");
                if (text.contains("reasoning")) tags.add("reasoning");
            }
        }
        Object inference = remote.get("inference_metadata");
        if (inference instanceof Map<?, ?> config) {
            Object modalities = config.get("request_modality");
            if (modalities instanceof Collection<?> values) {
                for (Object modality : values)
                    if ("image".equalsIgnoreCase(String.valueOf(modality)) ||
                        "video".equalsIgnoreCase(String.valueOf(modality))) tags.add("vision");
            }
        }
        entity.setTags(String.join(",", tags));
    }

    /** No name-based guesses. The native endpoint is derived only from catalog modality data. */
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
        } else {
            detected.add(String.valueOf(modalities).toLowerCase(Locale.ROOT));
        }
        String joined = String.join(",", detected);
        if (joined.contains("image") || joined.contains("video") || joined.contains("audio"))
            return "multimodal-generation";
        if (joined.contains("text")) return "text-generation";
        return "unknown";
    }

    /** Use an actual remotely supplied parameter schema when one is present. */
    private List<ParameterRule> remoteParameterRules(Map<String, Object> remote) {
        Object source = remote.get("parameter_rules");
        if (!(source instanceof Collection<?>)) source = remote.get("parameters");
        if (!(source instanceof Collection<?> list)) return new ArrayList<>();
        List<ParameterRule> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String key = string(m.get("code"));
            if (key.isBlank()) key = string(m.get("name"));
            if (key.isBlank() || key.startsWith("__")) continue;
            String type = string(m.get("type"));
            String normalized = type.equalsIgnoreCase("boolean") ? "Boolean"
                    : type.equalsIgnoreCase("string") ? "String" : "Number";
            ParameterRule rule = new ParameterRule().setCode(key).setName(key)
                    .setType(normalized).setRequired(Boolean.TRUE.equals(m.get("required")))
                    .setDescription(string(m.get("description")))
                    .setDefaultValue(m.containsKey("default_value") ? m.get("default_value") : m.get("default"));
            if (m.get("min") instanceof Number n) rule.setMin(n.intValue());
            if (m.get("max") instanceof Number n) rule.setMax(n.intValue());
            if (m.get("precision") instanceof Number n) rule.setPrecision(n.intValue());
            if (m.get("options") instanceof List<?> options) rule.setOptions(new ArrayList<>(options));
            result.add(rule);
        }
        return result;
    }

    /** Remote catalog is consulted only while creating/upgrading a model's stored rules. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadRemoteMetadata(String protocol, ModelCredential credential, String modelId) {
        if (credential == null || StringUtils.isAnyBlank(credential.getApiKey(), credential.getEndpoint()))
            return Map.of();
        if ("doubao".equals(protocol)) return Map.of(); // Ark account has no generic listing contract.
        try {
            String endpoint = credential.getEndpoint().trim().replaceAll("/+$", "")
                    .replaceFirst("/chat/completions$", "");
            String path;
            if ("dashscope".equals(protocol)) {
                endpoint = endpoint.replaceFirst("/compatible-mode(?:/v1)?$", "");
                endpoint = endpoint.replaceFirst("/api/v1$", "");
                path = "/api/v1/models";
            } else {
                if (!endpoint.endsWith("/v1")) endpoint += "/v1";
                path = "/models";
            }
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(3));
            factory.setReadTimeout(Duration.ofSeconds(8));
            RestClient client = RestClient.builder().requestFactory(factory).build();
            int pages = 1;
            for (int page = 1; page <= pages; page++) {
                String url = endpoint + path + ("dashscope".equals(protocol)
                        ? "?model=" + URLEncoder.encode(modelId, StandardCharsets.UTF_8) + "&page_no=1&page_size=20"
                        : "");
                Map<?, ?> response = client.get().uri(URI.create(url))
                        .header("Authorization", "Bearer " + credential.getApiKey())
                        .retrieve().body(Map.class);
                List<?> records = records(response);
                for (Object record : records) {
                    if (!(record instanceof Map<?, ?> m)) continue;
                    String id = string(m.get("model"));
                    if (id.isBlank()) id = string(m.get("id"));
                    if (id.isBlank()) id = string(m.get("model_id"));
                    if (modelId.equals(id)) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        m.forEach((key, value) -> copy.put(String.valueOf(key), value));
                        return copy;
                    }
                }
            }
        } catch (Exception e) {
            // Never block the installed model from being configured merely because the catalog is down.
            log.warn("Remote model metadata unavailable: providerProtocol={}, model={}: {}",
                    protocol, modelId, e.getMessage());
        }
        return Map.of();
    }

    /** One-time verification performed on model registration, never on workflow execution. */
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
                log.info("One-time model route probe did not succeed: protocol=dashscope route={} model={}: {}",
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

    private String string(Object value) { return value == null ? "" : String.valueOf(value); }

    private ModelEntity findModel(String provider, String modelId) {
        if (StringUtils.isAnyBlank(provider, modelId)) return null;
        QueryWrapper<ModelEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("provider", provider).eq("model_id", modelId);
        var context = RequestContextHolder.getRequestContext();
        if (context != null && StringUtils.isNotBlank(context.getWorkspaceId()))
            wrapper.eq("workspace_id", context.getWorkspaceId());
        return modelMapper.selectOne(wrapper);
    }
}
