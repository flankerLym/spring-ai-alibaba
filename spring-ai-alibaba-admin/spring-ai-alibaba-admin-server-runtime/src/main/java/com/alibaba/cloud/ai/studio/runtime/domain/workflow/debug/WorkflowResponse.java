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
package com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug;

import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.ChatMessage;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.WorkflowStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Workflow response.
 *
 * Runtime/debug fields are kept in the Java object for internal processing, while JSON
 * serialization only exposes business-facing fields.
 *
 * @author guning.lt
 * @since 1.0.0.3
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowResponse implements Serializable {

	/** Internal request trace id, never exposed as business output. */
	@JsonIgnore
	private String requestId;

	/** Business conversation identifier. */
	private String conversationId;

	/** Business task identifier. */
	private String taskId;

	/** Internal node diagnostics. */
	@JsonIgnore
	private String nodeId;

	@JsonIgnore
	private String nodeName;

	@JsonIgnore
	private String nodeType;

	@JsonIgnore
	private String nodeStatus;

	@JsonIgnore
	private Integer nodeMsgSeqId;

	@JsonIgnore
	private Boolean nodeIsCompleted;

	/** Business workflow status. */
	private WorkflowStatus status;

	/** Internal message model; expose content only. */
	@JsonIgnore
	private ChatMessage message;

	/** Internal error model; expose code/message only. */
	@JsonIgnore
	private Error error;

	@JsonProperty("content")
	public Object getBusinessContent() {
		return message == null ? null : message.getContent();
	}

	@JsonProperty("errorCode")
	public String getBusinessErrorCode() {
		return error == null ? null : error.getCode();
	}

	@JsonProperty("errorMessage")
	public String getBusinessErrorMessage() {
		return error == null ? null : error.getMessage();
	}

	@JsonIgnore
	public boolean isSuccess() {
		return error == null;
	}

}
