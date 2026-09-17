package com.alibaba.cloud.ai.studio.admin.trace.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class WorkflowSpanView {
    private Long id;
    private String spanId;
    private String traceId;
    private String parentSpanId;
    private String spanName;
    private String spanKind;
    private Integer sequenceNo;
    private String nodeId;
    private String nodeName;
    private String nodeType;
    private Integer attemptNo;
    private String provider;
    private String modelId;
    private String modelName;
    private BigDecimal priceCost;
    private String status;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private Long durationMs;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private String inputData;
    private String outputData;
    private String spanData;
    private String errorCode;
    private String errorMessage;
    private String errorData;
    private OffsetDateTime gmtCreate;
}
