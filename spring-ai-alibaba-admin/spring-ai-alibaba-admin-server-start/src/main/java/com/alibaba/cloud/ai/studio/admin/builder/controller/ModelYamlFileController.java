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

import com.alibaba.cloud.ai.studio.core.model.llm.ModelYamlFileService;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/console/v1/providers/{provider}/parameter-yamls")
public class ModelYamlFileController {
    private final ModelYamlFileService service;
    public ModelYamlFileController(ModelYamlFileService service) { this.service = service; }
    public record SaveRequest(String filename, String content, String version) { }
    private interface Action<T> { T run() throws IOException; }
    private <T> Result<T> call(Action<T> action) {
        try { return Result.success(action.run()); }
        catch (IllegalArgumentException | IOException error) {
            throw new BizException(ErrorCode.INVALID_PARAMS.toError("model_yaml", error.getMessage()));
        }
    }
    @GetMapping public Result<List<ModelYamlFileService.FileInfo>> list(@PathVariable("provider") String provider) {
        return call(() -> service.list(provider));
    }
    @GetMapping("/content") public Result<ModelYamlFileService.FileContent> read(@PathVariable("provider") String provider,
            @RequestParam("filename") String filename) { return call(() -> service.read(provider, filename)); }
    @PostMapping public Result<ModelYamlFileService.FileContent> create(@PathVariable("provider") String provider, @RequestBody SaveRequest body) {
        return call(() -> service.save(provider,body.filename(),body.content(),null,true));
    }
    @PutMapping public Result<ModelYamlFileService.FileContent> update(@PathVariable("provider") String provider, @RequestBody SaveRequest body) {
        return call(() -> service.save(provider,body.filename(),body.content(),body.version(),false));
    }
    @DeleteMapping public Result<Boolean> delete(@PathVariable("provider") String provider,
            @RequestParam("filename") String filename, @RequestParam("version") String version) {
        return call(() -> service.delete(provider,filename,version));
    }
}
