/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.studio.admin.service.client;

import com.alibaba.cloud.ai.studio.admin.entity.ModelConfigDO;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelFactory;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelProviderConfig;
import com.alibaba.cloud.ai.studio.core.model.llm.domain.ModelCredential;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Registers the missing vendor in the existing factory registry. */
@Component
public class DoubaoChatClientFactory implements ChatClientFactory {
    private final ModelFactory modelFactory;
    private final ModelProviderConfig providerConfig;
    private final OpenAiChatClientFactory sharedOptions;

    public DoubaoChatClientFactory(ModelFactory modelFactory, ModelProviderConfig providerConfig,
            OpenAiChatClientFactory sharedOptions) {
        this.modelFactory = modelFactory;
        this.providerConfig = providerConfig;
        this.sharedOptions = sharedOptions;
    }

    @Override public String getSupportProvider() { return "doubao"; }

    @Override public ChatModel buildChatModel(ModelConfigDO config) {
        ModelCredential saved = new ModelCredential();
        saved.setApiKey(config.getApiKey());
        saved.setEndpoint(config.getBaseUrl());
        saved.setCompletionsPath(config.getCompletionsPath());
        return modelFactory.createNativeChatModel("doubao", providerConfig.resolve("doubao", saved));
    }

    @Override public ChatOptions buildChatOptions(ModelConfigDO config, Map<String, Object> parameters,
            Map<String, String> observationMetadata) {
        // Reuse common options; ArkChatModel builds the actual request through Ark SDK.
        return sharedOptions.buildChatOptions(config, parameters, observationMetadata);
    }
}
