/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.core.base.manager;

import com.alibaba.cloud.ai.studio.core.workflow.trace.annotation.WorkflowModelCall;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelFactory;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelRequestAdapter;
import com.alibaba.cloud.ai.studio.core.model.llm.NativeOptionsChatModel;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentResponse;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentStatus;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.ChatMessage;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.MessageRole;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.Usage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ModelExecuteManager {

    @Resource private ModelFactory modelFactory;
    @Resource private List<ModelRequestAdapter> requestAdapters;

    @WorkflowModelCall
    public Flux<AgentResponse> stream(String provider, String modelId, Map<String, Object> parameterMap,
                                     List<Message> messages) {
        ChatModel chatModel = modelFactory.getChatModel(provider);
        String protocol = chatModel instanceof NativeOptionsChatModel nativeModel
                ? nativeModel.getProviderType() : "openai";

        ModelRequestAdapter adapter = requestAdapters.stream()
                .filter(item -> item.providerType().equalsIgnoreCase(protocol))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到模型请求适配器: " + protocol));

        Prompt prompt = adapter.buildPrompt(modelId, parameterMap, messages);
        Flux<AgentResponse> stream = chatModel.stream(prompt)
                .concatMap(response -> convertResponse(response).flux());
        return stream.switchIfEmpty(Flux.defer(() -> convertResponse(chatModel.call(prompt))
                .switchIfEmpty(Mono.error(new IllegalStateException("模型未返回有效回复")))
                .flux()));
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
        }
        else {
            return Mono.empty();
        }
        return Mono.just(responseBuilder.build());
    }
}
