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
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Shared OpenAI-compatible provider defaults. Existing saved credentials take precedence. */
@Component
public class ModelProviderConfig {

    public static final List<String> PROVIDERS = List.of("openai", "deepseek", "dashscope", "doubao");

    private final Environment environment;

    public ModelProviderConfig(Environment environment) {
        this.environment = environment;
    }

    public static String normalize(String provider) {
        String value = provider == null ? "openai" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "deepseek" -> "deepseek";
            case "dashscope", "tongyi", "qwen" -> "dashscope";
            case "doubao", "bytedance" -> "doubao";
            default -> "openai"; // Legacy custom provider IDs still use OpenAI-compatible transport.
        };
    }

    public static String defaultEndpoint(String provider) {
        return switch (normalize(provider)) {
            case "deepseek" -> "https://api.deepseek.com";
            case "dashscope" -> "https://dashscope.aliyuncs.com/compatible-mode";
            case "doubao" -> "https://ark.cn-beijing.volces.com/api/v3";
            default -> "https://api.openai.com";
        };
    }

    public ModelCredential resolve(String provider, ModelCredential saved) {
        String type = normalize(provider);
        // A fully configured existing provider must not depend on an optional template.
        Properties defaults = saved != null && hasText(saved.getEndpoint()) && hasText(saved.getApiKey())
                ? new Properties() : load(type);
        ModelCredential result = new ModelCredential();
        result.setApiKey(saved != null && hasText(saved.getApiKey()) ? saved.getApiKey()
                : property(defaults, "apiKey", "api-key", "api_key"));
        result.setEndpoint(saved != null && hasText(saved.getEndpoint()) ? saved.getEndpoint()
                : property(defaults, "baseUrl", "base-url", "endpoint"));
        if (!hasText(result.getEndpoint())) {
            result.setEndpoint(defaultEndpoint(type));
        }
        result.setCompletionsPath(saved == null ? null : saved.getCompletionsPath());
        result.setEmbeddingsPath(saved == null ? null : saved.getEmbeddingsPath());
        if (!hasText(result.getCompletionsPath())) {
            result.setCompletionsPath(property(defaults, "completionsPath", "completions-path", "completions_path"));
        }
        if (!hasText(result.getEmbeddingsPath())) {
            result.setEmbeddingsPath(property(defaults, "embeddingsPath", "embeddings-path", "embeddings_path"));
        }
        return route(result);
    }

    public String defaultModelName(String provider) {
        return property(load(normalize(provider)), "modelName", "model");
    }

    /** Treat versioned roots and full completion URLs consistently for all compatible providers. */
    public static ModelCredential route(ModelCredential credential) {
        ModelCredential result = new ModelCredential();
        result.setApiKey(credential.getApiKey());
        String endpoint = credential.getEndpoint().trim().replaceAll("/+$", "");
        if (endpoint.equals("https://ark.cn-beijing.volces.com")) {
            endpoint += "/api/v3";
        }
        else if (endpoint.equals("https://dashscope.aliyuncs.com")) {
            endpoint += "/compatible-mode";
        }
        String path = credential.getCompletionsPath();
        if (!hasText(path)) {
            if (endpoint.endsWith("/chat/completions")) {
                endpoint = endpoint.substring(0, endpoint.length() - "/chat/completions".length());
                path = "/chat/completions";
            }
            else if (endpoint.matches(".*/v[0-9]+$")) {
                path = "/chat/completions";
            }
            else {
                path = "/v1/chat/completions";
            }
        }
        result.setEndpoint(endpoint);
        result.setCompletionsPath(path);
        result.setEmbeddingsPath(hasText(credential.getEmbeddingsPath()) ? credential.getEmbeddingsPath()
                : endpoint.matches(".*/v[0-9]+$") ? "/embeddings" : "/v1/embeddings");
        return result;
    }

    private Properties load(String provider) {
        String configuredDir = environment.getProperty("spring.ai.studio.model-provider.config-directory");
        String mainFile = environment.getProperty("MODEL_CONFIG_FILE");
        Path dir = hasText(configuredDir) ? Paths.get(configuredDir)
                : hasText(mainFile) ? Paths.get(mainFile).toAbsolutePath().getParent() : Paths.get(".");
        Path file = dir.resolve("model-config-" + provider + ".yml");
        if (!Files.isRegularFile(file)) {
            file = dir.resolve("model-config-" + provider + ".yaml");
        }
        if (!Files.isRegularFile(file)) {
            return new Properties();
        }
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new FileSystemResource(file));
        Properties properties = loader.getObject();
        return properties == null ? new Properties() : properties;
    }

    private String property(Properties properties, String... names) {
        for (String prefix : List.of("", "models[0].")) {
            for (String name : names) {
                String value = properties.getProperty(prefix + name);
                if (hasText(value)) {
                    return environment.resolvePlaceholders(value);
                }
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
