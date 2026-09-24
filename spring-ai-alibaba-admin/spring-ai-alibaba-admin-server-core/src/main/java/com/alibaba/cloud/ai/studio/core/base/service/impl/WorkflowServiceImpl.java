/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.studio.core.base.service.impl;

import com.alibaba.cloud.ai.studio.runtime.domain.app.ApplicationVersion;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.InvokeSourceEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeResult;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeStatusEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.ParamSourceEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.WorkflowStatus;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.TaskRunResponse;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.TaskStopRequest;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.WorkflowRequest;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.WorkflowResponse;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.app.Application;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.ChatMessage;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.MessageRole;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import com.alibaba.cloud.ai.studio.core.base.service.AppService;
import com.alibaba.cloud.ai.studio.core.base.service.WorkflowService;
import com.alibaba.cloud.ai.studio.core.config.CommonConfig;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowConfig;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.core.utils.common.BeanCopierUtils;
import com.alibaba.cloud.ai.studio.core.utils.common.IdGenerator;
import com.alibaba.cloud.ai.studio.core.utils.LogUtils;
import com.alibaba.cloud.ai.studio.core.utils.common.VariableUtils;
import com.alibaba.cloud.ai.studio.core.workflow.runtime.WorkflowExecuteManager;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants.WORKFLOW_TASK_CONTEXT_PREFIX;
import static com.alibaba.cloud.ai.studio.core.workflow.constants.WorkflowConstants.SYS_HISTORY_LIST_KEY;
import static com.alibaba.cloud.ai.studio.core.utils.LogUtils.FAIL;
import static com.alibaba.cloud.ai.studio.core.utils.LogUtils.SUCCESS;

/**
 * Title workspace service.<br>
 * Description workspace service.<br>
 *
 * @since 1.0.0.3
 */

@Slf4j
@Service
public class WorkflowServiceImpl implements WorkflowService {

	@Resource
	private WorkflowExecuteManager workflowExecuteManager;

	@Resource
	private RedisManager redisManager;

	@Resource
	private AppService appService;

	@Resource
	private CommonConfig commonConfig;

	@Override
	public WorkflowResponse call(WorkflowRequest request) {
		List<WorkflowResponse> allResponses = streamCall(Flux.just(request)).collectList().block();
		if (CollectionUtils.isEmpty(allResponses)) {
			throw new BizException(ErrorCode.WORKFLOW_EXECUTE_ERROR.toError("Workflow did not return any response"));
		}

		Optional<WorkflowResponse> terminalResponse = allResponses.stream()
			.filter(response -> WorkflowStatus.FAILED.equals(response.getStatus())
					|| WorkflowStatus.PAUSE.equals(response.getStatus()))
			.findFirst();
		if (terminalResponse.isPresent()) {
			return terminalResponse.get();
		}

		/*
		 * Non-streaming calls reuse streamCall() internally. A normal SAA workflow may
		 * emit an explicit End node, while a Dify-imported workflow often ends at an
		 * Output node and is completed later by AutoEnd. AutoEnd is not emitted as a
		 * business stream message, so aggregating End messages only produces empty
		 * content in that case.
		 *
		 * Prefer explicit End output. If no End message was emitted, aggregate only the
		 * last business Output node, so intermediate Output/debug data is not mixed into
		 * the final non-streaming response.
		 */
		List<WorkflowResponse> businessResponses = collectNodeResponses(allResponses, NodeTypeEnum.END.getCode());
		if (CollectionUtils.isEmpty(businessResponses)) {
			businessResponses = collectLastOutputResponses(allResponses);
		}

		StringBuilder contentBuilder = new StringBuilder();
		for (WorkflowResponse response : businessResponses) {
			if (response.getMessage() != null && response.getMessage().getContent() != null) {
				contentBuilder.append(response.getMessage().getContent());
			}
		}

		WorkflowResponse finalResponse = allResponses.get(allResponses.size() - 1);
		finalResponse.setMessage(new ChatMessage(MessageRole.ASSISTANT, contentBuilder.toString()));
		return finalResponse;
	}

	private List<WorkflowResponse> collectNodeResponses(List<WorkflowResponse> responses, String nodeType) {
		return responses.stream()
			.filter(response -> nodeType.equals(response.getNodeType()))
			.sorted((left, right) -> Integer.compare(sequence(left), sequence(right)))
			.collect(Collectors.toList());
	}

	private List<WorkflowResponse> collectLastOutputResponses(List<WorkflowResponse> responses) {
		Optional<WorkflowResponse> lastOutput = responses.stream()
			.filter(response -> NodeTypeEnum.OUTPUT.getCode().equals(response.getNodeType()))
			.reduce((first, second) -> second);
		if (lastOutput.isEmpty()) {
			return Lists.newArrayList();
		}

		String outputNodeId = lastOutput.get().getNodeId();
		return responses.stream()
			.filter(response -> NodeTypeEnum.OUTPUT.getCode().equals(response.getNodeType())
					&& Objects.equals(outputNodeId, response.getNodeId()))
			.sorted((left, right) -> Integer.compare(sequence(left), sequence(right)))
			.collect(Collectors.toList());
	}

	private int sequence(WorkflowResponse response) {
		return response.getNodeMsgSeqId() == null ? 0 : response.getNodeMsgSeqId();
	}

	@Override
	public Flux<WorkflowResponse> streamCall(Flux<WorkflowRequest> requestFlux) {

		RequestContext requestContext = RequestContextHolder.getRequestContext();
		WorkflowContext context = BeanCopierUtils.copy(requestContext, WorkflowContext.class);
		context.setStream(true);

		return requestFlux.doOnNext(request -> checkAndInitContext(context, request))
			// request model
			.flatMap(request -> streamExecute(context, request))
			// read timeout
			.timeout(Duration.ofSeconds(InvokeSourceEnum.api.getTimeoutSeconds()))
			// handle error
			.onErrorResume(err -> handleThrowable(context, err))
			// post handle
			.doOnNext(response -> postHandle(context, response))
			// final call
			.doFinally(signal -> statistics(context, signal));
	}

	@Override
	public TaskRunResponse asyncCall(WorkflowRequest request) {
		ApplicationVersion appVersion = appService.getAppVersion(request.getAppId(), "lastPublished");
		WorkflowContext workflowContext = new WorkflowContext();
		workflowContext.setInvokeSource(InvokeSourceEnum.async.getCode());
		return workflowExecuteManager.runTask(appVersion, request.getInputParams(), request.getConversationId(),
				workflowContext);
	}

	@Override
	public Boolean stop(TaskStopRequest request) {
		return workflowExecuteManager.stopTask(request.getTaskId());
	}

	private void statistics(WorkflowContext context, SignalType signalType) {
		long firstResponseTime = context.getFirstResponseTime();
		long startTime = context.getStartTime();
		long endTime = context.getEndTime();

		if (firstResponseTime <= 0) {
			firstResponseTime = endTime > 0 ? endTime : System.currentTimeMillis();
		}
		LogUtils.monitor(context, "WorkflowService", "firstResponse", context.getStartTime(), "", null,
				firstResponseTime - startTime);

		if (SignalType.CANCEL == signalType) {
			if (endTime <= 0) {
				endTime = System.currentTimeMillis();
			}

			LogUtils.monitor(context, "WorkflowService", "handleRequests", context.getStartTime(), "cancel",
					"request cancelled", endTime - startTime);
		}
		else {
			if (context.getError() == null) {
				LogUtils.monitor(context, "WorkflowService", "handleRequests", context.getStartTime(), SUCCESS, null,
						context.getTaskResult());
				return;
			}

			LogUtils.monitor(context, "WorkflowService", "handleRequests", context.getStartTime(), FAIL, null,
					context.getError());
		}

		LogUtils.statistics(context, context.getError() == null);
	}

	private void postHandle(WorkflowContext context, WorkflowResponse response) {
		response.setRequestId(context.getRequestId());
		response.setConversationId(context.getConversationId());
		// context.setTaskResult(response.getMessage().getContent());
		context.setEndTime(System.currentTimeMillis());
	}

	private Mono<WorkflowResponse> handleThrowable(WorkflowContext context, Throwable err) {
		WorkflowResponse response = new WorkflowResponse();
		Error error;
		if (err instanceof BizException be) {
			error = be.getError();
		}
		else if (err instanceof TimeoutException) {
			error = ErrorCode.WORKFLOW_EXECUTION_TIMEOUT.toError();
		}
		else {
			error = ErrorCode.WORKFLOW_EXECUTE_ERROR.toError(err.getMessage());
		}
		response.setError(error);
		response.setStatus(WorkflowStatus.FAILED);
		LogUtils.monitor(context, "WorkflowService", "handleThrowable", context.getStartTime(), error.getCode(), null,
				response, err);
		return Mono.just(response);
	}

	private void checkAndInitContext(WorkflowContext workflowContext, WorkflowRequest request) {
		Long start = System.currentTimeMillis();
		RequestContext context = RequestContextHolder.getRequestContext();
		try {
			// check input params
			if (Objects.isNull(request)) {
				throw new BizException(ErrorCode.MISSING_PARAMS.toError("request"));
			}

			String uid = context.getAccountId();
			if (Objects.isNull(uid)) {
				throw new BizException(ErrorCode.UNAUTHORIZED.toError());
			}

			String appId = request.getAppId();
			if (StringUtils.isBlank(appId)) {
				throw new BizException(ErrorCode.MISSING_PARAMS.toError("appId"));
			}

			// if (CollectionUtils.isEmpty(request.getMessages())) {
			// throw new BizException(ErrorCode.MISSING_PARAMS.toError("messages"));
			// }

			if (Objects.isNull(context.getWorkspaceId())) {
				throw new BizException(ErrorCode.MISSING_PARAMS.toError("workspace_id"));
			}

			// get app config
			Application app = appService.getApp(appId);
			if (app == null) {
				throw new BizException(ErrorCode.APP_NOT_FOUND.toError());
			}

			String configStr;
			if (BooleanUtils.isTrue(request.getDraft())) {
				configStr = app.getConfigStr();
				if (configStr == null) {
					throw new BizException(ErrorCode.APP_CONFIG_NOT_FOUND.toError());
				}
			}
			else {
				configStr = app.getPubConfigStr();
				if (configStr == null) {
					throw new BizException(ErrorCode.APP_NOT_PUBLISHED.toError());
				}
			}

			workflowContext.setAppId(appId);

			if (!CollectionUtils.isEmpty(request.getInputParams())) {
				request.getInputParams().stream().forEach(input -> {
					String key = input.getKey();
					String source = input.getSource();
					if (ParamSourceEnum.sys.name().equals(source)) {
						workflowContext.getSysMap()
							.put(key, VariableUtils.convertValueByType(input.getKey(), input.getType(),
									input.getValue()));
					}
					else {
						workflowContext.getUserMap()
							.put(key, VariableUtils.convertValueByType(input.getKey(), input.getType(),
									input.getValue()));
					}
				});
			}

			// 处理messages作为上下文
			if (!CollectionUtils.isEmpty(request.getMessages())) {
				workflowContext.getSysMap().put(SYS_HISTORY_LIST_KEY, request.getMessages());
			}

			String conversationId = StringUtils.isBlank(request.getConversationId())
					? IdGenerator.idStr() : request.getConversationId();
			workflowContext.setWorkflowConfig(JsonUtils.fromJson(configStr, WorkflowConfig.class));
			workflowContext.setTaskStatus(NodeStatusEnum.EXECUTING.getCode());
			workflowContext.setRequestId(context.getRequestId());
			workflowContext.setWorkspaceId(context.getWorkspaceId());
			workflowContext.setConversationId(conversationId);
			workflowContext.setInvokeSource(InvokeSourceEnum.api.getCode());
			LogUtils.monitor(context, "WorkflowService", "check", start, SUCCESS, request, null);
		}
		catch (BizException e) {
			LogUtils.monitor(context, "WorkflowService", "check", start, FAIL, request, e.getError(), e);
			throw e;
		}
	}

	private Flux<WorkflowResponse> streamExecute(WorkflowContext workflowContext, WorkflowRequest request) {

		String version = BooleanUtils.isTrue(request.getDraft()) ? "latest" : "lastPublished";
		ApplicationVersion appVersion = appService.getAppVersion(request.getAppId(), version);

		/*
		 * Event-driven streaming:
		 *
		 * The old implementation started a dedicated polling thread and scanned
		 * executeOrderList every 50 ms. The workflow context now exposes a transient
		 * in-memory event stream. Node updates and task-status changes are pushed into
		 * that stream directly, so no polling thread is needed.
		 */
		Sinks.Many<WorkflowResponse> sink = Sinks.many().unicast().onBackpressureBuffer();
		Map<String, AtomicInteger> recmsgSeqIdMap = new ConcurrentHashMap<>();
		Map<String, String> lastOutputMap = new ConcurrentHashMap<>();
		AtomicBoolean firstResponse = new AtomicBoolean(true);
		AtomicBoolean terminalSent = new AtomicBoolean(false);

		Flux<WorkflowContext.WorkflowEvent> eventFlux = workflowContext.enableStreamEvents();
		Disposable eventSubscription = eventFlux.subscribe(
				event -> handleWorkflowEvent(sink, workflowContext, event, recmsgSeqIdMap, lastOutputMap,
						firstResponse, terminalSent),
				err -> {
					if (terminalSent.compareAndSet(false, true)) {
						markFirstResponse(workflowContext, firstResponse);
						Error error = ErrorCode.WORKFLOW_EXECUTE_ERROR.toError(err.getMessage());
						sendErrorMessage(sink, workflowContext.getRequestId(), workflowContext.getTaskId(),
								workflowContext.getConversationId(), error);
					}
				});

		try {
			workflowExecuteManager.runTask(appVersion, request.getInputParams(), request.getConversationId(),
					workflowContext);
		}
		catch (RuntimeException e) {
			eventSubscription.dispose();
			workflowContext.closeStreamEvents();
			throw e;
		}

		return sink.asFlux().doFinally(signal -> {
			eventSubscription.dispose();
			workflowContext.closeStreamEvents();
		});
	}

	private void handleWorkflowEvent(Sinks.Many<WorkflowResponse> sink, WorkflowContext context,
			WorkflowContext.WorkflowEvent event, Map<String, AtomicInteger> recmsgSeqIdMap,
			Map<String, String> lastOutputMap, AtomicBoolean firstResponse, AtomicBoolean terminalSent) {

		if (terminalSent.get() || event == null) {
			return;
		}

		/*
		 * SUCCESS and STOP are lifecycle events that do not necessarily have a following
		 * node-result update. FAIL and PAUSE may be announced before their node result is
		 * fully stored, so handleTerminalEventIfReady() checks the context map before
		 * closing the stream.
		 */
		if (WorkflowContext.WorkflowEvent.Type.TASK_STATUS.equals(event.getType())) {
			handleTerminalEventIfReady(sink, context, firstResponse, terminalSent);
			return;
		}

		NodeResult nodeResult = event.getNodeResult();
		if (nodeResult == null) {
			return;
		}

		if (handleTerminalEventIfReady(sink, context, firstResponse, terminalSent)) {
			return;
		}

		if (!isBusinessStreamNode(nodeResult)) {
			return;
		}

		/*
		 * AutoEnd is a lifecycle-only End node. It is not part of executeOrderList and
		 * must not emit the same user-facing Output content a second time.
		 */
		if (NodeTypeEnum.END.getCode().equals(nodeResult.getNodeType())
				&& !context.getExecuteOrderList().contains(nodeResult.getNodeId())) {
			return;
		}

		String currentOutput = nodeResult.getOutput() == null ? "" : nodeResult.getOutput();
		String previousOutput = lastOutputMap.getOrDefault(nodeResult.getNodeId(), "");
		String incrementalContent = calculateIncrementalContent(currentOutput, previousOutput);
		boolean nodeCompleted = NodeStatusEnum.SUCCESS.getCode().equals(nodeResult.getNodeStatus());

		if (StringUtils.isNotBlank(incrementalContent) || nodeCompleted) {
			AtomicInteger sequence = recmsgSeqIdMap.computeIfAbsent(nodeResult.getNodeId(),
					key -> new AtomicInteger(0));
			if (sendNodeMessage(sink, nodeResult, context.getRequestId(), context.getTaskId(),
					context.getConversationId(), sequence.incrementAndGet(), incrementalContent)) {
				markFirstResponse(context, firstResponse);
			}
		}

		lastOutputMap.put(nodeResult.getNodeId(), currentOutput);
	}

	private boolean handleTerminalEventIfReady(Sinks.Many<WorkflowResponse> sink, WorkflowContext context,
			AtomicBoolean firstResponse, AtomicBoolean terminalSent) {

		String taskStatus = context.getTaskStatus();

		if (NodeStatusEnum.FAIL.getCode().equals(taskStatus)) {
			Optional<NodeResult> failedNode = context.getNodeResultMap()
				.values()
				.stream()
				.filter(result -> NodeStatusEnum.FAIL.getCode().equals(result.getNodeStatus()))
				.findFirst();

			// Some processors set taskStatus before the failed NodeResult is stored.
			if (failedNode.isEmpty()) {
				return false;
			}
			if (!terminalSent.compareAndSet(false, true)) {
				return true;
			}

			Error error = context.getError();
			if (error == null) {
				NodeResult failed = failedNode.get();
				error = failed.getError();
				if (error == null) {
					error = ErrorCode.WORKFLOW_EXECUTE_ERROR
						.toError(StringUtils.defaultIfBlank(failed.getErrorInfo(), "Workflow execution failed"));
				}
			}

			markFirstResponse(context, firstResponse);
			sendErrorMessage(sink, context.getRequestId(), context.getTaskId(), context.getConversationId(), error);
			return true;
		}

		if (NodeStatusEnum.PAUSE.getCode().equals(taskStatus)) {
			boolean pauseNodeReady = context.getNodeResultMap()
				.values()
				.stream()
				.anyMatch(result -> NodeStatusEnum.PAUSE.getCode().equals(result.getNodeStatus()));
			if (!pauseNodeReady) {
				return false;
			}
			if (!terminalSent.compareAndSet(false, true)) {
				return true;
			}

			markFirstResponse(context, firstResponse);
			sendPauseMessage(sink, context, context.getRequestId(), context.getTaskId(), context.getConversationId());
			return true;
		}

		if (NodeStatusEnum.SUCCESS.getCode().equals(taskStatus)) {
			if (!terminalSent.compareAndSet(false, true)) {
				return true;
			}

			markFirstResponse(context, firstResponse);
			sendFinishMessage(sink, context.getRequestId(), context.getTaskId(), context.getConversationId());
			return true;
		}

		if (NodeStatusEnum.STOP.getCode().equals(taskStatus)) {
			if (!terminalSent.compareAndSet(false, true)) {
				return true;
			}

			markFirstResponse(context, firstResponse);
			// Keep the existing wire contract: STOP closes the stream without inventing
			// a new public WorkflowStatus value.
			completeSink(sink);
			return true;
		}

		return false;
	}

	private boolean isBusinessStreamNode(NodeResult nodeResult) {
		if (nodeResult == null) {
			return false;
		}
		String nodeType = nodeResult.getNodeType();
		String nodeStatus = nodeResult.getNodeStatus();
		boolean supportedType = NodeTypeEnum.OUTPUT.getCode().equals(nodeType)
				|| NodeTypeEnum.END.getCode().equals(nodeType)
				|| NodeTypeEnum.INPUT.getCode().equals(nodeType);
		boolean supportedStatus = NodeStatusEnum.EXECUTING.getCode().equals(nodeStatus)
				|| NodeStatusEnum.SUCCESS.getCode().equals(nodeStatus)
				|| NodeStatusEnum.PAUSE.getCode().equals(nodeStatus);
		return supportedType && supportedStatus;
	}

	private void markFirstResponse(WorkflowContext context, AtomicBoolean firstResponse) {
		if (firstResponse.compareAndSet(true, false)) {
			context.setFirstResponseTime(System.currentTimeMillis());
		}
	}

	private boolean handleNodeMessage(Sinks.Many<WorkflowResponse> sink, String requestId, String taskId,
			String conversationId, Map<String, AtomicInteger> recmsgSeqIdMap, List<NodeResult> lastNodeResults,
			List<NodeResult> thisNodeResult) {
		if (CollectionUtils.isEmpty(thisNodeResult)) {
			return false;
		}
		boolean diff = false;
		if (CollectionUtils.isEmpty(lastNodeResults)) {
			for (NodeResult nodeResult : thisNodeResult) {
				String incrementalContent = calculateIncrementalContent(nodeResult.getOutput(), "");
				AtomicInteger atomicInteger = recmsgSeqIdMap.get(nodeResult.getNodeId());
				if (atomicInteger == null) {
					atomicInteger = new AtomicInteger(0);
				}
				boolean nodeCompleted = nodeResult.getNodeStatus().equals(NodeStatusEnum.SUCCESS.getCode());
				if (StringUtils.isNotBlank(incrementalContent) || nodeCompleted) {
					if (sendNodeMessage(sink, nodeResult, requestId, taskId, conversationId,
							atomicInteger.incrementAndGet(), incrementalContent)) {
						diff = true;
					}
				}
				recmsgSeqIdMap.put(nodeResult.getNodeId(), atomicInteger);
			}
		}
		else {
			Map<String, List<NodeResult>> listMap = lastNodeResults.stream()
				.collect(Collectors.groupingBy(NodeResult::getNodeId));
			for (NodeResult nodeResult : thisNodeResult) {
				List<NodeResult> lastNodeResultList = listMap.get(nodeResult.getNodeId());
				boolean nodeCompleted = (CollectionUtils.isEmpty(lastNodeResultList)
						|| !lastNodeResultList.get(0).getNodeStatus().equals(NodeStatusEnum.SUCCESS.getCode()))
						&& nodeResult.getNodeStatus().equals(NodeStatusEnum.SUCCESS.getCode());
				String incrementalContent;
				if (CollectionUtils.isEmpty(lastNodeResultList)) {
					incrementalContent = calculateIncrementalContent(nodeResult.getOutput(), "");
				}
				else {
					incrementalContent = calculateIncrementalContent(nodeResult.getOutput(),
							lastNodeResultList.get(0).getOutput());
				}

				AtomicInteger atomicInteger = recmsgSeqIdMap.get(nodeResult.getNodeId());
				if (atomicInteger == null) {
					atomicInteger = new AtomicInteger(0);
				}
				if (StringUtils.isNotBlank(incrementalContent) || nodeCompleted) {
					if (sendNodeMessage(sink, nodeResult, requestId, taskId, conversationId,
							atomicInteger.incrementAndGet(), incrementalContent)) {
						diff = true;
					}
				}
				recmsgSeqIdMap.put(nodeResult.getNodeId(), atomicInteger);
			}
		}
		return diff;
	}

	private WorkflowContext getLatestContext(RequestContext context, String taskId) {
		return redisManager.get(WORKFLOW_TASK_CONTEXT_PREFIX + context.getWorkspaceId() + "_" + taskId);
	}

	private void handleCompletedMsg(Sinks.Many<WorkflowResponse> sink, WorkflowContext context, String requestId,
			String taskId, String conversationId) {
		String taskStatus = context.getTaskStatus();

		if (NodeStatusEnum.FAIL.getCode().equals(taskStatus)) {
			sendErrorMessage(sink, requestId, taskId, conversationId, context.getError());
		}
		else if (NodeStatusEnum.PAUSE.getCode().equals(taskStatus)) {
			sendPauseMessage(sink, context, requestId, taskId, conversationId);
		}
		else if (NodeStatusEnum.SUCCESS.getCode().equals(taskStatus)) {
			sendFinishMessage(sink, requestId, taskId, conversationId);
		}
		else if (NodeStatusEnum.STOP.getCode().equals(taskStatus)) {
			// The public WorkflowStatus enum has no STOPPED value. Close the stream
			// cleanly without inventing a new wire-level status.
			completeSink(sink);
		}
	}

	private String calculateIncrementalContent(String currentOutput, String lastOutput) {
		if (currentOutput == null) {
			return "";
		}
		if (lastOutput != null && !lastOutput.isEmpty() && currentOutput.startsWith(lastOutput)) {
			return currentOutput.substring(lastOutput.length());
		}
		return currentOutput;
	}

	private boolean sendNodeMessage(Sinks.Many<WorkflowResponse> sink, NodeResult nodeResult, String requestId,
			String taskId, String conversationId, int msgSeqId, String content) {
		WorkflowResponse response = new WorkflowResponse();
		response.setRequestId(requestId);
		response.setTaskId(taskId);
		response.setConversationId(conversationId);
		ChatMessage message = new ChatMessage(MessageRole.ASSISTANT, content);
		response.setMessage(message);
		response.setNodeId(nodeResult.getNodeId());
		response.setNodeName(nodeResult.getNodeName());
		response.setNodeType(nodeResult.getNodeType());
		response.setNodeStatus(nodeResult.getNodeStatus());
		response.setStatus(WorkflowStatus.IN_PROGRESS);
		response.setNodeIsCompleted(nodeResult.getNodeStatus().equals(NodeStatusEnum.SUCCESS.getCode()));
		response.setNodeMsgSeqId(msgSeqId);
		return tryEmitNext(sink, response);
	}

	private void sendErrorMessage(Sinks.Many<WorkflowResponse> sink, String requestId, String taskId,
			String conversationId, Error error) {
		WorkflowResponse response = new WorkflowResponse();
		response.setRequestId(requestId);
		response.setTaskId(taskId);
		response.setConversationId(conversationId);
		response.setError(error);
		response.setStatus(WorkflowStatus.FAILED);
		tryEmitNext(sink, response);
		completeSink(sink);
	}

	private void sendPauseMessage(Sinks.Many<WorkflowResponse> sink, WorkflowContext context, String requestId,
			String taskId, String conversationId) {
		Optional<NodeResult> pauseNodeResult = context.getNodeResultMap()
			.values()
			.stream()
			.filter(result -> NodeStatusEnum.PAUSE.getCode().equals(result.getNodeStatus()))
			.findFirst();

		WorkflowResponse response = new WorkflowResponse();
		response.setRequestId(requestId);
		response.setTaskId(taskId);
		response.setConversationId(conversationId);
		response.setStatus(WorkflowStatus.PAUSE);
		if (pauseNodeResult.isPresent()) {
			NodeResult nodeResult = pauseNodeResult.get();
			Map<String, Object> pauseData = Maps.newHashMap();
			pauseData.put("node_id", nodeResult.getNodeId());
			pauseData.put("node_name", nodeResult.getNodeName());
			pauseData.put("node_type", nodeResult.getNodeType());
			pauseData.put("input_params", nodeResult.getInput());
			ChatMessage message = new ChatMessage(MessageRole.ASSISTANT, JsonUtils.toJson(pauseData));
			response.setMessage(message);
		}

		// PAUSE is terminal for the current streaming call even when no pause node can be
		// found because of a timing race.
		tryEmitNext(sink, response);
		completeSink(sink);
	}

	private void sendFinishMessage(Sinks.Many<WorkflowResponse> sink, String requestId, String taskId,
			String conversationId) {
		WorkflowResponse response = new WorkflowResponse();
		response.setRequestId(requestId);
		response.setTaskId(taskId);
		response.setConversationId(conversationId);
		response.setStatus(WorkflowStatus.COMPLETED);
		tryEmitNext(sink, response);
		completeSink(sink);
	}

	private boolean tryEmitNext(Sinks.Many<WorkflowResponse> sink, WorkflowResponse response) {
		Sinks.EmitResult result = sink.tryEmitNext(response);
		if (result.isSuccess()) {
			return true;
		}
		if (result != Sinks.EmitResult.FAIL_CANCELLED && result != Sinks.EmitResult.FAIL_TERMINATED) {
			log.debug("Workflow stream emit ignored, result={}, taskId={}", result, response.getTaskId());
		}
		return false;
	}

	private void completeSink(Sinks.Many<WorkflowResponse> sink) {
		Sinks.EmitResult result = sink.tryEmitComplete();
		if (!result.isSuccess() && result != Sinks.EmitResult.FAIL_CANCELLED
				&& result != Sinks.EmitResult.FAIL_TERMINATED) {
			log.debug("Workflow stream complete ignored, result={}", result);
		}
	}

}
