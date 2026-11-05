/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.core.base.manager;

import com.alibaba.cloud.ai.studio.core.workflow.trace.annotation.WorkflowModelCall;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelFactory;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelWireParameters;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelParameterRuleService;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelRequestAdapter;
import com.alibaba.cloud.ai.studio.core.model.llm.NativeOptionsChatModel;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ParameterRule;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ModelExecuteManager {

    @Resource private ModelFactory modelFactory;
    @Resource private List<ModelRequestAdapter> requestAdapters;
    @Resource private ModelParameterRuleService modelParameterRuleService;

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

        // Never rely on metadata being embedded in the workflow JSON. Older Dify
        // imports may omit it from some LLM nodes even when they use the same model.
        Map<String, Object> effectiveParameters = new LinkedHashMap<>();
        if (parameterMap != null) {
            effectiveParameters.putAll(parameterMap);
        }
        if ("dashscope".equalsIgnoreCase(protocol)) {
            ensureDashScopeMetadata(provider, modelId, effectiveParameters);
        }

        ModelWireParameters wire = new ModelWireParameters(protocol, effectiveParameters);
        Prompt prompt = adapter.buildPrompt(modelId, effectiveParameters, messages);
        Flux<AgentResponse> stream = chatModel.stream(prompt)
                .concatMap(response -> convertResponse(response).flux());
        return stream.switchIfEmpty(Flux.defer(() -> convertResponse(wire.call(() -> chatModel.call(prompt)))
                .switchIfEmpty(Mono.error(new IllegalStateException("模型未返回有效回复")))
                .flux())).contextWrite(context -> context.put(ModelWireParameters.CONTEXT_KEY, wire));
    }

    /**
     * Resolve the endpoint from the registered model, not from a possibly
     * incomplete workflow snapshot. A matching, previously saved workflow
     * snapshot is retained as a fallback if the model registry is temporarily
     * unavailable. Unknown routes are rejected instead of silently selecting
     * DashScope's text-generation endpoint.
     */
    private void ensureDashScopeMetadata(String provider, String modelId, Map<String, Object> parameters) {
        Map<String, Object> workflowMetadata = toStringKeyMap(
                parameters.get(ModelParameterRuleService.META_KEY));
        Map<String, Object> registeredMetadata = null;

        try {
            List<ParameterRule> rules = modelParameterRuleService.getOrCreate(provider, modelId);
            if (rules != null) {
                for (ParameterRule rule : rules) {
                    if (rule != null && ModelParameterRuleService.META_KEY.equals(rule.getCode())) {
                        registeredMetadata = toStringKeyMap(rule.getDefaultValue());
                        break;
                    }
                }
            }
        }
        catch (Exception error) {
            log.warn("Unable to load DashScope model metadata: provider={}, model={}; "
                    + "will try the workflow snapshot", provider, modelId, error);
        }

        Map<String, Object> selectedMetadata = isUsableDashScopeMetadata(
                registeredMetadata, provider, modelId) ? registeredMetadata
                : isUsableDashScopeMetadata(workflowMetadata, provider, modelId) ? workflowMetadata : null;

        if (selectedMetadata == null) {
            throw new IllegalStateException("DashScope 模型 " + provider + "/" + modelId
                    + " 缺少有效的 endpoint_type 元数据，已阻止使用默认接口。"
                    + "请检查模型管理中的模型参数规则/接口类型后重试。");
        }

        parameters.put(ModelParameterRuleService.META_KEY, selectedMetadata);
    }

    private boolean isUsableDashScopeMetadata(
            Map<String, Object> metadata, String provider, String modelId) {
        if (metadata == null) {
            return false;
        }

        Object metadataProvider = metadata.get("provider");
        if (metadataProvider != null && !provider.equals(String.valueOf(metadataProvider))) {
            return false;
        }

        Object metadataModelId = metadata.get("model_id");
        if (metadataModelId != null && !modelId.equals(String.valueOf(metadataModelId))) {
            return false;
        }

        Object metadataProtocol = metadata.get("protocol");
        if (metadataProtocol != null
                && !"dashscope".equalsIgnoreCase(String.valueOf(metadataProtocol))) {
            return false;
        }

        String endpointType = String.valueOf(metadata.get("endpoint_type"));
        return "text-generation".equals(endpointType)
                || "multimodal-generation".equals(endpointType);
    }

    private Map<String, Object> toStringKeyMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
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
        }
        else {
            return Mono.empty();
        }
        return Mono.just(responseBuilder.build());
    }
}
