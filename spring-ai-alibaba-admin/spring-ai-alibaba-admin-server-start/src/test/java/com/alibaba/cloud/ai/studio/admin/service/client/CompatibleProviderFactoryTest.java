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
package com.alibaba.cloud.ai.studio.admin.service.client;

import com.alibaba.cloud.ai.studio.admin.entity.ModelConfigDO;
import com.alibaba.cloud.ai.studio.admin.repository.ModelConfigRepository;
import com.alibaba.cloud.ai.studio.admin.service.ModelConfigBridgeService;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompatibleProviderFactoryTest {

    @Test
    void existingRegistryRoutesToExistingNativeFactories() {
        OpenAiChatClientFactory openai = mock(OpenAiChatClientFactory.class);
        DashScopeChatClientFactory dashscope = mock(DashScopeChatClientFactory.class);
        DeepSeekChatClientFactory deepseek = mock(DeepSeekChatClientFactory.class);
        DoubaoChatClientFactory doubao = mock(DoubaoChatClientFactory.class);
        when(openai.getSupportProvider()).thenReturn("openai");
        when(dashscope.getSupportProvider()).thenReturn("dashscope");
        when(deepseek.getSupportProvider()).thenReturn("deepseek");
        when(doubao.getSupportProvider()).thenReturn("doubao");
        ChatClientFactoryDelegate delegate = new ChatClientFactoryDelegate(mock(ModelConfigRepository.class),
                mock(ModelConfigBridgeService.class), new ObjectMapper(), ObservationRegistry.NOOP,
                mock(ChatClientObservationConvention.class), openai, dashscope, deepseek, doubao);
        Map<?, ?> factories = (Map<?, ?>) ReflectionTestUtils.getField(delegate, "chatClientFactories");
        assertSame(openai, factories.get("openai"));
        assertSame(dashscope, factories.get("dashscope"));
        assertSame(deepseek, factories.get("deepseek"));
        assertSame(doubao, factories.get("doubao"));
        assertSame(dashscope, factories.get("tongyi"));
    }

    @Test
    void openAiRetainsHttpAndStreaming() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestURI().getPath() + "|" + exchange.getRequestHeaders().getFirst("Authorization") + "|" + body);
            boolean streaming = new ObjectMapper().readTree(body).path("stream").asBoolean();
            String result = streaming
                    ? "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n"
                    : "{\"id\":\"mock\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            OpenAiChatClientFactory factory = new OpenAiChatClientFactory(ObservationRegistry.NOOP,
                    mock(ToolCallingManager.class), mock(ChatModelObservationConvention.class));
            ReflectionTestUtils.setField(factory, "providerConfig", new ModelProviderConfig(new MockEnvironment()));
            for (String provider : List.of("openai")) {
                String path = provider.equals("doubao") ? "/api/v3" : provider.equals("dashscope") ? "/compatible-mode/v1" : "/v1";
                ModelConfigDO config = ModelConfigDO.builder().provider(provider).modelName("test-model")
                        .apiKey("test-key").baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + path).build();
                var model = factory.buildChatModel(config);
                assertTrue(model instanceof org.springframework.ai.openai.OpenAiChatModel);
                var options = factory.buildChatOptions(config, Map.of(), Map.of());
                Prompt prompt = new Prompt(List.of(new UserMessage("hello")), options);
                assertEquals("ok", model.call(prompt).getResult().getOutput().getText());
                var responses = model.stream(prompt).collectList().block(Duration.ofSeconds(5));
                assertNotNull(responses);
                assertFalse(responses.isEmpty());
                String request = requests.get(requests.size() - 1);
                assertTrue(request.startsWith(path + "/chat/completions|Bearer test-key|"));
                assertTrue(new ObjectMapper().readTree(request.substring(request.indexOf("|{") + 1))
                        .path("model").asText().equals("test-model"));
            }
            assertEquals(2, requests.size());
        }
        finally {
            server.stop(0);
        }
    }
}
