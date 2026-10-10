package com.alibaba.cloud.ai.studio.application;

import com.alibaba.cloud.ai.studio.core.base.service.AgentService;
import com.alibaba.cloud.ai.studio.core.base.service.WorkflowService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.utils.LogUtils;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.infrastructure.persistence.OpenApiWorkflowTaskRepository;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentRequest;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentResponse;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.*;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** 应用服务：协调同步、流式、异步任务，不处理 HTTP/SSE 和 Redis 具体读写。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiCompletionService {
    private final AgentService agentService;
    private final WorkflowService workflowService;
    private final OpenApiWorkflowTaskRepository taskRepository;
    private final OpenApiAsyncResultAssembler resultAssembler;

    public AgentResponse chat(AgentRequest request) { return agentService.call(request); }
    public Flux<AgentResponse> streamChat(AgentRequest request) { return agentService.streamCall(Flux.just(request)); }
    public WorkflowResponse workflow(WorkflowRequest request) { return workflowService.call(request); }
    public Flux<WorkflowResponse> streamWorkflow(WorkflowRequest request) { return workflowService.streamCall(Flux.just(request)); }

    public Result<TaskRunResponse> startWorkflow(WorkflowRequest request) {
        RequestContext context = RequestContextHolder.getRequestContext();
        context.setStartTime(System.currentTimeMillis());
        return Result.success(workflowService.asyncCall(request));
    }

    public Result<Boolean> stopWorkflow(TaskStopRequest request) {
        RequestContext context = RequestContextHolder.getRequestContext();
        if (request == null || StringUtils.isBlank(request.getTaskId())) {
            return Result.error(context.getRequestId(), ErrorCode.MISSING_PARAMS.toError("taskId is null"));
        }
        return Result.success(workflowService.stop(request));
    }

    public Result<AsyncResultResponse> workflowResult(AsyncResultRequest request) {
        RequestContext context = RequestContextHolder.getRequestContext();
        context.setStartTime(System.currentTimeMillis());
        try {
            if (request == null || request.getTaskId() == null) {
                throw new BizException(ErrorCode.MISSING_PARAMS.toError("request or taskId is null"));
            }
            WorkflowContext workflowContext = taskRepository.find(context.getWorkspaceId(), request.getTaskId());
            if (workflowContext == null) {
                log.info("Async task not found: taskId={}, workspaceId={}, requestId={}",
                        request.getTaskId(), context.getWorkspaceId(), context.getRequestId());
                return Result.error(context.getRequestId(),
                        ErrorCode.WORKFLOW_CONFIG_INVALID.toError("taskId not exists"));
            }
            AsyncResultResponse response = resultAssembler.assemble(request.getTaskId(), workflowContext);
            LogUtils.monitor(context, "ChatController", "getAsyncResults", context.getStartTime(),
                    LogUtils.SUCCESS, request, "Task status: " + workflowContext.getTaskStatus());
            return Result.success(response);
        } catch (BizException e) {
            LogUtils.monitor(context, "ChatController", "getAsyncResults", context.getStartTime(),
                    LogUtils.FAIL, request, e.getError(), e);
            throw e;
        } catch (Exception e) {
            LogUtils.monitor(context, "ChatController", "getAsyncResults", context.getStartTime(),
                    LogUtils.FAIL, request, e.getMessage(), e);
            return Result.error(ErrorCode.WORKFLOW_DEBUG_GET_PROCESS_FAIL);
        }
    }
}
