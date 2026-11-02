/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ModelExecuteManager {

    @Resource private ModelFactory modelFactory;

    @WorkflowModelCall
    public Flux<AgentResponse> stream(String provider, String modelId, Map<String, Object> parameterMap,
                                      List<Message> messages) {
        ChatModel chatModel = modelFactory.getChatModel(provider);

        if (chatModel instanceof NativeOptionsChatModel nativeModel
                && nativeModel.usesProvider("dashscope") && parameterMap != null) {
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

        String requestModelId = modelId;
        if (chatModel instanceof NativeOptionsChatModel nativeModel
                && nativeModel.usesProvider("doubao") && parameterMap != null) {
            Object rawMetadata = parameterMap.get("__model_metadata__");
            if (rawMetadata instanceof Map<?, ?> metadata) {
                Object savedModelId = metadata.get("model_id");
                Object savedEndpointId = metadata.get("endpoint_id");
                boolean sameModel = savedModelId == null || modelId.equals(String.valueOf(savedModelId));
                if (sameModel && savedEndpointId != null && StringUtils.isNotBlank(String.valueOf(savedEndpointId))) {
                    requestModelId = String.valueOf(savedEndpointId).trim();
                }
            }
        }

        Map<String, Object> params = parameterMap == null ? Map.of() : parameterMap;
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(requestModelId);

        Number temperature = numberParam(params, "temperature");
        if (temperature == null) temperature = numberParam(params, "temperate"); // legacy typo compatibility
        if (temperature != null) builder.temperature(temperature.doubleValue());

        Number maxTokens = numberParam(params, "max_tokens");
        if (maxTokens != null) builder.maxTokens(maxTokens.intValue());

        Number topP = numberParam(params, "top_p");
        if (topP != null) builder.topP(topP.doubleValue());

        Number presencePenalty = numberParam(params, "presence_penalty");
        if (presencePenalty != null) builder.presencePenalty(presencePenalty.doubleValue());

        Number frequencyPenalty = numberParam(params, "frequency_penalty");
        if (frequencyPenalty != null) builder.frequencyPenalty(frequencyPenalty.doubleValue());

        Number seed = numberParam(params, "seed");
        if (seed != null) builder.seed(seed.intValue());


        Object responseFormat = params.get("response_format");
        if (responseFormat != null) {
            String format = responseFormat instanceof Map<?, ?> map
                    ? String.valueOf(map.get("type") == null ? "text" : map.get("type")) : String.valueOf(responseFormat);
            if ("json".equalsIgnoreCase(format)) format = "json_object";
            if ("text".equalsIgnoreCase(format) || "json_object".equalsIgnoreCase(format)) {
                builder.responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.valueOf(format.toUpperCase()))
                        .build());
            }
        }

        // Model-specific fields not represented by the portable ChatOptions API
        // are forwarded through Spring AI's OpenAI-compatible extra_body mechanism.
        // The DashScope native route returned above and does not use this branch.
        Map<String, Object> extraBody = new LinkedHashMap<>();
        List<String> standardKeys = List.of("temperature", "temperate", "max_tokens", "top_p",
                "presence_penalty", "frequency_penalty", "seed", "stop", "response_format",
                "__model_metadata__");
        params.forEach((key, value) -> {
            if (!standardKeys.contains(key) && !key.startsWith("__") && value != null) {
                extraBody.put(key, value);
            }
        });
        if (!extraBody.isEmpty()) builder.extraBody(extraBody);

        builder.streamUsage(true);
        Prompt prompt = new Prompt(messages, builder.build());
        Flux<AgentResponse> stream = chatModel.stream(prompt)
                .concatMap(response -> convertResponse(response).flux());
        return stream.switchIfEmpty(Flux.defer(() -> convertResponse(chatModel.call(prompt))
                .switchIfEmpty(Mono.error(new IllegalStateException("模型未返回有效回复")))
                .flux()));
    }

    private Flux<AgentResponse> streamDashScopeByEndpoint(ChatModel chatModel, String modelId,
                                                           Map<String, Object> parameterMap,
                                                           List<Message> messages, String endpointType) {
        Map<String, Object> params = parameterMap == null ? Map.of() : parameterMap;
        DashScopeChatOptions.DashscopeChatOptionsBuilder builder = DashScopeChatOptions.builder()
                .withModel(modelId).withStream(true).withIncrementalOutput(true)
                .withMultiModel("multimodal-generation".equalsIgnoreCase(endpointType));

        Number temperature = numberParam(params, "temperature");
        if (temperature == null) temperature = numberParam(params, "temperate");
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
            try { return Double.valueOf(text); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> values) {
            for (Object item : values) if (item != null && !String.valueOf(item).isBlank())
                result.add(String.valueOf(item));
        } else if (value instanceof String text && !text.isBlank()) {
            for (String item : text.split("[,\\n]")) if (!item.isBlank()) result.add(item.trim());
        }
        return result;
    }

    protected Mono<AgentResponse> convertResponse(ChatResponse chatResponse) {
        if (chatResponse == null) return Mono.empty();

        org.springframework.ai.chat.metadata.Usage usage = chatResponse.getMetadata().getUsage();
        AgentResponse.AgentResponseBuilder responseBuilder = AgentResponse.builder()
                .model(chatResponse.getMetadata().getModel())
                .providerResponseId(chatResponse.getMetadata().getId())
                .usage(Usage.builder().promptTokens(usage.getPromptTokens())
                        .completionTokens(usage.getCompletionTokens()).totalTokens(usage.getTotalTokens()).build());

        if (!CollectionUtils.isEmpty(chatResponse.getResults())) {
            Generation generation = chatResponse.getResults().get(0);
            String finishReason = generation.getMetadata().getFinishReason();
            AgentStatus status = AgentStatus.toAgentStatus(finishReason);
            Object reasoningContentObj = generation.getOutput().getMetadata().get("reasoningContent");
            String reasoningContent = reasoningContentObj == null ? null : String.valueOf(reasoningContentObj);
            reasoningContent = StringUtils.isBlank(reasoningContent) ? null : reasoningContent;
            ChatMessage message = ChatMessage.builder().role(MessageRole.ASSISTANT)
                    .content(generation.getOutput().getText()).reasoningContent(reasoningContent).build();
            responseBuilder.status(status).message(message);
        } else return Mono.empty();

        return Mono.just(responseBuilder.build());
    }
}
