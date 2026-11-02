package com.alibaba.cloud.ai.studio.core.model.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ModelParameterValues {

    private ModelParameterValues() {
    }

    static Number number(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value instanceof Number number) return number;
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.valueOf(text);
            }
            catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item));
                }
            }
        }
        else if (value instanceof String text && !text.isBlank()) {
            for (String item : text.split("[,\\n]")) {
                if (!item.isBlank()) result.add(item.trim());
            }
        }
        return result;
    }

    static Map<String, Object> extraParameters(Map<String, Object> parameters, Set<String> standardKeys) {
        Map<String, Object> result = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            if (value != null && !key.startsWith("__") && !standardKeys.contains(key)) {
                result.put(key, value);
            }
        });
        return result;
    }

    static Map<String, Object> safeParameters(Map<String, Object> parameters) {
        return parameters == null ? Map.of() : parameters;
    }
}
