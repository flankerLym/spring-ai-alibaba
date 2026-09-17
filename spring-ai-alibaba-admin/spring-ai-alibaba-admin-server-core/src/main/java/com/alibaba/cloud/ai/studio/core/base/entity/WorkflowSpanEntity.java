package com.alibaba.cloud.ai.studio.core.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("workflow_span")
public class WorkflowSpanEntity {

    @TableId(value = "id", type = IdType.AUTO)
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

    private Date startTime;
    private Date endTime;
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

    private Date gmtCreate;
}
