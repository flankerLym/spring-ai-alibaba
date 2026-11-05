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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Per-request JSON overlay after SDK serialization; never uses shared mutable options. */
public final class ModelWireParameters {
    public static final String CONTEXT_KEY = ModelWireParameters.class.getName();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<ModelWireParameters> BLOCKING = new ThreadLocal<>();
    private static final Set<String> MANAGED = Set.of("model", "messages", "input", "stream",
            "result_format", "incremental_output", "multi_model");
    private final String protocol;
    private final Map<String, Object> values;
    private final Map<String, String> headers;

    public ModelWireParameters(String protocol, Map<String, Object> values) {
        this.protocol = protocol;
        this.values = clean(values);
        this.headers = extraHeaders(this.values.remove("extra_headers"));
        if ("deepseek".equals(protocol) && this.values.containsKey("enable_thinking")) {
            if (this.values.containsKey("thinking")) throw new IllegalArgumentException("thinking 与 enable_thinking 只能启用一个");
            Object enabled = this.values.remove("enable_thinking");
            if (!(enabled instanceof Boolean)) throw new IllegalArgumentException("enable_thinking 必须为布尔值");
            this.values.put("thinking", Map.of("type", Boolean.TRUE.equals(enabled) ? "enabled" : "disabled"));
        }
        if ("dashscope".equals(protocol)) {
            Map<String, Object> translation = new LinkedHashMap<>();
            for (String key : List.of("source_lang", "target_lang", "domains"))
                if (this.values.containsKey(key)) translation.put(key, this.values.remove(key));
            if (!translation.isEmpty()) this.values.put("translation_options", translation);
        }
        Object schema = this.values.remove("json_schema");
        if (this.values.get("response_format") instanceof Map<?, ?> format && "json_schema".equals(format.get("type"))) {
            Map<String, Object> full = new LinkedHashMap<>((Map<String, Object>) format);
            if (schema instanceof String text && !text.isBlank()) {
                try { schema = JSON.readValue(text, Map.class); }
                catch (Exception error) { throw new IllegalArgumentException("json_schema 必须为合法 JSON 对象", error); }
            }
            if (schema != null) full.put("json_schema", schema);
            if (!(full.get("json_schema") instanceof Map<?, ?>)) throw new IllegalArgumentException("response_format=json_schema 时必须启用并填写 json_schema");
            this.values.put("response_format", full);
        }
        if (this.values.containsKey("max_tokens") && this.values.containsKey("max_completion_tokens"))
            throw new IllegalArgumentException("max_tokens 与 max_completion_tokens 不能同时启用");
        if ("dashscope".equals(protocol) && this.values.containsKey("thinking_budget")
                && this.values.containsKey("reasoning_effort"))
            throw new IllegalArgumentException("thinking_budget 与 reasoning_effort 请只启用一个");
    }

    public static Map<String, Object> clean(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw != null) raw.forEach((key, value) -> {
            if (!key.startsWith("__") && !MANAGED.contains(key)) result.put(key, value);
        });
        if (result.containsKey("temperate")) {
            result.putIfAbsent("temperature", result.remove("temperate"));
        }
        Object format = result.get("response_format");
        if (format instanceof String text)
            result.put("response_format", Map.of("type", "json".equals(text) ? "json_object" : text));
        Object thinking = result.get("thinking");
        if (thinking instanceof String text) result.put("thinking", Map.of("type", text));
        if (thinking instanceof Boolean enabled) result.put("thinking", Map.of("type", enabled ? "enabled" : "disabled"));
        return result;
    }

    private static Map<String, String> extraHeaders(Object value) {
        if (value == null || "".equals(value)) return Map.of();
        if (value instanceof String text) {
            try { value = JSON.readValue(text, Map.class); }
            catch (Exception error) { throw new IllegalArgumentException("extra_headers 必须为 JSON 对象", error); }
        }
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("extra_headers 必须为 JSON 对象");
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> managed = Set.of("authorization", "proxy-authorization", "cookie", "host", "content-length",
                "transfer-encoding", "content-type", "x-dashscope-sse", "connection");
        map.forEach((key, item) -> {
            String name = String.valueOf(key);
            if (!name.matches("[A-Za-z0-9-]+") || managed.contains(name.toLowerCase(Locale.ROOT))
                    || !(item instanceof String text) || text.contains("\r") || text.contains("\n"))
                throw new IllegalArgumentException("extra_headers 包含非法或执行链路管理的请求头: " + name);
            result.put(name, (String) item);
        });
        return result;
    }

    public <T> T call(Supplier<T> action) {
        ModelWireParameters previous = BLOCKING.get();
        BLOCKING.set(this);
        try { return action.get(); }
        finally { if (previous == null) BLOCKING.remove(); else BLOCKING.set(previous); }
    }

    byte[] merge(byte[] bytes) {
        if (values.isEmpty() || bytes.length == 0) return bytes;
        try {
            Map<String, Object> body = JSON.readValue(bytes, new TypeReference<>() { });
            Map<String, Object> target = body;
            if ("dashscope".equals(protocol)) {
                Object existing = body.get("parameters");
                target = existing instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
                body.put("parameters", target);
            }
            target.putAll(values);
            if (values.containsKey("max_completion_tokens")) target.remove("max_tokens");
            if (!"dashscope".equals(protocol) && !Boolean.TRUE.equals(body.get("stream"))) target.remove("stream_options");
            return JSON.writeValueAsBytes(body);
        }
        catch (Exception error) { throw new IllegalArgumentException("模型请求参数必须是合法 JSON", error); }
    }

    public static RestClient.Builder rest(RestClient.Builder builder) {
        return builder.clone().requestInterceptor((request, body, execution) -> {
            ModelWireParameters parameters = BLOCKING.get();
            byte[] merged = parameters == null ? body : parameters.merge(body);
            if (parameters != null) parameters.headers.forEach((key, value) -> request.getHeaders().set(key, value));
            request.getHeaders().setContentLength(merged.length);
            return execution.execute(request, merged);
        });
    }

    public static WebClient.Builder web(WebClient.Builder builder) {
        return builder.clone().filter((request, next) -> Mono.deferContextual(context -> {
            ModelWireParameters parameters = context.getOrDefault(CONTEXT_KEY, null);
            if (parameters == null) return next.exchange(request);
            ClientRequest modified = ClientRequest.from(request)
                    .headers(headers -> parameters.headers.forEach(headers::set)).body((output, inserterContext) ->
                request.body().insert(new ClientHttpRequestDecorator(output) {
                    @Override public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        return DataBufferUtils.join(body).flatMap(buffer -> {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            DataBufferUtils.release(buffer);
                            byte[] merged = parameters.merge(bytes);
                            getHeaders().setContentLength(merged.length);
                            return super.writeWith(Mono.just(bufferFactory().wrap(merged)));
                        });
                    }
                    @Override public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                        return writeWith(Flux.from(body).concatMap(Flux::from));
                    }
                }, inserterContext)).build();
            return next.exchange(modified);
        }));
    }
}
