package com.alibaba.cloud.ai.studio.core.model.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** OpenAI-compatible request mapping. Vendor-specific adapters must not depend on this class. */
@Component
public class OpenAiRequestAdapter implements ModelRequestAdapter {

    private static final Set<String> STANDARD = Set.of(
            "temperature", "temperate", "max_tokens", "top_p",
            "presence_penalty", "frequency_penalty", "seed", "stop",
            "response_format");

    @Override
    public String providerType() {
        return "openai";
    }

    @Override
    public Prompt buildPrompt(String modelId, Map<String, Object> raw, List<Message> messages) {
        Map<String, Object> params = ModelParameterValues.safeParameters(raw);
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(modelId);

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

        Number seed = ModelParameterValues.number(params, "seed");
        if (seed != null) builder.seed(seed.intValue());

        List<String> stop = ModelParameterValues.strings(params.get("stop"));
        if (!stop.isEmpty()) builder.stop(stop);

        Map<String, Object> extra = ModelParameterValues.extraParameters(params, STANDARD);
        Object format = ModelWireParameters.clean(params).get("response_format");
        if (format != null) extra.put("response_format", format);
        if (!extra.isEmpty()) builder.extraBody(extra);
        return new Prompt(messages, builder.build());
    }
}
