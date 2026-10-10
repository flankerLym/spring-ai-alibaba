package com.alibaba.cloud.ai.studio.controller;

import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.openapi.OpenApiResult;
import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** HTTP 适配层统一错误响应；保持原分页和会话查询的响应格式。 */
@RestControllerAdvice(assignableTypes = {OpenApiAppController.class, OpenApiConversationController.class})
public class OpenApiExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenApiResult<Void>> handle(Exception exception) {
        RequestContext context = RequestContextHolder.getRequestContext();
        Error error;
        if (exception instanceof BizException bizException) {
            error = bizException.getError();
        } else if (exception instanceof HttpMessageNotReadableException) {
            error = ErrorCode.INVALID_JSON.toError();
        } else {
            error = ErrorCode.SYSTEM_ERROR.toError();
        }
        return ResponseEntity.status(error.getStatusCode())
                .body(OpenApiResult.error(context == null ? "" : context.getRequestId(), error));
    }
}
