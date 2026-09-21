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

import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Edge;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeResult;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeStatusEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.ValueFromEnum;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import com.alibaba.cloud.ai.studio.core.config.CommonConfig;
import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.core.utils.common.VariableUtils;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowInnerService;
import com.alibaba.cloud.ai.studio.core.workflow.processor.AbstractExecuteProcessor;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.alibaba.cloud.ai.studio.core.utils.common.VariableUtils.getValueFromPayload;

/**
 * Processor for variable assignment operations in workflows.
 *
 * Supports:
 * =   overwrite
 * +=  numeric addition
 * -=  numeric subtraction
 * *=  numeric multiplication
 * /=  numeric division
 *
 * Old workflow configs without "operation" remain compatible and are treated as "=".
 *
 * @version 1.0.0-beta
 */
@Slf4j
@Component("VariableAssignExecuteProcessor")
public class VariableAssignExecuteProcessor extends AbstractExecuteProcessor {

	private static final String OP_ASSIGN = "=";

	private static final Set<String> SUPPORTED_OPERATIONS = Set.of("=", "+=", "-=", "*=", "/=");

	public VariableAssignExecuteProcessor(RedisManager redisManager, WorkflowInnerService workflowInnerService,
			ChatMemory conversationChatMemory, CommonConfig commonConfig) {
		super(redisManager, workflowInnerService, conversationChatMemory, commonConfig);
	}

	@Override
	public NodeResult innerExecute(DirectedAcyclicGraph<String, Edge> graph, Node node, WorkflowContext context) {

		NodeResult nodeResult = initNodeResultAndRefreshContext(node, context);

		try {
			NodeParam config = JsonUtils.fromMap(node.getConfig().getNodeParam(), NodeParam.class);
			List<Pair> inputs = config.getInputs() == null ? List.of() : config.getInputs();
			List<Object> inputList = new ArrayList<>();

			// Collect input information for trace/debug.
			for (Pair pair : inputs) {
				Node.InputParam left = pair.getLeft();
				Node.InputParam right = pair.getRight();
				if (left == null || right == null || left.getValue() == null) {
					continue;
				}
				if (right.getValue() == null && !ValueFromEnum.clear.name().equals(right.getValueFrom())) {
					continue;
				}

				String leftExpression = VariableUtils.getExpressionFromBracket((String) left.getValue());
				Object leftValue = getValueFromPayload(leftExpression, context.getVariablesMap());
				Object rightValue = resolveRightValue(right, context);

				Map<String, Object> object = new HashMap<>();
				object.put("left", leftValue == null ? "" : leftValue);
				object.put("operation", normalizeOperation(pair.getOperation()));
				object.put("right", rightValue);
				inputList.add(object);
			}
			nodeResult.setInput(JsonUtils.toJson(inputList));

			for (Pair pair : inputs) {
				Node.InputParam left = pair.getLeft();
				Node.InputParam right = pair.getRight();

				if (left == null || right == null || left.getValue() == null) {
					continue;
				}
				if (right.getValue() == null && !ValueFromEnum.clear.name().equals(right.getValueFrom())) {
					continue;
				}

				String leftExpression = VariableUtils.getExpressionFromBracket((String) left.getValue());
				Object currentValue = getValueFromPayload(leftExpression, context.getVariablesMap());
				Object rightValue = resolveRightValue(right, context);
				String operation = normalizeOperation(pair.getOperation());

				Object resultValue = applyOperation(operation, left, right, currentValue, rightValue);
				VariableUtils.setValueForPayload(leftExpression, context.getVariablesMap(), resultValue);
			}
		}
		catch (Exception e) {
			log.error("VariableNode execute fail ,requestId:{}", context.getRequestId(), e);
			nodeResult.setNodeStatus(NodeStatusEnum.FAIL.getCode());
			HashMap<String, String> resultMap = new HashMap<>();
			resultMap.put("result", "Setup failed");
			nodeResult.setOutput(JsonUtils.toJson(resultMap));
			if (e instanceof BizException) {
				nodeResult.setErrorInfo(((BizException) e).getError().getMessage());
				nodeResult.setError(((BizException) e).getError());
			}
			else {
				nodeResult.setErrorInfo("Variable assignment error:" + e.getMessage());
				nodeResult
					.setError(ErrorCode.WORKFLOW_EXECUTE_ERROR.toError("Variable assignment error:" + e.getMessage()));
			}
			return nodeResult;
		}

		HashMap<String, String> resultMap = new HashMap<>();
		resultMap.put("result", "Assignment successful");
		nodeResult.setOutput(JsonUtils.toJson(resultMap));

		return nodeResult;
	}

	private Object resolveRightValue(Node.InputParam right, WorkflowContext context) {
		if (ValueFromEnum.refer.name().equals(right.getValueFrom())) {
			String rightExpression = VariableUtils.getExpressionFromBracket((String) right.getValue());
			return getValueFromPayload(rightExpression, context.getVariablesMap());
		}
		if (ValueFromEnum.clear.name().equals(right.getValueFrom())) {
			return null;
		}
		return right.getValue();
	}

	private Object applyOperation(String operation, Node.InputParam left, Node.InputParam right,
			Object currentValue, Object rightValue) {

		if (OP_ASSIGN.equals(operation)) {
			return rightValue;
		}

		if (!SUPPORTED_OPERATIONS.contains(operation)) {
			throw new IllegalArgumentException("Unsupported variable assignment operation: " + operation);
		}

		if (ValueFromEnum.clear.name().equals(right.getValueFrom())) {
			throw new IllegalArgumentException(operation + " does not support clear");
		}

		if (left.getType() != null && !"Number".equalsIgnoreCase(left.getType())) {
			throw new IllegalArgumentException(operation + " only supports Number variables");
		}

		BigDecimal leftNumber = toBigDecimal(currentValue, "left");
		BigDecimal rightNumber = toBigDecimal(rightValue, "right");

		BigDecimal result = switch (operation) {
			case "+=" -> leftNumber.add(rightNumber);
			case "-=" -> leftNumber.subtract(rightNumber);
			case "*=" -> leftNumber.multiply(rightNumber);
			case "/=" -> {
				if (BigDecimal.ZERO.compareTo(rightNumber) == 0) {
					throw new IllegalArgumentException("Division by zero is not allowed");
				}
				yield leftNumber.divide(rightNumber, MathContext.DECIMAL128);
			}
			default -> throw new IllegalArgumentException("Unsupported variable assignment operation: " + operation);
		};

		return normalizeNumber(result);
	}

	private BigDecimal toBigDecimal(Object value, String side) {
		if (value == null) {
			throw new IllegalArgumentException(side + " value is null, arithmetic assignment requires Number");
		}
		if (value instanceof BigDecimal decimal) {
			return decimal;
		}
		if (value instanceof Number number) {
			return new BigDecimal(number.toString());
		}
		try {
			return new BigDecimal(String.valueOf(value).trim());
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException(side + " value is not Number: " + value);
		}
	}

	private Object normalizeNumber(BigDecimal value) {
		BigDecimal normalized = value.stripTrailingZeros();
		if (normalized.scale() <= 0) {
			try {
				return normalized.longValueExact();
			}
			catch (ArithmeticException ignored) {
				return normalized.doubleValue();
			}
		}
		return normalized.doubleValue();
	}

	private String normalizeOperation(String operation) {
		return operation == null || operation.isBlank() ? OP_ASSIGN : operation.trim();
	}

	@Override
	public CheckNodeParamResult checkNodeParam(DirectedAcyclicGraph<String, Edge> graph, Node node) {
		CheckNodeParamResult result = super.checkNodeParam(graph, node);
		NodeParam nodeParam = JsonUtils.fromMap(node.getConfig().getNodeParam(), NodeParam.class);
		List<Pair> inputs = nodeParam.getInputs();
		if (inputs == null || inputs.isEmpty()) {
			result.setSuccess(false);
			result.getErrorInfos().add("No variables added");
			return result;
		}

		for (Pair pair : inputs) {
			Node.InputParam left = pair.getLeft();
			Node.InputParam right = pair.getRight();

			if (left == null || left.getValue() == null) {
				result.setSuccess(false);
				result.getErrorInfos().add("Left value is empty");
				continue;
			}

			String operation = normalizeOperation(pair.getOperation());
			if (!SUPPORTED_OPERATIONS.contains(operation)) {
				result.setSuccess(false);
				result.getErrorInfos().add("Unsupported variable assignment operation: " + operation);
			}
			if (!OP_ASSIGN.equals(operation) && left.getType() != null
					&& !"Number".equalsIgnoreCase(left.getType())) {
				result.setSuccess(false);
				result.getErrorInfos().add(operation + " only supports Number variables");
			}
			if (!OP_ASSIGN.equals(operation) && right != null
					&& ValueFromEnum.clear.name().equals(right.getValueFrom())) {
				result.setSuccess(false);
				result.getErrorInfos().add(operation + " does not support clear");
			}
		}

		return result;
	}

	@Data
	public static class NodeParam {

		@JsonProperty("inputs")
		private List<Pair> inputs;

	}

	@Data
	public static class Pair {

		@JsonProperty("left")
		private Node.InputParam left;

		@JsonProperty("right")
		private Node.InputParam right;

		/**
		 * =, +=, -=, *=, /=.
		 * Missing value keeps backward compatibility and is treated as "=".
		 */
		@JsonProperty("operation")
		private String operation = OP_ASSIGN;

	}

	@Override
	public String getNodeType() {
		return NodeTypeEnum.VARIABLE_ASSIGN.getCode();
	}

	@Override
	public String getNodeDescription() {
		return NodeTypeEnum.VARIABLE_ASSIGN.getDesc();
	}

}
