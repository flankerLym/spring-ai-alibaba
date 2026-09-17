package com.alibaba.cloud.ai.studio.core.workflow.trace.context;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Data
public class WorkflowSpanContext {

    private String spanId;
    private String traceId;
    private String parentSpanId;

    private String spanName;
    private String spanKind;
    private Integer sequenceNo;
    private Integer attemptNo = 0;

    private String nodeId;
    private String nodeName;
    private String nodeType;

    private String provider;
    private String modelId;
    private String modelName;

    private String status;

    private long startTime;
    private long endTime;
    private long durationMs;

    private long promptTokens;
    private long completionTokens;
    private long totalTokens;

    private String inputData;
    private String outputData;

    private String errorCode;
    private String errorMessage;
    private String errorData;

    private BigDecimal priceCost = BigDecimal.ZERO;

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * Ensures a Span is completed only once.
     */
    private final AtomicBoolean finished = new AtomicBoolean(false);

    /**
     * MODEL_CALL attempt sequence under one NODE span.
     */
    private final AtomicInteger childAttemptSequence = new AtomicInteger(0);
}
