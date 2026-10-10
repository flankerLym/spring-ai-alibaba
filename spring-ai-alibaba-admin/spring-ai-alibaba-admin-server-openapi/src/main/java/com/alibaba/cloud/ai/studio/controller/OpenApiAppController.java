package com.alibaba.cloud.ai.studio.controller;

import com.alibaba.cloud.ai.studio.application.OpenApiAppQueryService;
import com.alibaba.cloud.ai.studio.openapi.dto.app.AppDetailQuery;
import com.alibaba.cloud.ai.studio.openapi.dto.app.AppPageResponse;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.openapi.OpenApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API adapter: routes, HTTP errors and response envelope only. */
@RestController
@RequiredArgsConstructor
@Tag(name = "openapi-apps")
@RequestMapping("/api/v1/apps")
public class OpenApiAppController {

    private final OpenApiAppQueryService queryService;

    @PostMapping("")
    @Operation(summary = "Query application details by filter conditions with pagination")
    public OpenApiResult<AppPageResponse> queryAppDetails(@RequestBody AppDetailQuery filter) {
        AppPageResponse page = queryService.queryAppDetails(filter);
        return OpenApiResult.success(RequestContextHolder.getRequestContext().getRequestId(), page);
    }

}
