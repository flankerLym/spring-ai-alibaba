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
import org.springframework.core.io.FileSystemResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** External model YAML overrides bundled definitions for newly added models. */
@Slf4j
@Service
public class ClasspathModelYamlService {

    private final ResourcePatternResolver resolver;

    @Value("${saa.model.directory:./model}")
    private String directory = "./model";

    private record Candidate(String relative, Resource resource) { }


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
        ModelYamlParser.Definition match = find(externalResources(), entity.getModelId(), provider, protocol);
        String origin = "external";
        if (match == null) {
            match = find(bundledResources(), entity.getModelId(), provider, protocol);
            origin = "classpath";
        }
        if (match == null) return false;
        ModelYamlImportService.applyDefinition(entity, match, entity.getProvider(), protocol);
        log.info("Loaded model parameter YAML: provider={}, model={}, origin={}, file={}",
                entity.getProvider(), entity.getModelId(), origin, match.filename());
        return true;
    }

    private List<Candidate> externalResources() {
        List<Candidate> result = new ArrayList<>();
        if (directory == null || directory.isBlank()) return result;
        try {
            Path root = Path.of(directory.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) return result;
            try (var files = Files.walk(root)) {
                files.filter(Files::isRegularFile).forEach(path -> {
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    if (relative.toLowerCase(Locale.ROOT).matches(".*\\.ya?ml$"))
                        result.add(new Candidate(relative, new FileSystemResource(path)));
                });
            }
        }
        catch (IOException | RuntimeException error) {
            log.warn("Cannot scan external model YAML directory {}; falling back to bundled YAML", directory, error);
            result.clear();
        }
        return result;
    }

    private List<Candidate> bundledResources() {
        List<Candidate> result = new ArrayList<>();
        try {
            for (String pattern : List.of("classpath*:model/**/*.yaml", "classpath*:model/**/*.yml")) {
                for (Resource resource : resolver.getResources(pattern)) {
                    String url = resource.getURL().toExternalForm();
                    int root = url.lastIndexOf("/model/");
                    if (root >= 0) result.add(new Candidate(url.substring(root + "/model/".length()), resource));
                }
            }
        }
        catch (IOException error) {
            log.warn("Cannot scan bundled model YAML; using existing parameter configuration", error);
        }
        return result;
    }

    private ModelYamlParser.Definition find(List<Candidate> resources, String modelId, String provider, String protocol) {
        resources.sort(Comparator.comparing(Candidate::relative));
        ModelYamlParser.Definition match = null;
        int bestPriority = -1;
        String duplicate = null;
        for (Candidate candidate : resources) {
            Resource resource = candidate.resource();
            ModelYamlParser.Parsed parsed;
            int priority;
            try {
                String relative = candidate.relative();
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
                log.warn("Ignoring invalid model YAML {}: {}", resource.getDescription(), error.getMessage());
                continue;
            }
            for (var definition : parsed.models()) {
                if (!Objects.equals(modelId, definition.modelId())) continue;
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
        if (duplicate != null) throw new IllegalArgumentException("同一供应商和模型存在重复 YAML: " + duplicate);
        return match;
    }
}
