package com.alibaba.cloud.ai.studio.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for the Dify Chatflow /chat-messages wire protocol.
 *
 * The existing public route is retained for backward deployment compatibility;
 * this DTO only adapts its request body to the internal workflow request model.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DifyChatRequest implements Serializable {

    private String query;

    private Map<String, Object> inputs = new LinkedHashMap<>();

    @JsonProperty("response_mode")
    private String responseMode = "streaming";

    private String user;

    @JsonProperty("conversation_id")
    private String conversationId;

    private List<Map<String, Object>> files = new ArrayList<>();

    @JsonProperty("auto_generate_name")
    private Boolean autoGenerateName;

    @JsonProperty("workflow_id")
    private String workflowId;

    @JsonProperty("trace_id")
    private String traceId;
}
