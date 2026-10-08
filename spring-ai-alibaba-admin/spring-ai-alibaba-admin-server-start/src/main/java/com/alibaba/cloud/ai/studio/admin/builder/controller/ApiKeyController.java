/*
 * Copyright 2025 the original author or authors.
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

import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.domain.BaseQuery;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import com.alibaba.cloud.ai.studio.runtime.domain.account.ApiKey;
import com.alibaba.cloud.ai.studio.core.base.service.ApiKeyService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

/** API Key management for the signed-in account. */
@RestController
@Tag(name = "apikey")
@RequestMapping("/console/v1/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping()
    public Result<String> createApiKey(@RequestBody ApiKey apiKey) {
        RequestContext context = RequestContextHolder.getRequestContext();
        validateEditableFields(apiKey);
        Long id = apiKeyService.createApiKey(apiKey);
        return Result.success(context.getRequestId(), String.valueOf(id));
    }

    @PutMapping("/{id}")
    public Result<String> updateApiKey(@PathVariable("id") Long id, @RequestBody ApiKey apiKey) {
        RequestContext context = RequestContextHolder.getRequestContext();
        if (Objects.isNull(id)) {
            throw new BizException(ErrorCode.MISSING_PARAMS.toError("id"));
        }
        validateEditableFields(apiKey);
        apiKey.setId(id);
        apiKeyService.updateApiKey(apiKey);
        return Result.success(context.getRequestId(), null);
    }

    private void validateEditableFields(ApiKey apiKey) {
        if (Objects.isNull(apiKey)) {
            throw new BizException(ErrorCode.MISSING_PARAMS.toError("apiKey"));
        }
        if (StringUtils.isBlank(apiKey.getDescription())) {
            throw new BizException(ErrorCode.MISSING_PARAMS.toError("description"));
        }
        if (apiKey.getCompanyName() != null && apiKey.getCompanyName().length() > 200) {
            throw new BizException(ErrorCode.INVALID_PARAMS
                .toError("companyName", "companyName must be 200 characters or fewer"));
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteApiKey(@PathVariable("id") Long id) {
        RequestContext context = RequestContextHolder.getRequestContext();
        if (Objects.isNull(id)) {
            throw new BizException(ErrorCode.MISSING_PARAMS.toError("id"));
        }
        apiKeyService.deleteApiKey(id);
        return Result.success(context.getRequestId(), null);
    }

    @GetMapping("/{id}")
    public Result<ApiKey> getApiKey(@PathVariable("id") Long id) {
        RequestContext context = RequestContextHolder.getRequestContext();
        if (Objects.isNull(id)) {
            throw new BizException(ErrorCode.MISSING_PARAMS.toError("id"));
        }
        return Result.success(context.getRequestId(), apiKeyService.getApiKey(id));
    }

    @GetMapping()
    public Result<PagingList<ApiKey>> listApiKeys(@ModelAttribute BaseQuery query) {
        RequestContext context = RequestContextHolder.getRequestContext();
        return Result.success(context.getRequestId(), apiKeyService.listApiKeys(query));
    }
}
