package com.alibaba.cloud.ai.studio.core.workflow.trace.registry;

import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowTraceContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class WorkflowTraceRegistry {

    private final ConcurrentHashMap<String, WorkflowTraceContext> traceMap = new ConcurrentHashMap<>();

    public void register(WorkflowTraceContext trace) {
        if (trace != null && trace.getTraceId() != null) {
            traceMap.put(trace.getTraceId(), trace);
        }
    }

    public WorkflowTraceContext get(String traceId) {
        return traceId == null ? null : traceMap.get(traceId);
    }

    public WorkflowTraceContext remove(String traceId) {
        return traceId == null ? null : traceMap.remove(traceId);
    }
}
