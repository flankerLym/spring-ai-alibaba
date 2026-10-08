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

import com.alibaba.cloud.ai.studio.runtime.domain.chat.ChatMessage;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Workflow request.
 *
 * Canonical external field names use lower camel case. Legacy snake_case names remain
 * accepted as aliases for backward compatibility.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowRequest implements Serializable {

	@JsonProperty("appId")
	@JsonAlias("app_id")
	private String appId;

	@JsonProperty("conversationId")
	@JsonAlias("conversation_id")
	private String conversationId;

	@JsonProperty("requestId")
	@JsonAlias("request_id")
	private String requestId;

	@JsonProperty("messages")
	private List<ChatMessage> messages;

	@JsonProperty("stream")
	private Boolean stream = false;

	@JsonProperty("draft")
	private Boolean draft = false;

	@JsonProperty("inputParams")
	@JsonAlias("input_params")
	private List<TaskRunParam> inputParams;

}
