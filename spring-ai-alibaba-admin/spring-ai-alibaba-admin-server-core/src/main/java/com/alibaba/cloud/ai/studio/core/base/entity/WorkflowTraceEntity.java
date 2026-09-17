package com.alibaba.cloud.ai.studio.core.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("workflow_trace")
public class WorkflowTraceEntity {

    @TableId(value = "id", type = IdType.AUTO)
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

    private Date startTime;
    private Date endTime;
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

    private Date gmtCreate;
    private Date gmtModified;
}
