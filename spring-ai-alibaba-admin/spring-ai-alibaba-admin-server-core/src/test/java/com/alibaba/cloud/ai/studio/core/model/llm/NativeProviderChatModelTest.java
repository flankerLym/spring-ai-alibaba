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
package com.alibaba.cloud.ai.studio.core.model.llm;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ModelCredential;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.completion.chat.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import io.reactivex.Flowable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeProviderChatModelTest {
    private final ObjectMapper json = ArkService.defaultObjectMapper();
    private final ToolCallingManager tools = mock(ToolCallingManager.class);

    @Test void eachVendorBuildsItsOwnTransportWithoutCallingNetwork() {
        var factory = new ModelFactory();
        ReflectionTestUtils.setField(factory, "toolCallingManager", tools);
        ReflectionTestUtils.setField(factory, "observationRegistry", ObservationRegistry.NOOP);
        ReflectionTestUtils.setField(factory, "customChatModelObservationConvention", new DefaultChatModelObservationConvention());
        for (String provider : List.of("dashscope", "deepseek", "doubao")) {
            ModelCredential input = new ModelCredential();
            input.setApiKey("test-key");
            input.setEndpoint(ModelProviderConfig.defaultEndpoint(provider));
            ChatModel routed = factory.createNativeChatModel(provider, ModelProviderConfig.route(input));
            Object delegate = ReflectionTestUtils.getField(routed, "delegate");
            switch (provider) {
                case "dashscope" -> assertInstanceOf(DashScopeChatModel.class, delegate);
                case "deepseek" -> assertInstanceOf(DeepSeekChatModel.class, delegate);
                default -> assertInstanceOf(ArkChatModel.class, delegate);
            }
        }
        assertEquals("https://dashscope.aliyuncs.com", ModelFactory
                .dashScopeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/"));
        assertEquals("https://dashscope-intl.aliyuncs.com", ModelFactory
                .dashScopeBaseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode"));
    }

    @Test void savedProviderIdRoutesByProtocolAndLegacyBlankRemainsOpenAi() {
        var factory = new ModelFactory();
        var manager = mock(com.alibaba.cloud.ai.studio.core.base.manager.ProviderManager.class);
        ReflectionTestUtils.setField(factory, "providerManager", manager);
        ReflectionTestUtils.setField(factory, "modelProviderConfig",
                new ModelProviderConfig(new org.springframework.mock.env.MockEnvironment()));
        ReflectionTestUtils.setField(factory, "toolCallingManager", tools);
        ReflectionTestUtils.setField(factory, "observationRegistry", ObservationRegistry.NOOP);
        ReflectionTestUtils.setField(factory, "customChatModelObservationConvention", new DefaultChatModelObservationConvention());
        var detail = new com.alibaba.cloud.ai.studio.core.model.llm.domain.ProviderConfigInfo();
        var credential = new ModelCredential();
        credential.setApiKey("test-key");
        credential.setEndpoint("https://api.deepseek.com");
        detail.setCredential(credential);
        detail.setProtocol("deepseek");
        when(manager.getProviderDetail("record-id", false)).thenReturn(detail);
        assertInstanceOf(DeepSeekChatModel.class,
                ReflectionTestUtils.getField(factory.getChatModel("record-id"), "delegate"));
        detail.setProtocol(null);
        assertInstanceOf(org.springframework.ai.openai.OpenAiChatModel.class, factory.getChatModel("record-id"));
    }

    @Test void nativeOptionsPreserveModelSamplingAndExternalToolExecution() {
        var original = OpenAiChatOptions.builder().model("test-model").temperature(0.3)
                .maxTokens(123).topP(0.8).internalToolExecutionEnabled(false)
                .toolNames(Set.of("weather")).toolContext(Map.of("session", "s1")).build();
        for (String provider : List.of("dashscope", "deepseek")) {
            var wrapper = new NativeOptionsChatModel(provider, mock(ChatModel.class));
            var adapted = wrapper.adapt(new Prompt("hello", original)).getOptions();
            if (provider.equals("dashscope")) {
                assertInstanceOf(DashScopeChatOptions.class, adapted);
            }
            else {
                assertInstanceOf(DeepSeekChatOptions.class, adapted);
            }
            assertEquals("test-model", adapted.getModel());
            assertEquals(123, adapted.getMaxTokens());
            var toolOptions = (org.springframework.ai.model.tool.ToolCallingChatOptions) adapted;
            assertFalse(toolOptions.getInternalToolExecutionEnabled());
            assertEquals(Set.of("weather"), toolOptions.getToolNames());
            assertEquals("s1", toolOptions.getToolContext().get("session"));
        }
    }

    private ArkChatModel adapter(ArkService sdk) {
        return new ArkChatModel(() -> sdk, tools, ObservationRegistry.NOOP,
                new DefaultChatModelObservationConvention());
    }

    @Test void arkRequestUsesSdkMessagesAndPreservesToolResponses() {
        var model = adapter(mock(ArkService.class));
        var options = OpenAiChatOptions.builder().model("ep-test").maxTokens(80)
                .internalToolExecutionEnabled(false).build();
        var assistant = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "weather", "{}"))).build();
        var tool = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "weather", "sunny"))).build();
        var request = model.request(new Prompt(List.of(new UserMessage("hello"), assistant, tool), options), true);
        assertTrue(request.getStreamOptions().getIncludeUsage());
        JsonNodeAssertions.assertRequest(json.valueToTree(request));
    }

    @Test void arkRequestConvertsWorkflowAndAgentImageSources() throws Exception {
        var model = adapter(mock(ArkService.class));
        var options = OpenAiChatOptions.builder().model("ep-test").build();
        var local = new org.springframework.ai.content.Media(org.springframework.util.MimeType.valueOf("image/png"),
                new org.springframework.core.io.ByteArrayResource(new byte[]{1, 2, 3}));
        var dataUri = new org.springframework.ai.content.Media(org.springframework.util.MimeType.valueOf("image/png"),
                new org.springframework.core.io.ByteArrayResource("data:image/png;base64,YWJj".getBytes()));
        var remote = new org.springframework.ai.content.Media(org.springframework.util.MimeType.valueOf("image/png"),
                new java.net.URL("https://example.com/image.png"));
        var prompt = new Prompt(List.of(
                UserMessage.builder().text("local").media(List.of(local)).build(),
                UserMessage.builder().text("agent data uri").media(List.of(dataUri)).build(),
                UserMessage.builder().text("remote").media(List.of(remote)).build()), options);
        var request = json.valueToTree(model.request(prompt, false));
        assertEquals("data:image/png;base64,AQID", request.at("/messages/0/content/1/image_url/url").asText());
        assertEquals("data:image/png;base64,YWJj", request.at("/messages/1/content/1/image_url/url").asText());
        assertEquals("https://example.com/image.png", request.at("/messages/2/content/1/image_url/url").asText());
    }

    @Test void arkCallConvertsTextReasoningUsageAndClosesSdk() throws Exception {
        ArkService sdk = mock(ArkService.class);
        when(sdk.createChatCompletion(any())).thenReturn(json.readValue("""
                {"id":"r1","model":"ep-test","choices":[{"index":0,"message":
                {"role":"assistant","content":"ok","reasoning_content":"think"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                """, ChatCompletionResult.class));
        ChatResponse result = adapter(sdk).call(new Prompt("hi",
                OpenAiChatOptions.builder().model("ep-test").build()));
        assertEquals("ok", result.getResult().getOutput().getText());
        assertEquals("think", result.getResult().getOutput().getMetadata().get("reasoningContent"));
        assertEquals(5, result.getMetadata().getUsage().getTotalTokens());
        verify(sdk).shutdownExecutor();
    }

    @Test void arkStreamingUsesSdkMessageAliasAndAssemblesFragmentedToolArguments() throws Exception {
        ArkService sdk = mock(ArkService.class);
        ChatCompletionChunk first = json.readValue("""
                {"id":"s1","model":"ep-test","choices":[{"index":0,"delta":{"role":"assistant",
                "content":"ok","tool_calls":[{"index":0,"id":"c1","type":"function",
                "function":{"name":"weather","arguments":"{\\\"city\\\":"}}]},"finish_reason":null}]}
                """, ChatCompletionChunk.class);
        ChatCompletionChunk second = json.readValue("""
                {"id":"s1","model":"ep-test","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,
                "function":{"arguments":"\\\"Nanning\\\"}"}}]},"finish_reason":"tool_calls"}]}
                """, ChatCompletionChunk.class);
        ChatCompletionChunk usage = json.readValue("""
                {"id":"s1","model":"ep-test","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                """, ChatCompletionChunk.class);
        when(sdk.streamChatCompletion(any())).thenReturn(Flowable.just(first, second, usage));
        var options = OpenAiChatOptions.builder().model("ep-test").internalToolExecutionEnabled(false).build();
        var responses = adapter(sdk).stream(new Prompt("hi", options)).collectList().block(Duration.ofSeconds(5));
        assertNotNull(responses);
        assertEquals("ok", responses.get(0).getResult().getOutput().getText());
        assertFalse(responses.get(0).hasToolCalls());
        var last = responses.get(responses.size()-1);
        assertEquals("{\"city\":\"Nanning\"}", last.getResult().getOutput().getToolCalls().get(0).arguments());
        assertEquals(5, last.getMetadata().getUsage().getTotalTokens());
        verify(sdk).shutdownExecutor();
    }

    private static final class JsonNodeAssertions {
        static void assertRequest(com.fasterxml.jackson.databind.JsonNode root) {
            assertEquals("ep-test", root.path("model").asText());
            assertEquals(80, root.path("max_tokens").asInt());
            assertTrue(root.path("stream_options").path("include_usage").asBoolean());
            assertEquals("call-1", root.path("messages").get(2).path("tool_call_id").asText());
        }
    }
}
