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

import com.alibaba.cloud.ai.studio.core.workflow.trace.annotation.WorkflowModelCall;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentResponse;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentStatus;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.ChatMessage;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.MessageRole;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.Usage;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelFactory;
import com.alibaba.cloud.ai.studio.core.model.llm.NativeOptionsChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Model execution manager for handling chat model interactions.
 */
@Slf4j
@Component
public class ModelExecuteManager {

    @Resource
    private ModelFactory modelFactory;

    @WorkflowModelCall
    public Flux<AgentResponse> stream(String provider, String modelId, Map<String, Object> parameterMap,
                                      List<Message> messages) {
        ChatModel chatModel = modelFactory.getChatModel(provider);

        // DashScope/Qwen: select the API route from the model metadata snapshot
        // saved with this workflow, never from a hard-coded model-name list.
        // All non-DashScope providers continue through the original path below.
        if (chatModel instanceof NativeOptionsChatModel nativeModel
                && nativeModel.usesProvider("dashscope")
                && parameterMap != null) {
            Object rawMetadata = parameterMap.get("__model_metadata__");
            if (rawMetadata instanceof Map<?, ?> metadata
                    && "dashscope".equalsIgnoreCase(String.valueOf(metadata.get("protocol")))) {
                Object savedModelId = metadata.get("model_id");
                boolean sameModel = savedModelId == null || modelId.equals(String.valueOf(savedModelId));
                if (sameModel) {
                    String endpointType = String.valueOf(metadata.get("endpoint_type"));
                    if ("text-generation".equalsIgnoreCase(endpointType)
                            || "multimodal-generation".equalsIgnoreCase(endpointType)) {
                        return streamDashScopeByEndpoint(chatModel, modelId, parameterMap, messages, endpointType);
                    }
                    throw new IllegalStateException(
                            "DashScope模型接口类型尚未确认，请刷新供应商模型元数据并重新保存工作流");
                }
            }
        }

        // Doubao only: use the endpoint ID saved in this workflow's model metadata.
        // All other providers keep the original model ID and parameter assembly path.
        String requestModelId = modelId;
        if (chatModel instanceof NativeOptionsChatModel nativeModel
                && nativeModel.usesProvider("doubao") && parameterMap != null) {
            Object rawMetadata = parameterMap.get("__model_metadata__");
            if (rawMetadata instanceof Map<?, ?> metadata) {
                Object savedModelId = metadata.get("model_id");
                Object savedEndpointId = metadata.get("endpoint_id");
                boolean sameModel = savedModelId == null || modelId.equals(String.valueOf(savedModelId));
                if (sameModel && savedEndpointId != null
                        && StringUtils.isNotBlank(String.valueOf(savedEndpointId))) {
                    requestModelId = String.valueOf(savedEndpointId).trim();
                }
            }
        }

        OpenAiChatOptions.Builder chatOptionsBuilder = OpenAiChatOptions.builder().model(requestModelId);
        if (parameterMap != null) {
            if (parameterMap.get("temperate") != null && parameterMap.get("temperate") instanceof Double) {
                chatOptionsBuilder.temperature((Double) parameterMap.get("temperate"));
            }
            if (parameterMap.get("max_tokens") != null && parameterMap.get("max_tokens") instanceof Integer) {
                chatOptionsBuilder.maxTokens((Integer) parameterMap.get("max_tokens"));
            }
            if (parameterMap.get("top_p") != null && parameterMap.get("top_p") instanceof Double) {
                chatOptionsBuilder.topP((Double) parameterMap.get("top_p"));
            }
            if (parameterMap.get("presence_penalty") != null
                    && parameterMap.get("presence_penalty") instanceof Double) {
                chatOptionsBuilder.presencePenalty((Double) parameterMap.get("presence_penalty"));
            }
            if (parameterMap.get("frequency_penalty") != null
                    && parameterMap.get("frequency_penalty") instanceof Double) {
                chatOptionsBuilder.frequencyPenalty((Double) parameterMap.get("frequency_penalty"));
            }
            if (parameterMap.get("max_tokens") != null && parameterMap.get("max_tokens") instanceof Integer) {
                chatOptionsBuilder.maxTokens((Integer) parameterMap.get("max_tokens"));
            }
            if (parameterMap.get("seed") != null && parameterMap.get("seed") instanceof Integer) {
                chatOptionsBuilder.seed((Integer) parameterMap.get("seed"));
            }
            if (parameterMap.get("response_format") != null
                    && parameterMap.get("response_format") instanceof String) {
                chatOptionsBuilder.responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.valueOf(
                                parameterMap.get("response_format").toString().toUpperCase()))
                        .build());
            }
        }
        chatOptionsBuilder.streamUsage(true);

        ChatOptions chatOptions = chatOptionsBuilder.build();
        Prompt prompt = new Prompt(messages, chatOptions);
        // Keep the original streaming/fallback behavior for all existing providers.
        Flux<AgentResponse> stream = chatModel.stream(prompt)
                .concatMap(response -> convertResponse(response).flux());

        return stream.switchIfEmpty(Flux.defer(() -> convertResponse(chatModel.call(prompt))
                .switchIfEmpty(Mono.error(new IllegalStateException("模型未返回有效回复")))
                .flux()));
    }

    /**
     * Uses the endpoint type stored in the selected DashScope model's metadata. The
     * native options are isolated to DashScope and do not alter other providers.
     */
    private Flux<AgentResponse> streamDashScopeByEndpoint(ChatModel chatModel, String modelId,
                                                        Map<String, Object> parameterMap,
                                                        List<Message> messages, String endpointType) {
        Map<String, Object> params = parameterMap == null ? Map.of() : parameterMap;
        DashScopeChatOptions.DashscopeChatOptionsBuilder builder = DashScopeChatOptions.builder()
                .withModel(modelId)
                .withStream(true)
                .withIncrementalOutput(true)
                .withMultiModel("multimodal-generation".equalsIgnoreCase(endpointType));

        Number temperature = numberParam(params, "temperature");
        if (temperature == null) {
            temperature = numberParam(params, "temperate"); // Keep compatibility with the legacy key.
        }
        if (temperature != null) builder.withTemperature(temperature.doubleValue());

        Number maxTokens = numberParam(params, "max_tokens");
        if (maxTokens != null) builder.withMaxToken(maxTokens.intValue());

        Number topP = numberParam(params, "top_p");
        if (topP != null) builder.withTopP(topP.doubleValue());

        Number topK = numberParam(params, "top_k");
        if (topK != null) builder.withTopK(topK.intValue());

        Number seed = numberParam(params, "seed");
        if (seed != null) builder.withSeed(seed.intValue());

        Number repetitionPenalty = numberParam(params, "repetition_penalty");
        if (repetitionPenalty != null) builder.withRepetitionPenalty(repetitionPenalty.doubleValue());

        if (params.get("enable_thinking") instanceof Boolean enabled) builder.withEnableThinking(enabled);
        if (params.get("enable_search") instanceof Boolean enabled) builder.withEnableSearch(enabled);

        Prompt prompt = new Prompt(messages, builder.build());
        Flux<AgentResponse> stream = chatModel.stream(prompt)
                .concatMap(response -> convertResponse(response).flux());
        return stream.switchIfEmpty(Flux.defer(() -> convertResponse(chatModel.call(prompt))
                .switchIfEmpty(Mono.error(new IllegalStateException("模型未返回有效回复")))
                .flux()));
    }

    private Number numberParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
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

    protected Mono<AgentResponse> convertResponse(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return Mono.empty();
        }

        org.springframework.ai.chat.metadata.Usage usage = chatResponse.getMetadata().getUsage();

        AgentResponse.AgentResponseBuilder responseBuilder = AgentResponse.builder()
                .model(chatResponse.getMetadata().getModel())
                .providerResponseId(chatResponse.getMetadata().getId())
                .usage(Usage.builder()
                        .promptTokens(usage.getPromptTokens())
                        .completionTokens(usage.getCompletionTokens())
                        .totalTokens(usage.getTotalTokens())
                        .build());

        if (!CollectionUtils.isEmpty(chatResponse.getResults())) {
            Generation generation = chatResponse.getResults().get(0);
            String finishReason = generation.getMetadata().getFinishReason();
            AgentStatus status = AgentStatus.toAgentStatus(finishReason);

            Object reasoningContentObj = generation.getOutput().getMetadata().get("reasoningContent");
            String reasoningContent = reasoningContentObj == null ? null : String.valueOf(reasoningContentObj);
            reasoningContent = StringUtils.isBlank(reasoningContent) ? null : reasoningContent;

            ChatMessage.ChatMessageBuilder messageBuilder = ChatMessage.builder()
                    .role(MessageRole.ASSISTANT)
                    .content(generation.getOutput().getText())
                    .reasoningContent(reasoningContent);

            responseBuilder.status(status).message(messageBuilder.build());
        }
        else {
            return Mono.empty();
        }

        return Mono.just(responseBuilder.build());
    }
}
