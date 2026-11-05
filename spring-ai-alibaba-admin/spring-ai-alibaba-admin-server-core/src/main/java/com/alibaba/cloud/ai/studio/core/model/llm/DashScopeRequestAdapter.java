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
        String endpointType = endpointType(params, modelId);

        String requestModel = modelId;
        if (params.get(ModelParameterRuleService.META_KEY) instanceof Map<?, ?> metadata
                && ModelYamlImportService.SOURCE.equals(metadata.get("parameter_source"))
                && List.of("qwen-plus-chat", "qwen-turbo-chat").contains(modelId))
            requestModel = modelId.substring(0, modelId.length() - "-chat".length());
        DashScopeChatOptions.DashscopeChatOptionsBuilder builder = DashScopeChatOptions.builder()
                .withModel(requestModel).withStream(true).withIncrementalOutput(true)
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

    private String endpointType(Map<String, Object> params, String modelId) {
        Object raw = params.get(ModelParameterRuleService.META_KEY);
        if (!(raw instanceof Map<?, ?> metadata)) {
            throw new IllegalStateException("DashScope 模型 " + modelId
                    + " 缺少 __model_metadata__，无法确定推理接口；已阻止使用默认接口。");
        }

        Object metadataModelId = metadata.get("model_id");
        if (metadataModelId != null && !modelId.equals(String.valueOf(metadataModelId))) {
            throw new IllegalStateException("DashScope 模型元数据与当前模型不匹配: " + modelId);
        }

        Object protocol = metadata.get("protocol");
        if (protocol != null && !"dashscope".equalsIgnoreCase(String.valueOf(protocol))) {
            throw new IllegalStateException("DashScope 模型协议元数据不匹配: " + modelId);
        }

        Object value = metadata.get("endpoint_type");
        String endpointType = value == null ? "" : String.valueOf(value);
        if (!"text-generation".equals(endpointType)
                && !"multimodal-generation".equals(endpointType)) {
            throw new IllegalStateException("DashScope 模型 " + modelId
                    + " 的 endpoint_type 尚未确认，当前值为: "
                    + (endpointType.isBlank() ? "missing" : endpointType)
                    + "；请检查模型管理中的模型参数规则后重试。");
        }
        return endpointType;
    }
}
