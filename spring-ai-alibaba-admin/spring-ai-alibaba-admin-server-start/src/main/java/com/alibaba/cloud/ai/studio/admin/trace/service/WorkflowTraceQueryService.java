package com.alibaba.cloud.ai.studio.admin.trace.service;

import com.alibaba.cloud.ai.studio.admin.mapper.WorkflowTraceQueryMapper;
import com.alibaba.cloud.ai.studio.admin.trace.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowTraceQueryService {

    private final WorkflowTraceQueryMapper queryMapper;

    public WorkflowTracePageResponse page(Integer current, Integer size, String appId,
            String keyword, String status, String invokeSource,
            OffsetDateTime startTime, OffsetDateTime endTime) {
        int page = current == null || current < 1 ? 1 : current;
        int pageSize = size == null || size < 1 ? 20 : Math.min(size, 100);
        int offset = (page - 1) * pageSize;
        List<WorkflowTraceView> records = queryMapper.selectTracePage(
                appId, keyword, status, invokeSource, startTime, endTime, pageSize, offset);
        long total = queryMapper.countTraces(
                appId, keyword, status, invokeSource, startTime, endTime);
        return new WorkflowTracePageResponse(records, total, page, pageSize);
    }

    public WorkflowTraceOverviewView overview(String appId, String keyword, String status,
            String invokeSource, OffsetDateTime startTime, OffsetDateTime endTime) {
        WorkflowTraceOverviewView value = queryMapper.selectOverview(
                appId, keyword, status, invokeSource, startTime, endTime);
        return value == null ? new WorkflowTraceOverviewView() : value;
    }

    public WorkflowTraceDetailResponse detail(String traceId) {
        WorkflowTraceView trace = queryMapper.selectTraceById(traceId);
        if (trace == null) return null;
        return new WorkflowTraceDetailResponse(trace, queryMapper.selectSpansByTraceId(traceId));
    }
}
