package com.alibaba.cloud.ai.studio.controller;

import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.alibaba.cloud.ai.studio.runtime.utils.ExceptionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Keeps errors on the Dify completion route protocol-compatible. */
@RestControllerAdvice(assignableTypes = {DifyChatController.class})
public class DifyChatExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidJson(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(
                DifyChatProtocolConverter.errorResponse(400, "invalid_param", "Request body must be valid JSON"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception exception) {
        Error error = exception instanceof BizException businessException
                ? businessException.getError()
                : ExceptionUtils.convertError(exception);
        int status = error == null || error.getStatusCode() == null ? 500 : error.getStatusCode();
        String code = error == null ? "internal_server_error"
                : DifyChatProtocolConverter.difyErrorCode(error.getCode());
        String message = error == null ? "Internal server error"
                : StringUtils.defaultIfBlank(error.getMessage(), "Request failed");
        return ResponseEntity.status(status)
                .body(DifyChatProtocolConverter.errorResponse(status, code, message));
    }
}
