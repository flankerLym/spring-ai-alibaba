package com.alibaba.cloud.ai.studio.core.workflow.trace.reporter;

import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowSpanContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Lightweight KV reporter for processor code that occasionally needs custom trace data.
 * NODE spans are bound only for the node execution thread. Reactive model callbacks do
 * not rely on this ThreadLocal; ModelCallTraceAspect captures their span explicitly.
 */
public final class SpanReporter {

    private static final ThreadLocal<Deque<WorkflowSpanContext>> SPAN_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private SpanReporter() {
    }

    public static void push(WorkflowSpanContext span) {
        if (span != null) {
            SPAN_STACK.get().push(span);
        }
    }

    public static WorkflowSpanContext current() {
        Deque<WorkflowSpanContext> stack = SPAN_STACK.get();
        if (stack.isEmpty()) {
            SPAN_STACK.remove();
            return null;
        }
        return stack.peek();
    }

    public static void put(String key, Object value) {
        WorkflowSpanContext span = current();
        if (span != null && key != null && value != null) {
            span.getAttributes().put(key, value);
        }
    }

    public static void put(WorkflowSpanContext span, String key, Object value) {
        if (span != null && key != null && value != null) {
            span.getAttributes().put(key, value);
        }
    }

    public static void putAll(Map<String, Object> values) {
        WorkflowSpanContext span = current();
        if (span != null && values != null) {
            values.forEach((key, value) -> {
                if (key != null && value != null) {
                    span.getAttributes().put(key, value);
                }
            });
        }
    }

    public static void pop(WorkflowSpanContext span) {
        Deque<WorkflowSpanContext> stack = SPAN_STACK.get();
        if (!stack.isEmpty()) {
            if (stack.peek() == span) {
                stack.pop();
            }
            else {
                stack.remove(span);
            }
        }
        if (stack.isEmpty()) {
            SPAN_STACK.remove();
        }
    }

    public static void clear() {
        SPAN_STACK.remove();
    }
}
