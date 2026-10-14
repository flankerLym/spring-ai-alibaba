package com.alibaba.cloud.ai.studio.admin.service.client;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.studio.admin.entity.ModelConfigDO;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelFactory;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelProviderConfig;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ModelCredential;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * @author Sunrisea
 */
@Component
public class DashScopeChatClientFactory implements ChatClientFactory{
    
    private static final String DASHSCOPE_PROVIDER = "dashscope";
    
    private final ObservationRegistry observationRegistry;
    
    private final ToolCallingManager toolCallingManager;
    
    private final ChatModelObservationConvention customChatModelObservationConvention;
    
    public DashScopeChatClientFactory(ObservationRegistry observationRegistry,
            ToolCallingManager toolCallingManager,
            ChatModelObservationConvention customChatModelObservationConvention) {
        this.observationRegistry = observationRegistry;
        this.toolCallingManager = toolCallingManager;
        this.customChatModelObservationConvention = customChatModelObservationConvention;
    }
    
    @Autowired
    private ModelFactory modelFactory;

    @Autowired
    private ModelProviderConfig providerConfig;

    @Override
    public String getSupportProvider() {
        return DASHSCOPE_PROVIDER;
    }
    
    @Override
    public ChatModel buildChatModel(ModelConfigDO modelConfig) {
        ModelCredential saved = new ModelCredential();
        saved.setApiKey(modelConfig.getApiKey());
        saved.setEndpoint(modelConfig.getBaseUrl());
        saved.setCompletionsPath(modelConfig.getCompletionsPath());
        return modelFactory.createNativeChatModel("dashscope", providerConfig.resolve("dashscope", saved));
    }
    
    @Override
    public ChatOptions buildChatOptions(ModelConfigDO modelConfig, Map<String, Object> userParameters,
            Map<String, String> observationMetadata) {
        DashScopeChatOptions.DashscopeChatOptionsBuilder builder = DashScopeChatOptions.builder();
        builder.withModel(modelConfig.getModelName());
        builder.withStream(true);
        builder.withIncrementalOutput(true);
        for (Map.Entry<String, Object> entry : userParameters.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (value == null) {
                continue;
            }
            switch (key) {
                case "temperature":
                    builder.withTemperature(((Number) value).doubleValue());
                    break;
                case "maxTokens":
                case "max_tokens":
                    builder.withMaxToken(((Number) value).intValue());
                    break;
                case "enable_search":
                case "enableSearch":
                    builder.withEnableSearch((Boolean) value);
                    break;
                case "seed":
                    builder.withSeed(((Number) value).intValue());
                    break;
                case "topP":
                case "top_p":
                    builder.withTopP(((Number) value).doubleValue());
                    break;
                case "topK":
                case "top_k":
                    builder.withTopK(((Number) value).intValue());
                    break;
                case "enableThinking":
                case "enable_thinking":
                    builder.withEnableThinking((Boolean) value);
                    break;
                case "repetitionPenalty":
                case "repetition_penalty":
                    builder.withRepetitionPenalty(((Number) value).doubleValue());
                default:
                    break;
            }
        }
        DashScopeChatOptions chatOptions = builder.build();
        DashScopeObservationMetadataChatOptions options = DashScopeObservationMetadataChatOptions.fromDashScopeOptions(chatOptions);
        if (observationMetadata != null) {
            options.setObservationMetadata(observationMetadata);
        }
        return options;
    }
}
