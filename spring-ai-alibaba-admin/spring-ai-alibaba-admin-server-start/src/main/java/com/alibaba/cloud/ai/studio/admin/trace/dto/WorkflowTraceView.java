package com.alibaba.cloud.ai.studio.admin.trace.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class WorkflowTraceView {
    private Long id;
    private String traceId;
    private String rootSpanId;
    private String taskId;
    private String requestId;
    private String appId;
    private String workflowVersion;
    private String conversationId;
    private String invokeSource;
    private String status;
    private String finishReason;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private Long durationMs;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private BigDecimal totalCost;
    private Integer spanCount;
    private Integer modelCallCount;
    private String inputData;
    private String outputData;
    private String traceData;
    private String errorData;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime gmtCreate;
    private OffsetDateTime gmtModified;
}
