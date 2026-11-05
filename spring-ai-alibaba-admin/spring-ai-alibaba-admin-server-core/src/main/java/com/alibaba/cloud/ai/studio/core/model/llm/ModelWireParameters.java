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

    public ModelWireParameters(String protocol, Map<String, Object> values) {
        this.protocol = protocol;
        this.values = clean(values);
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
            request.getHeaders().setContentLength(merged.length);
            return execution.execute(request, merged);
        });
    }

    public static WebClient.Builder web(WebClient.Builder builder) {
        return builder.clone().filter((request, next) -> Mono.deferContextual(context -> {
            ModelWireParameters parameters = context.getOrDefault(CONTEXT_KEY, null);
            if (parameters == null) return next.exchange(request);
            ClientRequest modified = ClientRequest.from(request).body((output, inserterContext) ->
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
