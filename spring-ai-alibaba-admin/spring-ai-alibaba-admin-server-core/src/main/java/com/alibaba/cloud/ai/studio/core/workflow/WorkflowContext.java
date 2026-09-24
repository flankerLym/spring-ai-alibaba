/*
 * Copyright 2024-2026 the original author or authors.
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
package com.alibaba.cloud.ai.studio.core.workflow;

import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.Usage;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.InvokeSourceEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeResult;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeStatusEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.Maps;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Context for workflow execution and management
 *
 * @since 1.0.0.3
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
public class WorkflowContext extends RequestContext {

	/**
	 * Workflow Trace ID
	 */
	private String traceId;
	/** Application identifier */
	private String appId;

	/** Task identifier */
	private String taskId;

	/** Conversation identifier */
	private String conversationId;

	/** Current status of the task */
	private String taskStatus;

	/** Result of the task execution */
	private String taskResult;

	/** Error code if task fails */
	private String errorCode;

	/** Error information if task fails */
	private String errorInfo;

	/** Error object containing detailed error information */
	private Error error;

	/** Map of sub-workflow contexts */
	private HashMap<String, WorkflowContext> subWorkflowContextMap = new HashMap<>();

	/** Source of invocation: api or console */
	private String invokeSource = InvokeSourceEnum.api.getCode();

	/** Collection of user input parameters, e.g., ${user.abc} */
	private Map<String, Object> userMap = Maps.newHashMap();

	/** Collection of system parameters, e.g., ${sys.abc} */
	private Map<String, Object> sysMap = Maps.newHashMap();

	/** Workflow configuration information */
	private WorkflowConfig workflowConfig;

	/** Cache for intermediate variables used in variable substitution */
	private ConcurrentHashMap<String, Object> variablesMap = new ConcurrentHashMap<>();

	/**
	 * Cache for node execution results, used for debugging and node evaluation.
	 *
	 * The observable map preserves the existing ConcurrentHashMap contract while also
	 * notifying the transient runtime event channel whenever processors replace a
	 * NodeResult. This means existing processor code does not need to know about SSE.
	 */
	private ConcurrentHashMap<String, NodeResult> nodeResultMap = new ObservableNodeResultMap(this);

	/** List of execution order */
	private CopyOnWriteArrayList<String> executeOrderList = new CopyOnWriteArrayList<>();

	/** Lock to ensure single execution of a node at a time */
	@JsonIgnore
	private transient Lock lock = new ReentrantLock();

	/** Set of sub-task IDs (currently only used by agentgroup) */
	private Set<String> subTaskIdSet = new CopyOnWriteArraySet<>();

	/** Usage statistics */
	private List<Usage> usages;

	/** API key identifier */
	private String apikeyId;

	/** Flag indicating if streaming is enabled */
	private boolean stream;

	/** End time of the workflow */
	private long endTime;

	/** Time of first response */
	private long firstResponseTime;

	/** Version number for conflict detection and merging */
	private long version = 1L;

	/*
	 * Runtime-only event state. These fields must never enter Redis/JSON/deep-copy
	 * payloads. They are recreated lazily for each live workflow execution.
	 */
	@JsonIgnore
	private transient Sinks.Many<WorkflowEvent> streamEventSink;

	@JsonIgnore
	private transient Object nodeUpdateMonitor = new Object();

	@JsonIgnore
	private transient ConcurrentHashMap<String, AtomicLong> nodeUpdateVersions = new ConcurrentHashMap<>();

	/**
	 * Enable the event stream consumed by WorkflowServiceImpl.
	 *
	 * Unicast is intentional: one API workflow execution has exactly one stream consumer.
	 * Events emitted before the Reactor subscription is attached are buffered instead of
	 * being lost.
	 */
	public synchronized Flux<WorkflowEvent> enableStreamEvents() {
		ensureRuntimeState();
		if (streamEventSink == null) {
			streamEventSink = Sinks.many().unicast().onBackpressureBuffer();
		}
		return streamEventSink.asFlux();
	}

	/**
	 * Release the transient event channel. Workflow execution itself is not cancelled;
	 * this preserves the existing behavior where side-effect nodes may continue after a
	 * client disconnects.
	 */
	public synchronized void closeStreamEvents() {
		Sinks.Many<WorkflowEvent> sink = streamEventSink;
		streamEventSink = null;
		if (sink != null) {
			sink.tryEmitComplete();
		}
	}

	/**
	 * Publish an in-place NodeResult update. Output/End processors use this when they
	 * mutate an existing NodeResult instead of replacing it in nodeResultMap.
	 */
	public void publishNodeResult(NodeResult nodeResult) {
		onNodeResultChanged(nodeResult);
	}

	/**
	 * Returns the current update version for one upstream node.
	 */
	public long getNodeUpdateVersion(String nodeId) {
		ensureRuntimeState();
		AtomicLong versionCounter = nodeUpdateVersions.get(nodeId);
		return versionCounter == null ? 0L : versionCounter.get();
	}

	/**
	 * Wait until an upstream node changes instead of busy-spinning.
	 *
	 * A bounded wait is kept as a safety fallback for legacy processors that may mutate a
	 * NodeResult in place without publishing an update.
	 */
	public long awaitNodeUpdate(String nodeId, long observedVersion, long timeoutMillis) {
		ensureRuntimeState();

		long currentVersion = getNodeUpdateVersion(nodeId);
		if (currentVersion != observedVersion) {
			return currentVersion;
		}

		Object monitor = nodeUpdateMonitor;
		synchronized (monitor) {
			currentVersion = getNodeUpdateVersion(nodeId);
			if (currentVersion != observedVersion) {
				return currentVersion;
			}
			try {
				monitor.wait(Math.max(timeoutMillis, 1L));
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return getNodeUpdateVersion(nodeId);
		}
	}

	/**
	 * Explicit setter so task lifecycle changes can wake the API stream immediately.
	 * FAIL/PAUSE are only finalized by the consumer after their NodeResult is available,
	 * preserving the existing error/pause payload semantics.
	 */
	public void setTaskStatus(String taskStatus) {
		this.taskStatus = taskStatus;
		emitStreamEvent(WorkflowEvent.taskStatus(taskStatus));
	}

	/**
	 * Re-wrap maps supplied by Jackson/Redis so node-result updates remain observable in
	 * live contexts. No events are emitted while the existing entries are copied.
	 */
	public void setNodeResultMap(ConcurrentHashMap<String, NodeResult> nodeResultMap) {
		this.nodeResultMap = new ObservableNodeResultMap(this, nodeResultMap);
	}

	private void onNodeResultChanged(NodeResult nodeResult) {
		if (nodeResult == null || nodeResult.getNodeId() == null) {
			return;
		}

		ensureRuntimeState();
		nodeUpdateVersions.computeIfAbsent(nodeResult.getNodeId(), key -> new AtomicLong()).incrementAndGet();

		Object monitor = nodeUpdateMonitor;
		synchronized (monitor) {
			monitor.notifyAll();
		}

		/*
		 * Send business-node updates and failures into the API event channel. Regular LLM
		 * token updates only wake dependent Output nodes and do not need to traverse the
		 * SSE event pipeline themselves.
		 */
		boolean businessNode = NodeTypeEnum.OUTPUT.getCode().equals(nodeResult.getNodeType())
				|| NodeTypeEnum.END.getCode().equals(nodeResult.getNodeType())
				|| NodeTypeEnum.INPUT.getCode().equals(nodeResult.getNodeType());
		boolean failedNode = NodeStatusEnum.FAIL.getCode().equals(nodeResult.getNodeStatus());
		if (businessNode || failedNode) {
			emitStreamEvent(WorkflowEvent.nodeResult(snapshot(nodeResult)));
		}
	}

	private synchronized void emitStreamEvent(WorkflowEvent event) {
		if (event == null || streamEventSink == null) {
			return;
		}
		streamEventSink.tryEmitNext(event);
	}

	private synchronized void ensureRuntimeState() {
		if (nodeUpdateMonitor == null) {
			nodeUpdateMonitor = new Object();
		}
		if (nodeUpdateVersions == null) {
			nodeUpdateVersions = new ConcurrentHashMap<>();
		}
	}

	private void rebindNodeResultMap() {
		this.nodeResultMap = new ObservableNodeResultMap(this, this.nodeResultMap);
	}

	private static NodeResult snapshot(NodeResult source) {
		NodeResult copy = new NodeResult();
		copy.setNodeId(source.getNodeId());
		copy.setNodeName(source.getNodeName());
		copy.setNodeType(source.getNodeType());
		copy.setNodeStatus(source.getNodeStatus());
		copy.setNodeExecTime(source.getNodeExecTime());
		copy.setInput(source.getInput());
		copy.setOutput(source.getOutput());
		copy.setOutputType(source.getOutputType());
		copy.setIncrementOutput(source.getIncrementOutput());
		copy.setErrorCode(source.getErrorCode());
		copy.setErrorInfo(source.getErrorInfo());
		copy.setError(source.getError());
		copy.setUsages(source.getUsages());
		copy.setExt(source.getExt());
		return copy;
	}

	/**
	 * Creates a deep copy of the workflow context
	 * @param context The context to copy
	 * @return A deep copy of the context
	 */
	public static WorkflowContext deepCopy(WorkflowContext context) {
		try {
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
			objectOutputStream.writeObject(context);
			ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
			ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
			WorkflowContext copy = (WorkflowContext) objectInputStream.readObject();
			// Reinitialize runtime-only objects after deserialization
			copy.lock = new ReentrantLock();
			copy.streamEventSink = null;
			copy.nodeUpdateMonitor = new Object();
			copy.nodeUpdateVersions = new ConcurrentHashMap<>();
			copy.rebindNodeResultMap();
			// 确保版本号被正确复制
			if (copy.getVersion() == 0) {
				copy.setVersion(1L);
			}
			return copy;
		}
		catch (Exception e) {
			log.error("WorkflowContext deepCopy error:{}", JsonUtils.toJson(context), e);
			return null;
		}
	}

	@Data
	public static class WorkflowEvent {

		public enum Type {
			NODE_RESULT, TASK_STATUS
		}

		private Type type;

		private NodeResult nodeResult;

		private String taskStatus;

		public static WorkflowEvent nodeResult(NodeResult nodeResult) {
			WorkflowEvent event = new WorkflowEvent();
			event.setType(Type.NODE_RESULT);
			event.setNodeResult(nodeResult);
			return event;
		}

		public static WorkflowEvent taskStatus(String taskStatus) {
			WorkflowEvent event = new WorkflowEvent();
			event.setType(Type.TASK_STATUS);
			event.setTaskStatus(taskStatus);
			return event;
		}

	}

	private static class ObservableNodeResultMap extends ConcurrentHashMap<String, NodeResult> {

		private static final long serialVersionUID = 1L;

		private transient WorkflowContext owner;

		ObservableNodeResultMap() {
		}

		ObservableNodeResultMap(WorkflowContext owner) {
			this.owner = owner;
		}

		ObservableNodeResultMap(WorkflowContext owner, Map<String, NodeResult> source) {
			this.owner = owner;
			if (source != null && !source.isEmpty()) {
				super.putAll(source);
			}
		}

		@Override
		public NodeResult put(String key, NodeResult value) {
			NodeResult previous = super.put(key, value);
			notifyOwner(value);
			return previous;
		}

		@Override
		public void putAll(Map<? extends String, ? extends NodeResult> map) {
			if (map == null) {
				return;
			}
			map.forEach(this::put);
		}

		@Override
		public NodeResult putIfAbsent(String key, NodeResult value) {
			NodeResult previous = super.putIfAbsent(key, value);
			if (previous == null) {
				notifyOwner(value);
			}
			return previous;
		}

		@Override
		public NodeResult replace(String key, NodeResult value) {
			NodeResult previous = super.replace(key, value);
			if (previous != null) {
				notifyOwner(value);
			}
			return previous;
		}

		@Override
		public boolean replace(String key, NodeResult oldValue, NodeResult newValue) {
			boolean replaced = super.replace(key, oldValue, newValue);
			if (replaced) {
				notifyOwner(newValue);
			}
			return replaced;
		}

		private void notifyOwner(NodeResult value) {
			if (owner != null) {
				owner.onNodeResultChanged(value);
			}
		}

	}

}
