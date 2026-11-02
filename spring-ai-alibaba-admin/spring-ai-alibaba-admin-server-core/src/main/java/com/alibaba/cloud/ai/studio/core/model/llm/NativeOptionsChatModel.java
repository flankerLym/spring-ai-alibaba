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
    public String getProviderType() { return provider; }

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
        // Provider-specific request adapters already create native options.
        // OpenAI and Ark use their own option carrier and must never be coerced.
        if (source == null || "openai".equals(provider) || "doubao".equals(provider)
                || source instanceof DashScopeChatOptions || source instanceof DeepSeekChatOptions) {
            return prompt;
        }
        if (!"dashscope".equals(provider)) return prompt;

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
        if (source instanceof ToolCallingChatOptions tools) {
            options.setToolCallbacks(tools.getToolCallbacks());
            options.setToolNames(tools.getToolNames());
            options.setToolContext(tools.getToolContext());
            options.setInternalToolExecutionEnabled(tools.getInternalToolExecutionEnabled());
        }
        return new Prompt(prompt.getInstructions(), options);
    }
}
