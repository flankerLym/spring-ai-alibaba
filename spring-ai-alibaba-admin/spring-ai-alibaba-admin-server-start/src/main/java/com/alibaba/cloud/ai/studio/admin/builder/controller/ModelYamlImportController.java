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
package com.alibaba.cloud.ai.studio.admin.builder.controller;

import com.alibaba.cloud.ai.studio.core.model.llm.ModelYamlImportService;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelYamlParser;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/console/v1/providers")
public class ModelYamlImportController {
    private final ModelYamlImportService service;
    public ModelYamlImportController(ModelYamlImportService service) { this.service = service; }

    @PostMapping(value = "/{provider}/models/import-yaml", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> importYaml(@PathVariable("provider") String provider, @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite,
            @RequestParam(value = "dry_run", defaultValue = "false") boolean dryRun) {
        try {
            if (files.length == 0 || files.length > ModelYamlParser.MAX_MODELS) throw new IllegalArgumentException("文件数量必须为 1 至 200");
            List<ModelYamlParser.Source> sources = new ArrayList<>();
            long total = 0;
            for (MultipartFile file : files) {
                total += file.getSize();
                if (total > ModelYamlParser.MAX_BATCH_BYTES) throw new IllegalArgumentException("上传总量不能超过 10 MiB");
                sources.add(new ModelYamlParser.Source(Objects.toString(file.getOriginalFilename(), "model.yaml"), file.getBytes()));
            }
            return Result.success(service.importModels(provider, sources, overwrite, dryRun));
        }
        catch (IllegalArgumentException | IOException error) {
            throw new BizException(ErrorCode.INVALID_PARAMS.toError("model_yaml", error.getMessage()));
        }
    }
}
