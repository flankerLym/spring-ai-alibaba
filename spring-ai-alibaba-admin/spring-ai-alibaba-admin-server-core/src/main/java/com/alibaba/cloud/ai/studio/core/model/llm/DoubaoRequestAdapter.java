package com.alibaba.cloud.ai.studio.core.model.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ark request mapping. OpenAiChatOptions is used only as an internal carrier
 * understood by ArkChatModel; it is not sent to an OpenAI client.
 */
@Component
public class DoubaoRequestAdapter implements ModelRequestAdapter {

    private static final Set<String> STANDARD = Set.of(
            "temperature", "temperate", "max_tokens", "top_p",
            "presence_penalty", "frequency_penalty", "seed", "stop",
            "response_format");

    @Override
    public String providerType() {
        return "doubao";
    }

    @Override
    public Prompt buildPrompt(String modelId, Map<String, Object> raw, List<Message> messages) {
        Map<String, Object> params = ModelParameterValues.safeParameters(raw);
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(resolveEndpoint(modelId, params));

        Number temperature = ModelParameterValues.number(params, "temperature");
        if (temperature == null) temperature = ModelParameterValues.number(params, "temperate");
        if (temperature != null) builder.temperature(temperature.doubleValue());

        Number maxTokens = ModelParameterValues.number(params, "max_tokens");
        if (maxTokens != null) builder.maxTokens(maxTokens.intValue());

        Number topP = ModelParameterValues.number(params, "top_p");
        if (topP != null) builder.topP(topP.doubleValue());

        Number presence = ModelParameterValues.number(params, "presence_penalty");
        if (presence != null) builder.presencePenalty(presence.doubleValue());

        Number frequency = ModelParameterValues.number(params, "frequency_penalty");
        if (frequency != null) builder.frequencyPenalty(frequency.doubleValue());

        List<String> stop = ModelParameterValues.strings(params.get("stop"));
        if (!stop.isEmpty()) builder.stop(stop);

        // Ark JSON mode is model/endpoint dependent. Do not forward an old
        // workflow's response_format unless capability discovery explicitly exists.
        Map<String, Object> extra = ModelParameterValues.extraParameters(params, STANDARD);
        if (!extra.isEmpty()) builder.extraBody(extra);
        return new Prompt(messages, builder.build());
    }

    private String resolveEndpoint(String modelId, Map<String, Object> params) {
        Object rawMetadata = params.get(ModelParameterRuleService.META_KEY);
        if (!(rawMetadata instanceof Map<?, ?> metadata)) return modelId;
        Object savedModelId = metadata.get("model_id");
        Object endpointId = metadata.get("endpoint_id");
        boolean sameModel = savedModelId == null || modelId.equals(String.valueOf(savedModelId));
        return sameModel && endpointId != null && !String.valueOf(endpointId).isBlank()
                ? String.valueOf(endpointId).trim() : modelId;
    }
}
