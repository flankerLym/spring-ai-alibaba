package com.alibaba.cloud.ai.studio.application;

import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.base.service.AgentService;
import com.alibaba.cloud.ai.studio.core.base.service.WorkflowService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.utils.LogUtils;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentRequest;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentResponse;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeResult;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.*;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants.WORKFLOW_TASK_CONTEXT_PREFIX;

/** Application facade for OpenAPI invocation; transport concerns live outside this class. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiCompletionService {
    private final AgentService agentService;
    private final WorkflowService workflowService;
    private final RedisManager redisManager;

    public AgentResponse chat(AgentRequest request) {
        return agentService.call(request);
    }

    public Flux<AgentResponse> streamChat(AgentRequest request) {
        return agentService.streamCall(Flux.just(request));
    }

    public WorkflowResponse workflow(WorkflowRequest request) {
        return workflowService.call(request);
    }

    public Flux<WorkflowResponse> streamWorkflow(WorkflowRequest request) {
        return workflowService.streamCall(Flux.just(request));
    }

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
            String cacheKey = WORKFLOW_TASK_CONTEXT_PREFIX + context.getWorkspaceId() + "_" + request.getTaskId();
            WorkflowContext wfContext = redisManager.get(cacheKey);
            if (wfContext == null) {
                log.info("Async task not found: taskId={}, workspaceId={}, requestId={}",
                        request.getTaskId(), context.getWorkspaceId(), context.getRequestId());
                return Result.error(context.getRequestId(),
                        ErrorCode.WORKFLOW_CONFIG_INVALID.toError("taskId not exists"));
            }
            AsyncResultResponse response = new AsyncResultResponse();
            response.setTaskId(request.getTaskId());
            response.setRequestId(wfContext.getRequestId());
            response.setConversationId(wfContext.getConversationId());
            response.setTaskStatus(wfContext.getTaskStatus());
            response.setErrorCode(wfContext.getErrorCode());
            response.setErrorInfo(wfContext.getErrorInfo());
            if (wfContext.getStartTime() > 0) {
                response.setTaskExecTime((System.currentTimeMillis() - wfContext.getStartTime()) + "ms");
            }
            CopyOnWriteArrayList<String> executeOrderList = wfContext.getExecuteOrderList();
            ConcurrentHashMap<String, NodeResult> nodeResultMap = wfContext.getNodeResultMap();
            List<AsyncResultResponse.Output> outputs = Lists.newArrayList();
            if (CollectionUtils.isNotEmpty(executeOrderList)) {
                executeOrderList.forEach(nodeId -> {
                    if (nodeResultMap != null) {
                        NodeResult nodeResult = nodeResultMap.get(nodeId);
                        log.info("getAsyncResults nodeType:{}", nodeResult.getNodeType());
                        if (!nodeResult.getNodeType().equals(NodeTypeEnum.OUTPUT.getCode())
                                && !nodeResult.getNodeType().equals(NodeTypeEnum.END.getCode())) {
                            return;
                        }
                        AsyncResultResponse.Output output = new AsyncResultResponse.Output();
                        output.setNodeId(nodeId);
                        output.setNodeName(nodeResult.getNodeName());
                        output.setNodeType(nodeResult.getNodeType());
                        output.setNodeStatus(nodeResult.getNodeStatus());
                        output.setContent(nodeResult.getOutput());
                        outputs.add(output);
                    }
                });
            }
            response.setOutputs(outputs);
            LogUtils.monitor(context, "ChatController", "getAsyncResults", context.getStartTime(),
                    LogUtils.SUCCESS, request, "Task status: " + wfContext.getTaskStatus());
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
