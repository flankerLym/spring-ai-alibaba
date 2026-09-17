package com.alibaba.cloud.ai.studio.admin.trace.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
public class WorkflowTracePageResponse {
    private List<WorkflowTraceView> records;
    private Long total;
    private Integer current;
    private Integer size;
}
