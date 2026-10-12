/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.studio.core.workflow;

import com.alibaba.cloud.ai.studio.core.utils.common.VariableUtils;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.CommonParam;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Input constraints come from the workflow definition, never from the caller's type. */
public final class WorkflowInputValidator {

    private WorkflowInputValidator() {
    }

    public static Object readUserInput(WorkflowConfig config, String key, String callerType, Object value) {
        if (config != null && config.getNodes() != null) {
            for (Node node : config.getNodes()) {
                if (NodeTypeEnum.START.getCode().equals(node.getType()) && node.getConfig() != null
                        && node.getConfig().getOutputParams() != null) {
                    for (CommonParam param : node.getConfig().getOutputParams()) {
                        if (param != null && key.equals(param.getKey())) {
                            // Keep the original value until validation against the published definition.
                            return value;
                        }
                    }
                }
            }
        }
        return VariableUtils.convertValueByType(key, callerType, value);
    }

    public static void validateStart(WorkflowContext context) {
        if (context.getWorkflowConfig() == null || context.getWorkflowConfig().getNodes() == null) {
            return;
        }
        for (Node node : context.getWorkflowConfig().getNodes()) {
            if (NodeTypeEnum.START.getCode().equals(node.getType()) && node.getConfig() != null) {
                Map<String, Object> resolved = resolve(node.getConfig().getOutputParams(), context.getUserMap());
                context.getUserMap().clear();
                context.getUserMap().putAll(resolved);
                return;
            }
        }
    }

    public static Map<String, Object> resolve(List<? extends CommonParam> params, Map<String, Object> supplied) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (supplied != null) {
            result.putAll(supplied);
        }
        if (params == null) {
            return result;
        }
        for (CommonParam param : params) {
            if (param == null || param.getKey() == null || param.getKey().isBlank()) {
                continue;
            }
            String key = param.getKey();
            boolean present = result.containsKey(key);
            Object value = result.get(key);
            if (Boolean.TRUE.equals(param.getRequired())
                    && (!present || value == null || value instanceof String text && text.isBlank())) {
                throw new BizException(ErrorCode.MISSING_PARAMS.toError(key));
            }
            // Explicit null and empty strings are supplied values; defaults only fill omitted keys.
            if (!present && param.getDefaultValue() != null) {
                value = param.getDefaultValue();
                present = true;
            }
            if (present) {
                result.put(key, convert(key, param.getType(), value));
            }
        }
        return result;
    }

    private static Object convert(String key, String type, Object value) {
        if ("String".equals(type) && value != null && !(value instanceof String)) {
            throw new BizException(ErrorCode.INVALID_PARAMS.toError(key, "expected type is String"));
        }
        Object converted = "File".equals(type) && value instanceof Map
                ? com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils.fromMap((Map<String, Object>) value,
                        com.alibaba.cloud.ai.studio.runtime.domain.file.File.class)
                : VariableUtils.convertValueByType(key, type, value);
        // VariableUtils accepts any List for arrays. Check each element against the declared type.
        if (converted instanceof List<?> list && type != null && type.startsWith("Array<")) {
            String elementType = type.substring(6, type.length() - 1);
            for (Object element : list) {
                boolean valid = element == null || switch (elementType) {
                    case "String" -> element instanceof String;
                    case "Number" -> element instanceof Number;
                    case "Boolean" -> element instanceof Boolean;
                    case "Object" -> element instanceof Map;
                    case "File" -> element instanceof Map
                            || element instanceof com.alibaba.cloud.ai.studio.runtime.domain.file.File;
                    default -> true;
                };
                if (!valid) {
                    throw new BizException(ErrorCode.INVALID_PARAMS.toError(key, "expected type is " + type));
                }
            }
        }
        return converted;
    }
}
