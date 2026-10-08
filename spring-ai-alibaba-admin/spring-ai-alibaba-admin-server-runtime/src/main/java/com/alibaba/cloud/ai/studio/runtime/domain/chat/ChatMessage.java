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

package com.alibaba.cloud.ai.studio.runtime.domain.chat;

import com.alibaba.cloud.ai.studio.runtime.domain.audio.AudioOutput;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Represents a message in a chat conversation.
 *
 * Canonical external field names use lower camel case. Legacy snake_case names remain
 * accepted as aliases for backward compatibility.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage implements Serializable {

	public ChatMessage(MessageRole role, Object content) {
		this.role = role;
		this.content = content;
	}

	@JsonProperty("role")
	private MessageRole role;

	@JsonProperty("contentType")
	@JsonAlias("content_type")
	@Builder.Default
	private ContentType contentType = ContentType.TEXT;

	@JsonProperty("content")
	@JsonDeserialize(using = ChatMessageContentDeserializer.class)
	private Object content;

	@JsonProperty("name")
	private String name;

	@JsonProperty("toolCalls")
	@JsonAlias("tool_calls")
	private List<ToolCall> toolCalls;

	@JsonProperty("audio")
	private AudioOutput audioOutput;

	@JsonProperty("reasoningContent")
	@JsonAlias("reasoning_content")
	private String reasoningContent;

}
