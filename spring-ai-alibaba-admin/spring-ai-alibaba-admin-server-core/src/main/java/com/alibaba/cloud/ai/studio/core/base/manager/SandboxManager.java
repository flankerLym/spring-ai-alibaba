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
package com.alibaba.cloud.ai.studio.core.base.manager;

import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.studio.core.base.manager.AppComponentManager.OUTPUT_DECORATE_PARAM_KEY;

/**
 * Sandbox manager for secure script execution. Supports Python and JavaScript script
 * types. Each execution uses an independent GraalVM Polyglot Context.
 */
@Slf4j
@Component
public class SandboxManager {

    private static final String JS_INPUT_BINDING_NAME = "__SAA_WORKFLOW_INPUT_JSON__";

    /**
     * Executes JavaScript with an isolated GraalVM JavaScript context.
     *
     * Input values are transferred as JSON and materialized as native JavaScript objects.
     * This avoids exposing Java Map/List instances to JavaScript as host objects and keeps
     * the existing workflow conventions such as params.foo and direct input variables.
     *
     * @param scriptContent JavaScript source
     * @param localVariableMap workflow variables
     * @param requestId request ID
     * @return normalized execution result
     */
    public Result<String> executeScript(String scriptContent, Map<String, Object> localVariableMap, String requestId) {
        if (StringUtils.isBlank(scriptContent)) {
            log.error("JavaScript content cannot be empty");
            return Result.error(requestId, ErrorCode.INVALID_PARAMS);
        }

        try (Context context = Context.newBuilder("js")
                .allowAllAccess(true)
                .build()) {

            Map<String, Object> variables = localVariableMap == null ? Maps.newHashMap() : localVariableMap;
            context.getBindings("js").putMember(JS_INPUT_BINDING_NAME, JsonUtils.toJson(variables));

            // Materialize workflow inputs as plain JavaScript values, not Java host objects.
            context.eval("js", """
                    Object.assign(globalThis, JSON.parse(__SAA_WORKFLOW_INPUT_JSON__ || '{}'));
                    delete globalThis.__SAA_WORKFLOW_INPUT_JSON__;
                    """);

            Value result = context.eval("js", scriptContent);
            Object javaResult = convertJavaScriptValue(result);

            // Keep the same output contract as the Python executor:
            // object -> its fields; scalar/array/null -> { "output": value }.
            Map<String, Object> output = Maps.newLinkedHashMap();
            if (javaResult instanceof Map<?, ?> resultMap) {
                resultMap.forEach((key, value) -> output.put(String.valueOf(key), value));
            }
            else {
                output.put(OUTPUT_DECORATE_PARAM_KEY, javaResult);
            }

            Map<String, Object> resultMap = Maps.newHashMap();
            resultMap.put("success", true);
            resultMap.put("data", output);
            return Result.success(requestId, JsonUtils.toJson(resultMap));
        }
        catch (Exception e) {
            String detail = rootCauseMessage(e);
            log.error("GraalVM JavaScript script execution exception: {}", detail, e);
            return Result.<String>builder()
                    .requestId(requestId)
                    .success(false)
                    .code(ErrorCode.SYSTEM_ERROR.getStatusCode())
                    .message("GraalVM JavaScript execution failed: " + detail)
                    .build();
        }
    }

    /**
     * Executes Python3 script using GraalVM Python.
     *
     * @param scriptContent Python script content
     * @param variables script variable mapping
     * @param requestId request ID
     * @return execution result
     */
    public Result<String> executePython3Script(String scriptContent, Map<String, Object> variables, String requestId) {
        try {
            Context.Builder contextBuilder = Context.newBuilder("python")
                    .allowAllAccess(true)
                    .option("python.ForceImportSite", "true");

            try (Context context = contextBuilder.build()) {
                if (variables != null && !variables.isEmpty()) {
                    for (Map.Entry<String, Object> entry : variables.entrySet()) {
                        context.getBindings("python").putMember(entry.getKey(), entry.getValue());
                    }
                }

                Value result = context.eval("python", scriptContent);
                Map<String, Object> resultMap = Maps.newHashMap();
                resultMap.put("success", true);
                Map<String, Object> innerMap = Maps.newHashMap();
                if (result.isNull()) {
                    innerMap.put(OUTPUT_DECORATE_PARAM_KEY, null);
                }
                else if (result.isString()) {
                    innerMap.put(OUTPUT_DECORATE_PARAM_KEY, result.asString());
                }
                else if (result.isNumber()) {
                    innerMap.put(OUTPUT_DECORATE_PARAM_KEY, result.asDouble());
                }
                else if (result.isBoolean()) {
                    innerMap.put(OUTPUT_DECORATE_PARAM_KEY, result.asBoolean());
                }
                else if (result.hasArrayElements()) {
                    List<Object> list = Lists.newArrayList();
                    for (long i = 0; i < result.getArraySize(); i++) {
                        list.add(result.getArrayElement(i).as(Object.class));
                    }
                    innerMap.put(OUTPUT_DECORATE_PARAM_KEY, list);
                }
                else if (result.hasMembers()) {
                    innerMap.putAll(result.as(Map.class));
                }
                resultMap.put("data", innerMap);
                return Result.success(requestId, JsonUtils.toJson(resultMap));
            }
        }
        catch (Exception e) {
            String detail = rootCauseMessage(e);
            log.error("GraalVM Python script execution exception: {}", detail, e);
            return Result.<String>builder()
                    .requestId(requestId)
                    .success(false)
                    .code(ErrorCode.SYSTEM_ERROR.getStatusCode())
                    .message("GraalVM Python execution failed: " + detail)
                    .build();
        }
    }

    /**
     * Converts a GraalVM JavaScript value into ordinary Java values so the result can be
     * serialized by the workflow JSON layer.
     */
    private Object convertJavaScriptValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.hasArrayElements()) {
            List<Object> values = Lists.newArrayList();
            for (long i = 0; i < value.getArraySize(); i++) {
                values.add(convertJavaScriptValue(value.getArrayElement(i)));
            }
            return values;
        }
        if (value.hasMembers()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String key : value.getMemberKeys()) {
                Value member = value.getMember(key);
                // Function-valued properties are executable code, not workflow output data.
                if (member != null && member.canExecute()) {
                    continue;
                }
                values.put(key, convertJavaScriptValue(member));
            }
            return values;
        }
        if (value.isHostObject()) {
            return convertHostObject(value.asHostObject());
        }

        String text = value.toString();
        return "undefined".equals(text) ? null : text;
    }

    private Object convertHostObject(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), convertHostObject(item)));
            return result;
        }
        if (value instanceof Iterable<?> source) {
            List<Object> result = Lists.newArrayList();
            for (Object item : source) {
                result.add(convertHostObject(item));
            }
            return result;
        }
        return value;
    }

    private String rootCauseMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = StringUtils.defaultIfBlank(root.getMessage(), root.getClass().getSimpleName());
        return root.getClass().getSimpleName() + ": " + message;
    }
}
