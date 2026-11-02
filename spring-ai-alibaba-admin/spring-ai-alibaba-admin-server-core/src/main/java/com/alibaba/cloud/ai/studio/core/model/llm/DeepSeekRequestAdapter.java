package com.alibaba.cloud.ai.studio.core.model.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** DeepSeek's native Spring AI options mapping. */
@Component
public class DeepSeekRequestAdapter implements ModelRequestAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String providerType() {
        return "deepseek";
    }

    @Override
    public Prompt buildPrompt(String modelId, Map<String, Object> raw, List<Message> messages) {
        Map<String, Object> params = ModelParameterValues.safeParameters(raw);
        DeepSeekChatOptions options = DeepSeekChatOptions.builder().model(modelId)
                .temperature(value(params, "temperature", "temperate"))
                .maxTokens(integer(params, "max_tokens"))
                .topP(decimal(params, "top_p"))
                .presencePenalty(decimal(params, "presence_penalty"))
                .frequencyPenalty(decimal(params, "frequency_penalty"))
                .stop(ModelParameterValues.strings(params.get("stop")))
                .build();

        Object responseFormat = params.get("response_format");
        if (responseFormat instanceof Map<?, ?> format
                && "json_object".equalsIgnoreCase(String.valueOf(format.get("type")))) {
            options.setResponseFormat(JSON.convertValue(responseFormat,
                    org.springframework.ai.deepseek.api.ResponseFormat.class));
        }
        else if ("json_object".equalsIgnoreCase(String.valueOf(responseFormat))) {
            options.setResponseFormat(JSON.convertValue(Map.of("type", "json_object"),
                    org.springframework.ai.deepseek.api.ResponseFormat.class));
        }

        return new Prompt(messages, options);
    }

    private Double decimal(Map<String, Object> params, String key) {
        Number value = ModelParameterValues.number(params, key);
        return value == null ? null : value.doubleValue();
    }

    private Integer integer(Map<String, Object> params, String key) {
        Number value = ModelParameterValues.number(params, key);
        return value == null ? null : value.intValue();
    }

    private Double value(Map<String, Object> params, String first, String fallback) {
        Number value = ModelParameterValues.number(params, first);
        if (value == null) value = ModelParameterValues.number(params, fallback);
        return value == null ? null : value.doubleValue();
    }
}
