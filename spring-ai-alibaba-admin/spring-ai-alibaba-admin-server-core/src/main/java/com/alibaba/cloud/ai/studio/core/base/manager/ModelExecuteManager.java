/*
 * Copyright 2024-2026 the original author or authors.
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

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Executes LLM requests using the model's workflow JSON snapshot. All runtime
 * configuration is already in parameterMap: NO model metadata database/Redis
 * lookups, remote discovery, or name-based model type heuristics occur here.
 */
@Slf4j
@Component
public class ModelExecuteManager {
    @Resource private ModelFactory modelFactory;
    private static final String META_KEY = "__model_metadata__";

    @WorkflowModelCall
    public Flux<AgentResponse> stream(String provider, String modelId,
            Map<String, Object> parameterMap, List<Message> messages) {
        ChatModel chatModel = modelFactory.getChatModel(provider);
        Object snapshot = parameterMap == null ? null : parameterMap.get(META_KEY);
        String requestModelId = modelId;
        boolean doubao = chatModel instanceof NativeOptionsChatModel nativeModel
                && nativeModel.usesProvider("doubao");

        if (snapshot instanceof Map<?, ?> m) {
            Object savedModel = m.get("model_id");
            if (savedModel != null && !modelId.equals(String.valueOf(savedModel))) {
                throw new IllegalArgumentException("工作流模型ID与元数据快照不一致，请重新选择模型并保存工作流");
            }
            Object savedProtocol = m.get("protocol");
            if (savedProtocol != null && !"openai".equalsIgnoreCase(String.valueOf(savedProtocol))
                    && !(chatModel instanceof NativeOptionsChatModel nativeModel
                        && nativeModel.usesProvider(String.valueOf(savedProtocol)))) {
                throw new IllegalArgumentException("供应商协议已变化，请重新保存工作流中的模型配置");
            }

            // Ark accepts either an endpoint ID or a model ID in the `model` field.
            // Prefer the endpoint when configured; otherwise preserve the logical model ID.
            if (doubao) {
                Object savedEndpoint = m.get("endpoint_id");
                String endpointId = savedEndpoint == null ? "" : String.valueOf(savedEndpoint).trim();
                if (StringUtils.isNotBlank(endpointId)) {
                    requestModelId = endpointId;
                }
            }
        }

        // Older workflows may not have a metadata snapshot. In that case,
        // requestModelId remains modelId, so endpoint_id is optional.
        ChatOptions options = createOptions(requestModelId, parameterMap);
        Prompt prompt = new Prompt(messages, options);
        Flux<AgentResponse> stream = chatModel.stream(prompt)
                .concatMap(response -> convertResponse(response).flux());
        // A few providers return an empty streaming completion without a content
        // generation. The fallback uses the SAME selected protocol and validated
        // options rather than inferring model capabilities again.
        return stream.switchIfEmpty(Flux.defer(() -> convertResponse(chatModel.call(prompt))
                .switchIfEmpty(Mono.error(new IllegalStateException("模型未返回有效回复"))).flux()));
    }

    private ChatOptions createOptions(String modelId, Map<String, Object> params) {
        Map<String, Object> p = params == null ? Map.of() : params;
        Object value = p.get(META_KEY);
        Map<?, ?> metadata = value instanceof Map<?, ?> map ? map : Map.of();
        String protocol = String.valueOf(metadata.get("protocol") == null ? "" : metadata.get("protocol"));
        String route = String.valueOf(metadata.get("endpoint_type") == null ? "" : metadata.get("endpoint_type"));
        // Metadata from PostgreSQL -> workflow JSON is an execution snapshot.
        // The native DashScope SDK chooses the correct API path from multiModel.
        if ("dashscope".equalsIgnoreCase(protocol)) {
            if ("unknown".equals(route)) {
                throw new IllegalArgumentException("模型尚未确认 DashScope 推理接口类型，请刷新供应商模型元数据并重新保存工作流");
            }
            DashScopeChatOptions.DashscopeChatOptionsBuilder builder = DashScopeChatOptions.builder()
                    .withModel(modelId).withStream(true).withIncrementalOutput(true)
                    .withMultiModel("multimodal-generation".equals(route));
            if (number(p, "temperature") != null) builder.withTemperature(number(p, "temperature").doubleValue());
            if (number(p, "max_tokens") != null) builder.withMaxToken(number(p, "max_tokens").intValue());
            if (number(p, "top_p") != null) builder.withTopP(number(p, "top_p").doubleValue());
            if (number(p, "top_k") != null) builder.withTopK(number(p, "top_k").intValue());
            if (number(p, "seed") != null) builder.withSeed(number(p, "seed").intValue());
            if (number(p, "repetition_penalty") != null)
                builder.withRepetitionPenalty(number(p, "repetition_penalty").doubleValue());
            if (p.get("enable_thinking") instanceof Boolean b) builder.withEnableThinking(b);
            if (p.get("enable_search") instanceof Boolean b) builder.withEnableSearch(b);
            return builder.build();
        }
        // Keep the existing shared protocol adapter for DeepSeek, Doubao and
        // OpenAI-compatible providers. Only explicitly enabled values are passed.
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(modelId);
        if (number(p, "temperature") != null) builder.temperature(number(p, "temperature").doubleValue());
        if (number(p, "max_tokens") != null) builder.maxTokens(number(p, "max_tokens").intValue());
        if (number(p, "top_p") != null) builder.topP(number(p, "top_p").doubleValue());
        if (number(p, "presence_penalty") != null) builder.presencePenalty(number(p, "presence_penalty").doubleValue());
        if (number(p, "frequency_penalty") != null) builder.frequencyPenalty(number(p, "frequency_penalty").doubleValue());
        if (number(p, "seed") != null) builder.seed(number(p, "seed").intValue());
        if (p.get("response_format") instanceof String s && StringUtils.isNotBlank(s)) {
            builder.responseFormat(ResponseFormat.builder()
                    .type(ResponseFormat.Type.valueOf(s.toUpperCase(java.util.Locale.ROOT))).build());
        }
        // Non-standard supplier options are saved only when enabled in the
        // workflow JSON. The Ark SDK consumes thinking/reasoning_effort from
        // extraBody, while OpenAI-compatible providers may support extra keys.
        // Never forward the reserved internal metadata object to a provider.
        Set<String> standard = Set.of(META_KEY, "temperature", "max_tokens", "top_p",
                "presence_penalty", "frequency_penalty", "seed", "response_format");
        Map<String, Object> extra = new LinkedHashMap<>();
        p.forEach((key, raw) -> {
            if (raw != null && !standard.contains(key) && !key.startsWith("__"))
                extra.put(key, raw);
        });
        if (!extra.isEmpty()) builder.extraBody(extra);
        builder.streamUsage(true);
        return builder.build();
    }

    private Number number(Map<String, Object> values, String name) {
        Object v = values.get(name);
        if (v instanceof Number n) return n;
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.valueOf(s); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    protected Mono<AgentResponse> convertResponse(ChatResponse response) {
        if (response == null || CollectionUtils.isEmpty(response.getResults())) return Mono.empty();
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
        AgentResponse.AgentResponseBuilder builder = AgentResponse.builder()
                .model(response.getMetadata().getModel())
                .providerResponseId(response.getMetadata().getId())
                .usage(Usage.builder()
                        .promptTokens(usage == null ? 0 : usage.getPromptTokens())
                        .completionTokens(usage == null ? 0 : usage.getCompletionTokens())
                        .totalTokens(usage == null ? 0 : usage.getTotalTokens())
                        .build());
        Generation generation = response.getResults().get(0);
        String finishReason = generation.getMetadata().getFinishReason();
        AgentStatus status = AgentStatus.toAgentStatus(finishReason);
        Object reasoning = generation.getOutput().getMetadata().get("reasoningContent");
        String reasoningText = reasoning == null ? null : String.valueOf(reasoning);
        if (StringUtils.isBlank(reasoningText)) reasoningText = null;
        ChatMessage message = ChatMessage.builder().role(MessageRole.ASSISTANT)
                .content(generation.getOutput().getText())
                .reasoningContent(reasoningText).build();
        return Mono.just(builder.status(status).message(message).build());
    }
}
