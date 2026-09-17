package com.alibaba.cloud.ai.studio.admin.mapper;

import com.alibaba.cloud.ai.studio.admin.trace.dto.WorkflowSpanView;
import com.alibaba.cloud.ai.studio.admin.trace.dto.WorkflowTraceOverviewView;
import com.alibaba.cloud.ai.studio.admin.trace.dto.WorkflowTraceView;
import org.apache.ibatis.annotations.Param;
import java.time.OffsetDateTime;
import java.util.List;

public interface WorkflowTraceQueryMapper {

    List<WorkflowTraceView> selectTracePage(
            @Param("appId") String appId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("invokeSource") String invokeSource,
            @Param("startTime") OffsetDateTime startTime,
            @Param("endTime") OffsetDateTime endTime,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countTraces(
            @Param("appId") String appId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("invokeSource") String invokeSource,
            @Param("startTime") OffsetDateTime startTime,
            @Param("endTime") OffsetDateTime endTime);

    WorkflowTraceOverviewView selectOverview(
            @Param("appId") String appId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("invokeSource") String invokeSource,
            @Param("startTime") OffsetDateTime startTime,
            @Param("endTime") OffsetDateTime endTime);

    WorkflowTraceView selectTraceById(@Param("traceId") String traceId);

    List<WorkflowSpanView> selectSpansByTraceId(@Param("traceId") String traceId);
}
