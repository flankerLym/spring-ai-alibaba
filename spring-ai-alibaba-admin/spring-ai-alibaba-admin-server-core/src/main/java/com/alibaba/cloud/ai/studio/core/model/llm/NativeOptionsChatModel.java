/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.core.model.llm;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import java.util.HashMap;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import java.util.ArrayList;

/**
 * Converts shared options into native vendor options. Model-specific native
 * options assembled from the persisted workflow snapshot are passed through
 * without any local model-name heuristics or guessed endpoint routing.
 */
public final class NativeOptionsChatModel implements ChatModel {
    private final String provider;
    private final ChatModel delegate;
    private static final ObjectMapper JSON = new ObjectMapper();

    public NativeOptionsChatModel(String provider, ChatModel delegate) {
        this.provider = provider;
        this.delegate = delegate;
    }

    @Override public ChatResponse call(Prompt prompt) { return normalize(delegate.call(prepare(prompt))); }
    @Override public Flux<ChatResponse> stream(Prompt prompt) { return delegate.stream(prepare(prompt)).map(this::normalize); }
    @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }
    public boolean usesProvider(String code) { return provider.equalsIgnoreCase(code); }

    /**
     * The forked DashScopeChatModel has a known 1.1.2 option-copy issue: its
     * buildRequestPrompt() copies ToolCallingChatOptions fields and drops
     * DashScope-specific multiModel before merging defaults. The ModelFactory
     * creates a fresh model for each workflow invocation, so setting its native
     * default options and using a prompt without runtime options preserves the
     * selected workflow protocol with no cross-request shared state.
     */
    private Prompt prepare(Prompt prompt) {
        Prompt adapted = adapt(prompt);
        if ("dashscope".equals(provider) && delegate instanceof DashScopeChatModel dashscope
                && adapted.getOptions() instanceof DashScopeChatOptions nativeOptions) {
            dashscope.setDashScopeChatOptions(nativeOptions);
            return new Prompt(adapted.getInstructions(), null);
        }
        return adapted;
    }

    private ChatResponse normalize(ChatResponse response) {
        if (!"deepseek".equals(provider)) return response;
        var generations = response.getResults().stream().map(generation -> {
            if (!(generation.getOutput() instanceof DeepSeekAssistantMessage message)
                    || message.getReasoningContent() == null) return generation;
            var metadata = new HashMap<String, Object>(message.getMetadata());
            metadata.put("reasoningContent", message.getReasoningContent());
            var output = new DeepSeekAssistantMessage.Builder().content(message.getText())
                    .reasoningContent(message.getReasoningContent()).properties(metadata)
                    .toolCalls(message.getToolCalls()).build();
            return new Generation(output, generation.getMetadata());
        }).toList();
        return ChatResponse.builder().from(response).generations(generations).build();
    }

    Prompt adapt(Prompt prompt) {
        ChatOptions source = prompt.getOptions();
        if (source == null || "doubao".equals(provider)
                || source instanceof DashScopeChatOptions || source instanceof DeepSeekChatOptions) {
            return prompt;
        }
        ToolCallingChatOptions target;
        if ("dashscope".equals(provider)) {
            // Legacy workflows without a protocol snapshot retain their prior
            // default behavior; new workflows use native options from the JSON.
            DashScopeChatOptions options = DashScopeChatOptions.builder().withModel(source.getModel())
                    .withTemperature(source.getTemperature()).withMaxToken(source.getMaxTokens())
                    .withTopP(source.getTopP()).withTopK(source.getTopK())
                    .withStream(true).withIncrementalOutput(true).build();
            if (source.getStopSequences() != null) options.setStop(new ArrayList<>(source.getStopSequences()));
            if (source instanceof OpenAiChatOptions legacy) {
                if (legacy.getSeed() != null) options.setSeed(Math.toIntExact(legacy.getSeed()));
                if (legacy.getResponseFormat() != null) options.setResponseFormat(
                        JSON.convertValue(legacy.getResponseFormat(), DashScopeResponseFormat.class));
                if (legacy.getPresencePenalty() != null && legacy.getPresencePenalty() > 0) {
                    options.setRepetitionPenalty(legacy.getPresencePenalty());
                }
            }
            target = options;
        }
        else {
            DeepSeekChatOptions options = DeepSeekChatOptions.builder().model(source.getModel())
                    .temperature(source.getTemperature()).maxTokens(source.getMaxTokens())
                    .topP(source.getTopP()).stop(source.getStopSequences())
                    .frequencyPenalty(source.getFrequencyPenalty()).presencePenalty(source.getPresencePenalty()).build();
            if (source instanceof OpenAiChatOptions legacy && legacy.getResponseFormat() != null) {
                options.setResponseFormat(JSON.convertValue(legacy.getResponseFormat(),
                        org.springframework.ai.deepseek.api.ResponseFormat.class));
            }
            target = options;
        }
        if (source instanceof ToolCallingChatOptions tools) {
            target.setToolCallbacks(tools.getToolCallbacks());
            target.setToolNames(tools.getToolNames());
            target.setToolContext(tools.getToolContext());
            target.setInternalToolExecutionEnabled(tools.getInternalToolExecutionEnabled());
        }
        return new Prompt(prompt.getInstructions(), target);
    }
}
