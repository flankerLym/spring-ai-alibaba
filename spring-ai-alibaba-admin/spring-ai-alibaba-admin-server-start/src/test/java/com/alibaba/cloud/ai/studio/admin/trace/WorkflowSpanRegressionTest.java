/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.studio.admin.trace;

import com.alibaba.cloud.ai.studio.admin.conversation.ConversationPersistenceAspect;
import com.alibaba.cloud.ai.studio.admin.trace.aspect.ModelCallTraceAspect;
import com.alibaba.cloud.ai.studio.admin.trace.aspect.WorkflowSpanAspect;
import com.alibaba.cloud.ai.studio.core.config.CommonConfig;
import com.alibaba.cloud.ai.studio.core.conversation.ConversationManager;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowConfig;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowInnerService;
import com.alibaba.cloud.ai.studio.core.workflow.processor.AbstractExecuteProcessor;
import com.alibaba.cloud.ai.studio.core.workflow.runtime.WorkflowExecuteManager;
import com.alibaba.cloud.ai.studio.core.workflow.trace.annotation.WorkflowModelCall;
import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowSpanContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.context.WorkflowTraceContext;
import com.alibaba.cloud.ai.studio.core.workflow.trace.registry.WorkflowTraceRegistry;
import com.alibaba.cloud.ai.studio.core.workflow.trace.reporter.SpanReporter;
import com.alibaba.cloud.ai.studio.core.workflow.trace.service.WorkflowNodeTraceScope;
import com.alibaba.cloud.ai.studio.core.workflow.trace.service.WorkflowTraceManager;
import com.alibaba.cloud.ai.studio.core.workflow.trace.store.WorkflowTraceStore;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentResponse;
import com.alibaba.cloud.ai.studio.runtime.domain.app.ApplicationVersion;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.Usage;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Edge;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeResult;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeStatusEnum;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import org.mockito.MockMakers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowSpanRegressionTest {
    private WorkflowTraceRegistry registry;
    private WorkflowTraceStore store;
    private WorkflowTraceManager traces;
    private ConversationManager conversations;
    private WorkflowContext context;
    private WorkflowTraceContext trace;

    @BeforeEach
    void setup() {
        registry = new WorkflowTraceRegistry();
        store = stub(WorkflowTraceStore.class);
        traces = new WorkflowTraceManager(registry, store);
        conversations = stub(ConversationManager.class);
        context = new WorkflowContext();
        context.setInvokeSource("api");
        context.setTaskStatus(NodeStatusEnum.EXECUTING.getCode());
        ApplicationVersion version = new ApplicationVersion();
        version.setAppId("span-regression");
        trace = traces.startTrace(version, context, List.of());
    }

    @AfterEach
    void threadLocalIsClean() {
        assertNull(SpanReporter.current());
        SpanReporter.clear();
    }

    @Test
    void schedulerRecordsEveryNodeEvenWithoutAop() {
        Node start = node("Start_1", "Start");
        Node end = node("End_1", "End");
        WorkflowExecuteManager execution = execution(false, start, end);
        runNode(execution, start);
        runNode(execution, end);
        traces.finishIfNecessary(context);
        assertEquals(2, trace.getSpanCount());
        assertNotNull(trace.getRootSpanId());
        verify(store).save(trace);
    }

    @Test
    void conversationAndSpanAdviceRunTogetherWithoutDuplicateSpans() {
        Node start = node("Start_1", "Start");
        Node end = node("End_1", "End");
        WorkflowExecuteManager execution = execution(true, start, end);
        runNode(execution, start);
        runNode(execution, end);
        traces.finishIfNecessary(context);
        assertEquals(2, trace.getSpanCount());
        verify(conversations, times(1)).saveWorkflowAssistantMessage(context);
        verify(store, times(1)).save(trace);
    }

    @Test
    void autoEndHasOwnSpanAndStillSavesConversation() {
        Node output = node("Output_1", "Output");
        WorkflowExecuteManager execution = execution(true, output);
        runNode(execution, output);
        ReflectionTestUtils.invokeMethod(execution, "executeAutoEnd", graph(output), context,
                context.getNodeResultMap().get(output.getId()));
        traces.finishIfNecessary(context);
        assertEquals(2, trace.getSpanCount());
        assertTrue(trace.getSpans().stream().anyMatch(s -> "End_Auto_Output_1".equals(s.getNodeId())));
        verify(conversations, times(1)).saveWorkflowAssistantMessage(context);
    }

    @Test
    void queuedWorkPreventsEarlyPersistenceBetweenNodes() throws Exception {
        WorkflowTraceContext reservation = traces.retainExecution(context);
        traces.finishIfNecessary(context);
        verifyNoInteractions(store);
        CompletableFuture.runAsync(() -> {
            try (WorkflowNodeTraceScope ignored = WorkflowNodeTraceScope.open(traces, context, node("LLM_1", "LLM"))) {
                result(context, node("LLM_1", "LLM"));
            }
            assertNull(SpanReporter.current());
            traces.releaseExecution(reservation);
        }).get(5, TimeUnit.SECONDS);
        assertEquals(1, trace.getSpanCount());
        verify(store).save(trace);
        assertNull(registry.get(trace.getTraceId()));
    }

    @Test
    void sseCloseDoesNotLoseModelDetailsOrLaterNodes() {
        context.enableStreamEvents().subscribe();
        AspectJProxyFactory factory = new AspectJProxyFactory(new ModelFixture());
        factory.setProxyTargetClass(true);
        factory.addAspect(new ModelCallTraceAspect(traces));
        ModelFixture model = factory.getProxy();
        Node llm = node("LLM_1", "LLM");
        try (WorkflowNodeTraceScope ignored = WorkflowNodeTraceScope.open(traces, context, llm)) {
            model.stream("provider", "model").blockLast();
            context.closeStreamEvents();
            result(context, llm);
        }
        Node api = node("API_1", "Api");
        try (WorkflowNodeTraceScope ignored = WorkflowNodeTraceScope.open(traces, context, api)) {
            result(context, api);
        }
        traces.finishIfNecessary(context);
        verify(store, timeout(2000)).save(trace);
        assertEquals(3, trace.getSpanCount());
        assertEquals(1, trace.getModelCallCount());
        assertEquals(5, trace.getTotalTokens());
        WorkflowSpanContext modelSpan = trace.getSpans().stream()
                .filter(s -> "MODEL_CALL".equals(s.getSpanKind())).findFirst().orElseThrow();
        assertEquals("provider-id", modelSpan.getAttributes().get("provider_response_id"));
        assertTrue(trace.getSpans().stream().anyMatch(s -> s.getSpanId().equals(modelSpan.getParentSpanId())));
        verify(store).save(trace);
    }

    @Test
    void failedStoreDoesNotChangeExecutionResultAndCanRetry() {
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(store).save(trace);
        Node node = node("Start_1", "Start");
        try (WorkflowNodeTraceScope ignored = WorkflowNodeTraceScope.open(traces, context, node)) {
            result(context, node);
        }
        assertDoesNotThrow(() -> traces.finishIfNecessary(context));
        assertSame(trace, registry.get(trace.getTraceId()));
        assertFalse(trace.getPersisted().get());
        traces.finishIfNecessary(context);
        verify(store, times(2)).save(trace);
        assertEquals(1, trace.getSpanCount());
    }

    private static <T> T stub(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.SUBCLASS));
    }

    private WorkflowExecuteManager execution(boolean proxied, Node... nodes) {
        AbstractExecuteProcessor processor = stub(AbstractExecuteProcessor.class);
        doAnswer(call -> {
            result(call.getArgument(2), call.getArgument(1));
            return null;
        }).when(processor).execute(any(), any(), any());
        doAnswer(call -> {
            result(call.getArgument(2), call.getArgument(1));
            return null;
        }).when(processor).handleNodeResult(any(), any(), any(), any(), anyLong());
        if (proxied) {
            AspectJProxyFactory factory = new AspectJProxyFactory(processor);
            factory.setProxyTargetClass(true);
            factory.addAspect(new ConversationPersistenceAspect(conversations));
            factory.addAspect(new WorkflowSpanAspect(traces));
            processor = factory.getProxy();
        }
        WorkflowExecuteManager execution = new WorkflowExecuteManager(
                Map.of("StartExecuteProcessor", processor, "EndExecuteProcessor", processor,
                        "OutputExecuteProcessor", processor),
                stub(WorkflowInnerService.class), stub(ChatMemory.class), stub(CommonConfig.class));
        ReflectionTestUtils.setField(execution, "workflowTraceManager", traces);
        ReflectionTestUtils.setField(execution, "conversationManager", conversations);
        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(List.of(nodes));
        context.setWorkflowConfig(config);
        return execution;
    }

    private void runNode(WorkflowExecuteManager execution, Node node) {
        ReflectionTestUtils.invokeMethod(execution, "executeNodeWork", graph(node), node.getId(), context);
    }

    private static DirectedAcyclicGraph<String, Edge> graph(Node node) {
        DirectedAcyclicGraph<String, Edge> graph = new DirectedAcyclicGraph<>(Edge.class);
        graph.addVertex(node.getId());
        return graph;
    }

    private static Node node(String id, String type) {
        Node node = new Node();
        node.setId(id);
        node.setName(id);
        node.setType(type);
        return node;
    }

    private static void result(WorkflowContext context, Node node) {
        NodeResult result = new NodeResult();
        result.setNodeId(node.getId());
        result.setNodeType(node.getType());
        result.setNodeStatus(NodeStatusEnum.SUCCESS.getCode());
        result.setInput("plain input");
        result.setOutput("plain output");
        context.getNodeResultMap().put(node.getId(), result);
        if ("End".equals(node.getType())) {
            context.setTaskStatus(NodeStatusEnum.SUCCESS.getCode());
        }
    }

    public static class ModelFixture {
        @WorkflowModelCall
        public Flux<AgentResponse> stream(String provider, String model) {
            AgentResponse response = new AgentResponse();
            Usage usage = new Usage();
            usage.setPromptTokens(2);
            usage.setCompletionTokens(3);
            usage.setTotalTokens(5);
            response.setUsage(usage);
            response.setProviderResponseId("provider-id");
            return Flux.just(response).publishOn(Schedulers.boundedElastic());
        }
    }
}
