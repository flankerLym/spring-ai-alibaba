/*
 * Copyright 2025-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.alibaba.cloud.ai.studio.core.model.llm;

import com.alibaba.cloud.ai.studio.core.model.llm.domain.ParameterRule;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.*;

/** Protocol field inventory, not a claim that every model accepts every field. */
final class OfficialModelParameters {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Map<String, Object>> CATALOG = load();
    private OfficialModelParameters() { }

    private static Map<String, Map<String, Object>> load() {
        try (InputStream input = OfficialModelParameters.class.getResourceAsStream("/model/official-parameters.json")) {
            return JSON.readValue(Objects.requireNonNull(input), new TypeReference<>() { });
        }
        catch (Exception error) { throw new IllegalStateException("Cannot load official parameter inventory", error); }
    }

    static String reference(String protocol) {
        return String.valueOf(CATALOG.getOrDefault(protocol, CATALOG.get("openai")).get("reference"));
    }

    static void supplement(String protocol, List<ParameterRule> rules) {
        Set<String> codes = new HashSet<>();
        rules.forEach(rule -> codes.add(rule.getCode()));
        Object definitions = CATALOG.getOrDefault(protocol, CATALOG.get("openai")).get("rules");
        for (Object definition : (List<?>) definitions) {
            ParameterRule rule = JSON.convertValue(definition, ParameterRule.class);
            if (codes.add(rule.getCode())) rules.add(rule);
            // Structured fields must retain their nested JSON rather than a string enum.
            else if (Set.of("response_format", "thinking", "stop").contains(rule.getCode())) {
                rules.replaceAll(existing -> existing.getCode().equals(rule.getCode()) ? rule : existing);
            }
        }
        // Limits not provided by a model must not be invented from a generic fallback.
        rules.forEach(rule -> {
            if (Set.of("max_tokens", "seed", "top_k").contains(rule.getCode())) rule.setMax(null);
        });
    }

    static List<ParameterRule> remoteRules(Map<String, Object> remote) {
        Object source = null;
        for (String key : List.of("parameter_rules", "parameters", "supported_parameters")) {
            if (remote.get(key) instanceof Collection<?> || remote.get(key) instanceof Map<?, ?>) {
                source = remote.get(key); break;
            }
        }
        List<ParameterRule> result = new ArrayList<>();
        if (source instanceof Map<?, ?> map) {
            Object properties = map.get("properties");
            Map<?, ?> definitions = properties instanceof Map<?, ?> props ? props : map;
            Collection<?> required = map.get("required") instanceof Collection<?> values ? values : List.of();
            definitions.forEach((key, value) -> {
                if (value instanceof Map<?, ?> definition)
                    result.add(rule(String.valueOf(key), definition, required.contains(key)));
            });
        }
        else if (source instanceof Collection<?> values) {
            for (Object item : values) {
                if (item instanceof String key) result.add(rule(key, Map.of(), false));
                else if (item instanceof Map<?, ?> definition) {
                    String key = String.valueOf(first(definition, "code", "name", "key", "parameter"));
                    if (!"null".equals(key)) result.add(rule(key, definition, Boolean.TRUE.equals(definition.get("required"))));
                }
            }
        }
        result.removeIf(rule -> rule.getCode().startsWith("__"));
        return result;
    }

    private static ParameterRule rule(String key, Map<?, ?> raw, boolean required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        raw.forEach((k, v) -> schema.put(String.valueOf(k), v));
        String type = String.valueOf(first(raw, "type", "value_type")).toLowerCase(Locale.ROOT);
        String mapped = switch (type) {
            case "bool", "boolean" -> "Boolean";
            case "int", "integer", "float", "double", "number", "numeric" -> "Number";
            case "array", "list", "string_list" -> {
                Object items = raw.get("items");
                String itemType = items instanceof Map<?, ?> m ? String.valueOf(m.get("type")) : "string";
                yield switch (itemType) { case "number", "integer" -> "Array<Number>";
                    case "boolean" -> "Array<Boolean>"; case "string" -> "Array<String>"; default -> "Array<Object>"; };
            }
            case "object", "dict", "map" -> "Object";
            default -> "String";
        };
        ParameterRule rule = new ParameterRule().setCode(key).setName(key).setType(mapped).setRequired(required)
                .setSchema(schema).setDefaultValue(first(raw, "default_value", "defaultValue", "default"));
        Object label = first(raw, "label", "display_name");
        if (label != null) rule.setName(String.valueOf(label));
        Object description = first(raw, "description", "help", "desc");
        if (description != null) rule.setDescription(String.valueOf(description));
        Object min = first(raw, "min", "minimum"), max = first(raw, "max", "maximum");
        if (min instanceof Number n) rule.setMin(n);
        if (max instanceof Number n) rule.setMax(n);
        if (first(raw, "precision", "decimal_places") instanceof Number n) rule.setPrecision(n.intValue());
        else if ("Number".equals(mapped)) rule.setPrecision("integer".equals(type) || "int".equals(type) ? 0 : 4);
        if (first(raw, "options", "choices", "enum") instanceof Collection<?> values)
            rule.setOptions(new ArrayList<>(values));
        return rule;
    }

    private static Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) if (map.get(key) != null) return map.get(key);
        return null;
    }
}
