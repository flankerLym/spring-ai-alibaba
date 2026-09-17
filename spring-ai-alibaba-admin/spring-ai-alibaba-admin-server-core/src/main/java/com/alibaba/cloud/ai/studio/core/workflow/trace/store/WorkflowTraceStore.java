package com.alibaba.cloud.ai.studio.core.workflow.trace.store;

import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowTraceContext;

/**
 * Persistence boundary. The Admin start module provides the PostgreSQL implementation.
 */
public interface WorkflowTraceStore {

    void save(WorkflowTraceContext trace);
}
