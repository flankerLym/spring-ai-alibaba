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

import com.alibaba.cloud.ai.studio.core.base.manager.ModelExecuteManager;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentResponse;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.ChatMessage;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.MessageRole;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.*;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.inner.ShortTermMemory;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClassifierExecuteProcessorTest {

	@Test
	void currentFollowUpIsLastAndRoutesToSecondCategoryInBothModes() {
		for (String mode : List.of("efficient", "advanced")) {
			ModelExecuteManager model = mock(ModelExecuteManager.class);
			when(model.stream(anyString(), anyString(), anyMap(), anyList())).thenAnswer(invocation -> {
				List<Message> messages = invocation.getArgument(3);
				assertInstanceOf(SystemMessage.class, messages.get(0));
				assertTrue(messages.get(0).getText().contains("看看一个可尝试的方案"));
				assertInstanceOf(UserMessage.class, messages.get(messages.size() - 1));
				assertEquals("CURRENT input to classify:\n看看一个可尝试的方案",
						messages.get(messages.size() - 1).getText());
				assertEquals(1, messages.stream().filter(m -> m instanceof SystemMessage).count());
				return Flux.just(AgentResponse.builder()
						.message(new ChatMessage(MessageRole.ASSISTANT, "<Decision>：2")).build());
			});
			Node node = node(mode);
			DirectedAcyclicGraph<String, Edge> graph = new DirectedAcyclicGraph<>(Edge.class);
			graph.addVertex("classifier");
			graph.addVertex("plan");
			Edge edge = new Edge();
			edge.setSourceHandle("classifier_plan");
			edge.setTarget("plan");
			graph.addEdge("classifier", "plan", edge);
			NodeResult result = processor(model).innerExecute(graph, node, new WorkflowContext());
			assertNotEquals(NodeStatusEnum.FAIL.getCode(), result.getNodeStatus());
			assertEquals(1, result.getMultiBranchResults().size());
			assertEquals("plan", result.getMultiBranchResults().get(0).getConditionId());
			assertEquals(List.of("plan"), result.getMultiBranchResults().get(0).getTargetIds());
			assertTrue(result.getOutput().contains("行动方案"));
		}
	}

	@Test
	void malformedOutputAndStreamErrorsFailPromptly() {
		for (Flux<AgentResponse> stream : List.of(
				Flux.just(AgentResponse.builder().message(new ChatMessage(MessageRole.ASSISTANT, "[1,2]")).build()),
				Flux.<AgentResponse>error(new IllegalStateException("test failure")))) {
			ModelExecuteManager model = mock(ModelExecuteManager.class);
			when(model.stream(anyString(), anyString(), anyMap(), anyList())).thenReturn(stream);
			NodeResult result = processor(model).innerExecute(new DirectedAcyclicGraph<>(Edge.class),
					node("efficient"), new WorkflowContext());
			assertEquals(NodeStatusEnum.FAIL.getCode(), result.getNodeStatus());
			assertNotNull(result.getErrorInfo());
		}
	}

	private Node node(String mode) {
		Node node = new Node();
		node.setId("classifier");
		Node.NodeCustomConfig config = new Node.NodeCustomConfig();
		config.setNodeParam(Map.of("mode_switch", mode,
				"model_config", Map.of("provider", "openai", "model_id", "test", "params", List.of()),
				"conditions", List.of(Map.of("id", "empathy", "subject", "共情"),
						Map.of("id", "default", "subject", "legacy"),
						Map.of("id", "plan", "subject", "行动方案", "examples", "看看一个可尝试的方案"))));
		node.setConfig(config);
		return node;
	}

	private ClassifierExecuteProcessor processor(ModelExecuteManager model) {
		return new ClassifierExecuteProcessor(null, null, null, null, model) {
			@Override
			protected NodeResult initNodeResultAndRefreshContext(Node node, WorkflowContext context) {
				NodeResult result = new NodeResult();
				result.setNodeStatus(NodeStatusEnum.EXECUTING.getCode());
				return result;
			}

			@Override
			public Map<String, Object> constructInputParamsMap(Node node, WorkflowContext context) {
				return Map.of("input", "看看一个可尝试的方案");
			}

			@Override
			protected List<Message> constructShortTermMemory(Node node, ShortTermMemory memory,
					WorkflowContext context) {
				return List.of(new SystemMessage("old context"), new UserMessage("我很焦虑"),
						new AssistantMessage("Matched classification is 共情"));
			}
		};
	}

}
