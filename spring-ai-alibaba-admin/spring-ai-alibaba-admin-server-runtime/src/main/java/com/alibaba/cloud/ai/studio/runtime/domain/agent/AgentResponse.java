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

package com.alibaba.cloud.ai.studio.runtime.domain.agent;

import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.ChatMessage;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.Usage;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Response model for agent completion requests.
 *
 * Runtime/debug fields stay available internally, but external JSON is limited to
 * business-facing fields.
 *
 * @since 1.0.0.3
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse implements Serializable {

	@JsonIgnore
	private String requestId;

	@JsonIgnore
	private String traceId;

	private String conversationId;

	private AgentStatus status;

	@JsonIgnore
	private String index;

	@JsonIgnore
	private ChatMessage message;

	@JsonIgnore
	private Long created;

	@JsonIgnore
	private String model;

	@JsonIgnore
	private Usage usage;

	@JsonIgnore
	private Error error;

	@JsonIgnore
	private String providerResponseId;

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
