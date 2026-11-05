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

import com.alibaba.cloud.ai.studio.core.base.entity.ModelEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Resolves bundled definitions for newly added models, including resources inside JARs. */
@Slf4j
@Service
public class ClasspathModelYamlService {

    private final ResourcePatternResolver resolver;

    public ClasspathModelYamlService() {
        this(new PathMatchingResourcePatternResolver());
    }

    ClasspathModelYamlService(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    /** Returns false without changing the entity when no valid matching YAML exists. */
    public boolean configure(ModelEntity entity, String providerProtocol) {
        if (!"llm".equalsIgnoreCase(entity.getType())) return false;
        String protocol = ModelProviderConfig.normalize(providerProtocol);
        String provider = Objects.toString(entity.getProvider(), "").toLowerCase(Locale.ROOT);
        List<Resource> resources = new ArrayList<>();
        try {
            resources.addAll(List.of(resolver.getResources("classpath*:model/**/*.yaml")));
            resources.addAll(List.of(resolver.getResources("classpath*:model/**/*.yml")));
        }
        catch (IOException error) {
            log.warn("Cannot scan bundled model YAML; using existing parameter configuration", error);
            return false;
        }
        resources.sort(Comparator.comparing(Resource::getDescription));
        ModelYamlParser.Definition match = null;
        int bestPriority = -1;
        String duplicate = null;
        for (Resource resource : resources) {
            ModelYamlParser.Parsed parsed;
            int priority;
            try {
                String url = resource.getURL().toExternalForm();
                int root = url.lastIndexOf("/model/");
                if (root < 0) continue;
                String relative = url.substring(root + "/model/".length());
                int slash = relative.indexOf('/');
                String scope = slash < 0 ? "" : relative.substring(0, slash).toLowerCase(Locale.ROOT);
                priority = scope.isEmpty() ? 0 : scope.equals(provider) ? 2 : scope.equals(protocol) ? 1 : -1;
                if (priority < 0) continue;
                byte[] bytes;
                try (var input = resource.getInputStream()) {
                    bytes = input.readNBytes(ModelYamlParser.MAX_FILE_BYTES + 1);
                }
                parsed = ModelYamlParser.parse(List.of(new ModelYamlParser.Source(relative, bytes)));
            }
            catch (IOException | IllegalArgumentException error) {
                log.warn("Ignoring invalid bundled model YAML {}: {}", resource.getDescription(), error.getMessage());
                continue;
            }
            for (var definition : parsed.models()) {
                if (!entity.getModelId().equals(definition.modelId())) continue;
                Object declaredProvider = definition.document().get("provider");
                if (declaredProvider != null && !provider.equalsIgnoreCase(String.valueOf(declaredProvider))
                        && !protocol.equalsIgnoreCase(String.valueOf(declaredProvider))) continue;
                if (priority < bestPriority) continue;
                if (priority == bestPriority) {
                    duplicate = match.filename() + ", " + definition.filename();
                    continue;
                }
                duplicate = null;
                match = definition;
                bestPriority = priority;
            }
        }
        if (match == null) return false;
        if (duplicate != null) throw new IllegalArgumentException("同一供应商和模型存在重复 YAML: " + duplicate);
        ModelYamlImportService.applyDefinition(entity, match, entity.getProvider(), protocol);
        // Keep the existing YAML marker so refresh, workflow validation and front-end filtering work unchanged.
        log.info("Loaded bundled parameter YAML: provider={}, model={}, file={}", entity.getProvider(), entity.getModelId(), match.filename());
        return true;
    }
}
