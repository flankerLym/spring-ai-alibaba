package com.alibaba.cloud.ai.studio.controller;

import com.alibaba.cloud.ai.studio.application.OpenApiAppQueryService;
import com.alibaba.cloud.ai.studio.application.OpenApiAppQueryService.AppDetailQuery;
import com.alibaba.cloud.ai.studio.application.OpenApiAppQueryService.AppPageResponse;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.openapi.OpenApiResult;
import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenApiResult<Void>> handleException(Exception exception) {
        RequestContext context = RequestContextHolder.getRequestContext();
        String requestId = context == null ? "" : context.getRequestId();
        Error error;
        if (exception instanceof BizException bizException) {
            error = bizException.getError();
        } else if (exception instanceof HttpMessageNotReadableException) {
            error = ErrorCode.INVALID_JSON.toError();
        } else {
            error = ErrorCode.SYSTEM_ERROR.toError();
        }
        return ResponseEntity.status(error.getStatusCode()).body(OpenApiResult.error(requestId, error));
    }
}
