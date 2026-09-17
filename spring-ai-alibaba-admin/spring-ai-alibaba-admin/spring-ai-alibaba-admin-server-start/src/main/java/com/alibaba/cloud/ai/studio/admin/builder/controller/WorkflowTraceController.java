package com.alibaba.cloud.ai.studio.admin.builder.controller;

import com.alibaba.cloud.ai.studio.admin.trace.dto.*;
import com.alibaba.cloud.ai.studio.admin.trace.service.WorkflowTraceQueryService;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;

@RestController
@RequiredArgsConstructor
@Tag(name = "workflow trace")
@RequestMapping("/console/v1/workflow-traces")
public class WorkflowTraceController {

    private final WorkflowTraceQueryService queryService;

    @GetMapping
    public Result<WorkflowTracePageResponse> list(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String invokeSource,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endTime) {
        return Result.success(queryService.page(
                current, size, appId, keyword, status, invokeSource, startTime, endTime));
    }

    @GetMapping("/overview")
    public Result<WorkflowTraceOverviewView> overview(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String invokeSource,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endTime) {
        return Result.success(queryService.overview(
                appId, keyword, status, invokeSource, startTime, endTime));
    }

    @GetMapping("/{traceId}")
    public Result<WorkflowTraceDetailResponse> detail(@PathVariable String traceId) {
        WorkflowTraceDetailResponse detail = queryService.detail(traceId);
        return detail == null ? Result.error(404, "workflow trace not found") : Result.success(detail);
    }
}
