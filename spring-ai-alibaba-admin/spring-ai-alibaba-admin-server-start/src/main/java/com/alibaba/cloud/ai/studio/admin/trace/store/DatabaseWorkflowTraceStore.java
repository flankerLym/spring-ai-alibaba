package com.alibaba.cloud.ai.studio.admin.trace.store;

import com.alibaba.cloud.ai.studio.admin.mapper.WorkflowSpanMapper;
import com.alibaba.cloud.ai.studio.admin.mapper.WorkflowTraceMapper;
import com.alibaba.cloud.ai.studio.core.base.entity.WorkflowSpanEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.WorkflowTraceEntity;
import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowSpanContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowTraceContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.store.WorkflowTraceStore;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DatabaseWorkflowTraceStore implements WorkflowTraceStore {

    private final WorkflowTraceMapper workflowTraceMapper;
    private final WorkflowSpanMapper workflowSpanMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(WorkflowTraceContext trace) {
        WorkflowTraceEntity traceEntity = toTraceEntity(trace);
        workflowTraceMapper.insertTrace(traceEntity);

        List<WorkflowSpanEntity> spans = trace.getSpans()
                .stream()
                .sorted(Comparator.comparing(
                        WorkflowSpanContext::getSequenceNo,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(this::toSpanEntity)
                .toList();

        if (!spans.isEmpty()) {
            workflowSpanMapper.batchInsert(spans);
        }
    }

    private WorkflowTraceEntity toTraceEntity(WorkflowTraceContext trace) {
        WorkflowTraceEntity entity = new WorkflowTraceEntity();
        entity.setTraceId(trace.getTraceId());
        entity.setRootSpanId(trace.getRootSpanId());
        entity.setTaskId(trace.getTaskId());
        entity.setRequestId(trace.getRequestId());

        entity.setAppId(trace.getAppId());
        entity.setWorkflowVersion(trace.getWorkflowVersion());
        entity.setConversationId(trace.getConversationId());
        entity.setInvokeSource(trace.getInvokeSource());

        entity.setStatus(trace.getStatus());
        entity.setFinishReason(trace.getFinishReason());

        entity.setStartTime(new Date(trace.getStartTime()));
        entity.setEndTime(trace.getEndTime() <= 0 ? null : new Date(trace.getEndTime()));
        entity.setDurationMs(trace.getDurationMs());

        entity.setPromptTokens(trace.getPromptTokens());
        entity.setCompletionTokens(trace.getCompletionTokens());
        entity.setTotalTokens(trace.getTotalTokens());
        entity.setTotalCost(trace.getTotalCost());

        entity.setSpanCount(trace.getSpanCount());
        entity.setModelCallCount(trace.getModelCallCount());

        entity.setInputData(trace.getInputData());
        entity.setOutputData(trace.getOutputData());
        entity.setTraceData(trace.getTraceData());
        entity.setErrorData(trace.getErrorData());
        entity.setErrorCode(trace.getErrorCode());
        entity.setErrorMessage(trace.getErrorMessage());

        Date now = new Date();
        entity.setGmtCreate(now);
        entity.setGmtModified(now);
        return entity;
    }

    private WorkflowSpanEntity toSpanEntity(WorkflowSpanContext span) {
        WorkflowSpanEntity entity = new WorkflowSpanEntity();
        entity.setSpanId(span.getSpanId());
        entity.setTraceId(span.getTraceId());
        entity.setParentSpanId(span.getParentSpanId());

        entity.setSpanName(span.getSpanName());
        entity.setSpanKind(span.getSpanKind());
        entity.setSequenceNo(span.getSequenceNo());

        entity.setNodeId(span.getNodeId());
        entity.setNodeName(span.getNodeName());
        entity.setNodeType(span.getNodeType());

        entity.setAttemptNo(span.getAttemptNo());

        entity.setProvider(span.getProvider());
        entity.setModelId(span.getModelId());
        entity.setModelName(span.getModelName());
        entity.setPriceCost(span.getPriceCost());

        entity.setStatus(span.getStatus());

        entity.setStartTime(new Date(span.getStartTime()));
        entity.setEndTime(span.getEndTime() <= 0 ? null : new Date(span.getEndTime()));
        entity.setDurationMs(span.getDurationMs());

        entity.setPromptTokens(span.getPromptTokens());
        entity.setCompletionTokens(span.getCompletionTokens());
        entity.setTotalTokens(span.getTotalTokens());

        entity.setInputData(span.getInputData());
        entity.setOutputData(span.getOutputData());
        entity.setSpanData(JsonUtils.toJson(span.getAttributes()));

        entity.setErrorCode(span.getErrorCode());
        entity.setErrorMessage(span.getErrorMessage());
        entity.setErrorData(span.getErrorData());

        entity.setGmtCreate(new Date());
        return entity;
    }
}
