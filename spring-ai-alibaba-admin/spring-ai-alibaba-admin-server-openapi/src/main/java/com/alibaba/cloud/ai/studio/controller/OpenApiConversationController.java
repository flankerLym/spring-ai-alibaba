package com.alibaba.cloud.ai.studio.controller;

import com.alibaba.cloud.ai.studio.application.OpenApiConversationQueryService;
import com.alibaba.cloud.ai.studio.application.OpenApiConversationQueryService.*;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.openapi.OpenApiResult;
import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/** API adapter: all data access and filtering live in the application query service. */
@RestController
@RequiredArgsConstructor
@Tag(name = "openapi-conversations")
@RequestMapping("/api/v1/conversations")
public class OpenApiConversationController {
    private final OpenApiConversationQueryService queryService;

    @PostMapping("/query")
    @Operation(summary = "Query conversations with pagination and filters")
    public OpenApiResult<PagingList<ConversationView>> query(@RequestBody ConversationQuery filter) {
        PagingList<ConversationView> page = queryService.query(filter);
        return OpenApiResult.success(RequestContextHolder.getRequestContext().getRequestId(), page);
    }

    @PostMapping("/messages/query")
    @Operation(summary = "Retrieve conversation details and paginated message history")
    public OpenApiResult<ConversationDetail> messages(@RequestBody MessageQuery filter) {
        ConversationDetail detail = queryService.messages(filter);
        return OpenApiResult.success(RequestContextHolder.getRequestContext().getRequestId(), detail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenApiResult<Void>> handleException(Exception ex) {
        RequestContext context = RequestContextHolder.getRequestContext();
        Error error = ex instanceof BizException biz ? biz.getError()
                : ex instanceof HttpMessageNotReadableException ? ErrorCode.INVALID_JSON.toError()
                : ErrorCode.SYSTEM_ERROR.toError();
        return ResponseEntity.status(error.getStatusCode())
                .body(OpenApiResult.error(context == null ? "" : context.getRequestId(), error));
    }
}
