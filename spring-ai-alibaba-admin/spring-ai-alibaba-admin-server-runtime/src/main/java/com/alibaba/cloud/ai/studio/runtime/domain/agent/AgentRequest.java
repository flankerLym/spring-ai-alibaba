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

import com.alibaba.cloud.ai.studio.runtime.domain.chat.ChatMessage;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** Agent request. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AgentRequest implements Serializable {

    @JsonProperty("appId")
    @JsonAlias("app_id")
    private String appId;

    @JsonProperty("userId")
    @JsonAlias("user_id")
    private String userId;

    @JsonProperty("conversationId")
    @JsonAlias("conversation_id")
    private String conversationId;

    @JsonProperty("messages")
    private List<ChatMessage> messages;

    @JsonProperty("stream")
    private Boolean stream = false;

    @JsonProperty("promptVariables")
    @JsonAlias("prompt_variables")
    private Map<String, String> promptVariables;

    @JsonProperty("extraParams")
    @JsonAlias("extra_params")
    private Map<String, Object> extraPrams;

    @JsonProperty("draft")
    @JsonAlias("is_draft")
    private boolean draft = false;

    /** Backward-compatible constructor used by existing internal callers/tests. */
    public AgentRequest(String appId, String conversationId, List<ChatMessage> messages, Boolean stream,
            Map<String, String> promptVariables, Map<String, Object> extraPrams, boolean draft) {
        this.appId = appId;
        this.conversationId = conversationId;
        this.messages = messages;
        this.stream = stream;
        this.promptVariables = promptVariables;
        this.extraPrams = extraPrams;
        this.draft = draft;
    }
}
