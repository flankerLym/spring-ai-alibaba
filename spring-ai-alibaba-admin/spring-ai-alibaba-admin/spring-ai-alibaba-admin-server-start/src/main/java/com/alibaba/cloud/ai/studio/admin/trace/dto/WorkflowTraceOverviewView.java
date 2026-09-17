package com.alibaba.cloud.ai.studio.admin.trace.dto;

import lombok.Data;

@Data
public class WorkflowTraceOverviewView {
    private Long traceCount;
    private Long successCount;
    private Long failCount;
    private Long totalTokens;
    private Long modelCallCount;
    private Long avgDurationMs;
}
