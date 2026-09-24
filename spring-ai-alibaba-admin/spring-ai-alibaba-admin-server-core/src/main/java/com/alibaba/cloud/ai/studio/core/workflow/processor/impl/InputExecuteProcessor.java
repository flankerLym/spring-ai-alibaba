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

package com.alibaba.cloud.ai.studio.core.workflow.processor.impl;

import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Edge;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeResult;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeStatusEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import com.alibaba.cloud.ai.studio.core.config.CommonConfig;
import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowInnerService;
import com.alibaba.cloud.ai.studio.core.workflow.processor.AbstractExecuteProcessor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants.WORKFLOW_TASK_CONTEXT_PREFIX;

/**
 * Input Node Processor
 * <p>
 * This processor is responsible for handling input nodes in the workflow. It manages user
 * input collection and timeout handling for workflow execution.
 *
 * @version 1.0.0-M1
 */
@Slf4j
@Component("InputExecuteProcessor")
public class InputExecuteProcessor extends AbstractExecuteProcessor {

	public InputExecuteProcessor(RedisManager redisManager, WorkflowInnerService workflowInnerService,
			ChatMemory conversationChatMemory, CommonConfig commonConfig) {
		super(redisManager, workflowInnerService, conversationChatMemory, commonConfig);
	}

	@Override
	public NodeResult innerExecute(DirectedAcyclicGraph<String, Edge> graph, Node node, WorkflowContext context) {
		NodeResult nodeResult = new NodeResult();
		nodeResult.setNodeId(node.getId());
		nodeResult.setNodeName(node.getName());
		nodeResult.setNodeType(node.getType());
		nodeResult.setUsages(null);

		List<Node.OutputParam> outputParams = node.getConfig().getOutputParams();
		nodeResult.setInput(JsonUtils.toJson(outputParams));
		nodeResult.setNodeStatus(NodeStatusEnum.PAUSE.getCode());

		/*
		 * Store the pause node before announcing PAUSE so event-driven consumers can read
		 * the complete pause payload immediately.
		 */
		context.getNodeResultMap().put(node.getId(), nodeResult);
		context.setTaskStatus(NodeStatusEnum.PAUSE.getCode());

		/*
		 * API workflows normally skip Redis writes for performance. Input resume is the
		 * exception: the resume endpoint needs a durable task context, so persist only
		 * when a task actually pauses for user input.
		 */
		workflowInnerService.forceRefreshContextCache(context);

		long startTime = System.currentTimeMillis();
		long timeout = commonConfig.getInputTimeout();

		while (NodeStatusEnum.PAUSE.getCode().equals(nodeResult.getNodeStatus())) {
			WorkflowContext cachedContext = redisManager
				.get(WORKFLOW_TASK_CONTEXT_PREFIX + context.getWorkspaceId() + "_" + context.getTaskId());

			if (cachedContext != null && cachedContext.getNodeResultMap() != null) {
				NodeResult cachedNodeResult = cachedContext.getNodeResultMap().get(node.getId());
				if (cachedNodeResult != null) {
					nodeResult = cachedNodeResult;
				}
			}

			if (!NodeStatusEnum.PAUSE.getCode().equals(nodeResult.getNodeStatus())) {
				break;
			}

			if (System.currentTimeMillis() - startTime > timeout) {
				nodeResult.setNodeStatus(NodeStatusEnum.FAIL.getCode());
				nodeResult.setErrorInfo("Input node waiting timeout");
				nodeResult.setError(ErrorCode.WORKFLOW_EXECUTE_ERROR.toError("input node waiting timeout"));
				return nodeResult;
			}

			try {
				Thread.sleep(500);
			}
			catch (InterruptedException e) {
				log.warn("Interrupted while waiting for input node result, taskId={}", context.getTaskId());
				Thread.currentThread().interrupt();
				nodeResult.setNodeStatus(NodeStatusEnum.FAIL.getCode());
				nodeResult.setErrorInfo("Input node waiting interrupted");
				nodeResult.setError(ErrorCode.WORKFLOW_EXECUTE_ERROR.toError("input node waiting interrupted"));
				return nodeResult;
			}
		}

		nodeResult.setNodeStatus(NodeStatusEnum.EXECUTING.getCode());
		context.getNodeResultMap().put(node.getId(), nodeResult);
		context.setTaskStatus(NodeStatusEnum.EXECUTING.getCode());

		return nodeResult;
	}

	@Override
	public String getNodeType() {
		return NodeTypeEnum.INPUT.getCode();
	}

	@Override
	public String getNodeDescription() {
		return NodeTypeEnum.INPUT.getDesc();
	}

}
