package com.alibaba.cloud.ai.studio.admin.trace.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
public class WorkflowTraceDetailResponse {
    private WorkflowTraceView trace;
    private List<WorkflowSpanView> spans;
}
