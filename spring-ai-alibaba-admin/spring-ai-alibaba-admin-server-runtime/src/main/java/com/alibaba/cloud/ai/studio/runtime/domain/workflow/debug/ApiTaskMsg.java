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

import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE message for workflow execution.
 *
 * The object still carries node-level diagnostics internally, but those fields are not
 * serialized to external clients. External JSON uses camelCase business fields only.
 */
@Data
public class ApiTaskMsg implements Serializable {

	/**
	 * @see Event
	 */
	private String event;

	private String taskId;

	private String conversationId;

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

	@JsonIgnore
	private Map<String, Object> ext;

	@JsonIgnore
	private String contentType = "text";

	@JsonIgnore
	private String textContent;

	@JsonIgnore
	private String error_code;

	@JsonIgnore
	private String error_message;

	@JsonIgnore
	private Object pause_data;

	/**
	 * @see PauseType
	 */
	private String pauseType;

	@JsonProperty("content")
	public String getBusinessContent() {
		return textContent;
	}

	@JsonProperty("errorCode")
	public String getErrorCode() {
		return error_code;
	}

	@JsonProperty("errorMessage")
	public String getErrorMessage() {
		return error_message;
	}

	/**
	 * Pause information is a business interaction contract, so only expose the information
	 * required to resume the workflow. Node name/type/status are diagnostics and remain
	 * hidden.
	 */
	@JsonProperty("pauseData")
	public Object getBusinessPauseData() {
		if (!(pause_data instanceof Map<?, ?> rawMap)) {
			return pause_data;
		}

		Map<String, Object> result = new LinkedHashMap<>();

		Object nodeIdValue = rawMap.get("node_id");
		if (nodeIdValue != null) {
			result.put("resumeNodeId", nodeIdValue);
		}

		Object inputParams = rawMap.get("input_params");
		if (inputParams != null) {
			if (inputParams instanceof String json && JsonUtils.isValidJson(json)) {
				result.put("inputParams", JsonUtils.fromJson(json));
			}
			else {
				result.put("inputParams", inputParams);
			}
		}

		return result.isEmpty() ? null : result;
	}

	public enum Event {

		Message, Error, Finished, Paused

	}

	public enum PauseType {

		InputNodeInterrupt

	}

}
