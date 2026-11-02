package com.alibaba.cloud.ai.studio.core.model.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

/**
 * Converts workflow parameters into the request options of one provider client.
 * Implementations are deliberately isolated from one another.
 */
public interface ModelRequestAdapter {

    String providerType();

    Prompt buildPrompt(String modelId, Map<String, Object> parameters,
                       List<Message> messages);
}
