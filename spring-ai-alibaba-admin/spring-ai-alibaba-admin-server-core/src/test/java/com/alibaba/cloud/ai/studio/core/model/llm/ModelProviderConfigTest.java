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
package com.alibaba.cloud.ai.studio.core.model.llm;

import com.alibaba.cloud.ai.studio.core.model.llm.domain.ModelCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelProviderConfigTest {

    @TempDir
    Path directory;

    private ModelProviderConfig config() {
        return new ModelProviderConfig(new MockEnvironment()
                .withProperty("spring.ai.studio.model-provider.config-directory", directory.toString()));
    }

    private ModelCredential credential(String endpoint, String key) {
        ModelCredential credential = new ModelCredential();
        credential.setEndpoint(endpoint);
        credential.setApiKey(key);
        return credential;
    }

    @Test
    void providerDefaultsAndLegacyAliases() {
        assertEquals("openai", ModelProviderConfig.normalize(null));
        assertEquals("openai", ModelProviderConfig.normalize(""));
        assertEquals("openai", ModelProviderConfig.normalize("custom-provider-id"));
        assertEquals("deepseek", ModelProviderConfig.normalize("DeepSeek"));
        assertEquals("dashscope", ModelProviderConfig.normalize("Tongyi"));
        assertEquals("doubao", ModelProviderConfig.normalize("ByteDance"));
    }

    @Test
    void fourProvidersUseCorrectCompletionUrls() {
        for (String provider : ModelProviderConfig.PROVIDERS) {
            ModelCredential actual = config().resolve(provider, credential(null, "test-key"));
            String expected = switch (provider) {
                case "deepseek" -> "https://api.deepseek.com/v1/chat/completions";
                case "dashscope" -> "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
                case "doubao" -> "https://ark.cn-beijing.volces.com/api/v3/chat/completions";
                default -> "https://api.openai.com/v1/chat/completions";
            };
            assertEquals(expected, actual.getEndpoint() + actual.getCompletionsPath());
        }
    }

    @Test
    void versionedAndFullUrlsDoNotDuplicateVersions() {
        for (String endpoint : List.of("https://proxy.example/v1", "https://proxy.example/v1/",
                "https://proxy.example/v1/chat/completions")) {
            ModelCredential actual = config().resolve("openai", credential(endpoint, "test-key"));
            assertEquals("https://proxy.example/v1/chat/completions",
                    actual.getEndpoint() + actual.getCompletionsPath());
        }
        ModelCredential ark = config().resolve("doubao", credential("https://proxy.example/api/v3/", "test-key"));
        assertEquals("https://proxy.example/api/v3/chat/completions", ark.getEndpoint() + ark.getCompletionsPath());
    }

    @Test
    void explicitlyConfiguredPathsArePreserved() {
        ModelCredential saved = credential("https://proxy.example/root/", "test-key");
        saved.setCompletionsPath("/custom/chat");
        saved.setEmbeddingsPath("/custom/embed");
        ModelCredential actual = config().resolve("deepseek", saved);
        assertEquals("https://proxy.example/root/custom/chat", actual.getEndpoint() + actual.getCompletionsPath());
        assertEquals("/custom/embed", actual.getEmbeddingsPath());
        assertEquals("https://proxy.example/root/", saved.getEndpoint());
    }

    @Test
    void providerYamlSuppliesMissingConnectionAndModel() throws Exception {
        Files.writeString(directory.resolve("model-config-deepseek.yaml"),
                "models:\n  - provider: deepseek\n    apiKey: test-file-key\n    baseUrl: https://proxy.example/v1\n    modelName: test-model\n");
        ModelCredential actual = config().resolve("deepseek", null);
        assertEquals("test-file-key", actual.getApiKey());
        assertEquals("https://proxy.example/v1/chat/completions", actual.getEndpoint() + actual.getCompletionsPath());
        assertEquals("test-model", config().defaultModelName("deepseek"));
    }

    @Test
    void savedCredentialsOverrideYamlAndInvalidOptionalTemplates() throws Exception {
        Files.writeString(directory.resolve("model-config-deepseek.yml"), "models: [invalid YAML");
        ModelCredential actual = config().resolve("deepseek", credential("https://existing.example/v1", "saved-key"));
        assertEquals("saved-key", actual.getApiKey());
        assertEquals("https://existing.example/v1/chat/completions", actual.getEndpoint() + actual.getCompletionsPath());
    }

    @Test
    void rootYamlAliasesAndPlaceholdersAreSupported() throws Exception {
        Files.writeString(directory.resolve("model-config-doubao.yml"),
                "provider: doubao\napi-key: ${TEST_KEY}\nbase-url: https://proxy.example/api/v3\nmodel: endpoint-id\n");
        ModelProviderConfig config = new ModelProviderConfig(new MockEnvironment()
                .withProperty("spring.ai.studio.model-provider.config-directory", directory.toString())
                .withProperty("TEST_KEY", "test-resolved-key"));
        assertEquals("test-resolved-key", config.resolve("doubao", null).getApiKey());
        assertEquals("endpoint-id", config.defaultModelName("doubao"));
    }
}
