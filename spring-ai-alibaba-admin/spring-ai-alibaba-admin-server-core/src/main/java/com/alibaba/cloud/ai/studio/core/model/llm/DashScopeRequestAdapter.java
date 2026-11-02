package com.alibaba.cloud.ai.studio.core.model.llm;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** DashScope native request mapping, including its endpoint-specific generation API. */
@Component
public class DashScopeRequestAdapter implements ModelRequestAdapter {

    @Override
    public String providerType() {
        return "dashscope";
    }

    @Override
    public Prompt buildPrompt(String modelId, Map<String, Object> raw, List<Message> messages) {
        Map<String, Object> params = ModelParameterValues.safeParameters(raw);
        String endpointType = endpointType(params);
        if (!"text-generation".equals(endpointType) && !"multimodal-generation".equals(endpointType)) {
            throw new IllegalStateException(
                    "DashScope模型接口类型尚未确认，请刷新供应商模型元数据并重新保存工作流");
        }

        DashScopeChatOptions.DashscopeChatOptionsBuilder builder = DashScopeChatOptions.builder()
                .withModel(modelId).withStream(true).withIncrementalOutput(true)
                .withMultiModel("multimodal-generation".equals(endpointType));

        Number temperature = ModelParameterValues.number(params, "temperature");
        if (temperature == null) temperature = ModelParameterValues.number(params, "temperate");
        if (temperature != null) builder.withTemperature(temperature.doubleValue());

        Number maxTokens = ModelParameterValues.number(params, "max_tokens");
        if (maxTokens != null) builder.withMaxToken(maxTokens.intValue());

        Number topP = ModelParameterValues.number(params, "top_p");
        if (topP != null) builder.withTopP(topP.doubleValue());

        Number topK = ModelParameterValues.number(params, "top_k");
        if (topK != null) builder.withTopK(topK.intValue());

        Number seed = ModelParameterValues.number(params, "seed");
        if (seed != null) builder.withSeed(seed.intValue());

        Number repetition = ModelParameterValues.number(params, "repetition_penalty");
        if (repetition != null) builder.withRepetitionPenalty(repetition.doubleValue());

        if (params.get("enable_thinking") instanceof Boolean enabled) builder.withEnableThinking(enabled);
        if (params.get("enable_search") instanceof Boolean enabled) builder.withEnableSearch(enabled);

        DashScopeChatOptions options = builder.build();
        List<String> stop = ModelParameterValues.strings(params.get("stop"));
        if (!stop.isEmpty()) {
            options.setStop(new java.util.ArrayList<Object>(stop));
        }
        return new Prompt(messages, options);
    }

    private String endpointType(Map<String, Object> params) {
        Object raw = params.get(ModelParameterRuleService.META_KEY);
        // Workflows saved before protocol metadata existed used DashScope's
        // standard text generation route. Preserve that compatibility.
        if (!(raw instanceof Map<?, ?> metadata)) return "text-generation";
        Object value = metadata.get("endpoint_type");
        return value == null ? "text-generation" : String.valueOf(value);
    }
}
