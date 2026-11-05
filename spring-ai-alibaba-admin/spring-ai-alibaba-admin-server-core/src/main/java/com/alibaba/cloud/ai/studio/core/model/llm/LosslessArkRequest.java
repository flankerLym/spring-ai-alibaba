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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keep the Ark SDK's authentication, retry and SSE parsing, with lossless request JSON. */
@JsonSerialize(using = LosslessArkRequest.Serializer.class)
final class LosslessArkRequest extends ChatCompletionRequest {
    private final Map<String, Object> body;
    LosslessArkRequest(Map<String, Object> body) {
        this.body = new LinkedHashMap<>(body);
        setModel(String.valueOf(body.get("model")));
        setStream(Boolean.TRUE.equals(body.get("stream")));
    }
    static com.fasterxml.jackson.databind.ObjectMapper mapper() {
        var mapper = com.volcengine.ark.runtime.service.ArkService.defaultObjectMapper();
        var fallback = mapper.copy();
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(ChatCompletionRequest.class, new JsonSerializer<ChatCompletionRequest>() {
            @Override public void serialize(ChatCompletionRequest value, JsonGenerator generator,
                    SerializerProvider provider) throws IOException {
                if (value instanceof LosslessArkRequest request) generator.writeObject(request.body);
                else generator.writeTree(fallback.valueToTree(value));
            }
        });
        return mapper.registerModule(module);
    }

    public static final class Serializer extends JsonSerializer<LosslessArkRequest> {
        @Override public void serialize(LosslessArkRequest value, JsonGenerator generator,
                SerializerProvider provider) throws IOException {
            generator.writeObject(value.body);
        }
    }
}
