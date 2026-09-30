/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.studio.core.workflow.trace.service;

import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowSpanContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.enums.SpanKind;
import com.alibaba.cloud.ai.studio.core.workflow.trace.reporter.SpanReporter;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/** Shares one real NODE span between the scheduler and the annotation advice. */
@Slf4j
public final class WorkflowNodeTraceScope implements AutoCloseable {

    private final WorkflowTraceManager manager;
    private final WorkflowContext context;
    private final WorkflowSpanContext span;

    private WorkflowNodeTraceScope(WorkflowTraceManager manager, WorkflowContext context,
            WorkflowSpanContext span) {
        this.manager = manager;
        this.context = context;
        this.span = span;
    }

    public static WorkflowNodeTraceScope open(WorkflowTraceManager manager, WorkflowContext context, Node node) {
        WorkflowSpanContext span = null;
        if (context != null && context.getTraceId() != null && node != null) {
            WorkflowSpanContext parent = SpanReporter.current();
            // The scheduler already owns this invocation. The advice must not create
            // another NODE or finish the scheduler's span before its call returns.
            boolean alreadyBound = parent != null && !parent.getFinished().get()
                    && SpanKind.NODE.name().equals(parent.getSpanKind())
                    && Objects.equals(context.getTraceId(), parent.getTraceId())
                    && Objects.equals(node.getId(), parent.getNodeId());
            if (!alreadyBound) {
                try {
                    span = manager.startNodeSpan(context, node, parent);
                    SpanReporter.push(span);
                }
                catch (Exception e) {
                    log.error("start workflow node span failed, traceId={}, nodeId={}",
                            context.getTraceId(), node.getId(), e);
                }
            }
        }
        return new WorkflowNodeTraceScope(manager, context, span);
    }

    public void recordError(Throwable error) {
        if (span != null) {
            try {
                manager.recordSpanError(span, error);
            }
            catch (Exception e) {
                log.warn("record workflow span error failed, spanId={}", span.getSpanId(), e);
            }
        }
    }

    @Override
    public void close() {
        if (span == null) {
            return;
        }
        try {
            manager.finishNodeSpan(context, span, context.getNodeResultMap().get(span.getNodeId()));
        }
        catch (Exception e) {
            log.error("finish workflow node span failed, traceId={}, spanId={}",
                    span.getTraceId(), span.getSpanId(), e);
        }
        finally {
            SpanReporter.pop(span);
        }
    }
}
