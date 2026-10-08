package com.alibaba.cloud.ai.studio.core.workflow.trace.context;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Data
public class WorkflowTraceContext {

    private String traceId;
    private String rootSpanId;

    private String taskId;
    private String requestId;

    private String appId;
    private String workflowVersion;
    private String conversationId;
    private String userId;
    /** API-key company name used only when persisting invoke_source. */
    private String apiKeyCompanyName;
    private String invokeSource;

    private String status;
    private String finishReason;

    private long startTime;
    private long endTime;
    private long durationMs;

    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private BigDecimal totalCost = BigDecimal.ZERO;

    private int spanCount;
    private int modelCallCount;

    private String inputData;
    private String outputData;
    private String traceData;
    private String errorData;
    private String errorCode;
    private String errorMessage;

    private final Queue<WorkflowSpanContext> spans = new ConcurrentLinkedQueue<>();
    private final AtomicInteger sequence = new AtomicInteger(0);
    private final AtomicInteger activeSpans = new AtomicInteger(0);
    private final AtomicInteger pendingExecutions = new AtomicInteger(0);
    private final AtomicBoolean finishRequested = new AtomicBoolean(false);
    private final AtomicBoolean persisted = new AtomicBoolean(false);
}
