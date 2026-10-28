/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.admin.builder.generator.service.generator.workflow.sections;

import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.Node;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.NodeType;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.nodedata.LLMNodeData;
import com.alibaba.cloud.ai.studio.admin.builder.generator.service.dsl.DSLDialectType;
import com.alibaba.cloud.ai.studio.admin.builder.generator.service.generator.workflow.NodeSection;
import com.alibaba.cloud.ai.studio.admin.builder.generator.utils.ObjectToCodeUtil;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LLMNodeSection implements NodeSection<LLMNodeData> {

    @Override
    public boolean support(NodeType nodeType) {
        return NodeType.LLM.equals(nodeType);
    }

    @Override
    public String render(Node node, String varName) {
        LLMNodeData nodeData = (LLMNodeData) node.getData();
        return String.format("""
                // —— LLMNode [%s] —— provider=%s, model=%s
                stateGraph.addNode("%s", AsyncNodeAction.node_async(
                    createLLMNodeAction(modelRegistry, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ));

                """,
                node.getId(),
                nodeData.getProviderName(),
                nodeData.getChatModeName(),
                varName,
                ObjectToCodeUtil.toCode(nodeData.getProviderName()),
                ObjectToCodeUtil.toCode(nodeData.getChatModeName()),
                ObjectToCodeUtil.toCode(nodeData.getModeParams()),
                ObjectToCodeUtil.toCode(nodeData.getMessageTemplates()),
                ObjectToCodeUtil.toCode(nodeData.getMemoryKey()),
                ObjectToCodeUtil.toCode(nodeData.getMaxRetryCount()),
                ObjectToCodeUtil.toCode(nodeData.getRetryIntervalMs()),
                ObjectToCodeUtil.toCode(nodeData.getDefaultOutput()),
                ObjectToCodeUtil.toCode(nodeData.getErrorNextNode()),
                ObjectToCodeUtil.toCode(nodeData.getOutputKeyPrefix()));
    }

    @Override
    public String assistMethodCode(DSLDialectType dialectType) {
        return String.format("""
                private record MessageTemplate(String template, List<String> keys, MessageType type) {

                    private Object resolveValue(OverAllState state, String key) {
                        Object direct = state.value(key).orElse(null);
                        if (direct != null) {
                            return direct;
                        }

                        // Common Dify shorthand used by memory.query_prompt_template.
                        if ("user_problem".equals(key)) {
                            Object value = state.value("conversation_user_problem_raw").orElse(null);
                            if (value != null) {
                                return value;
                            }
                        }

                        // Bare placeholders such as {{child_profile_summary}} commonly
                        // refer to Dify conversation variables.
                        if (!key.startsWith("conversation_") && !key.startsWith("sys_")) {
                            Object value = state.value("conversation_" + key).orElse(null);
                            if (value != null) {
                                return value;
                            }
                        }

                        return "";
                    }

                    public Message render(OverAllState state) {
                        String text = template == null ? "" : template;
                        for (String key : keys) {
                            Object value = resolveValue(state, key);
                            // Replace double-brace form first, otherwise replacing the
                            // inner single-brace token leaves one pair of braces behind.
                            text = text.replace("{{" + key + "}}", String.valueOf(value));
                            text = text.replace("{" + key + "}", String.valueOf(value));
                        }
                        return switch (type) {
                            case USER -> new UserMessage(text);
                            case SYSTEM -> new SystemMessage(text);
                            case TOOL -> throw new UnsupportedOperationException("Tool message not supported");
                            case ASSISTANT -> new AssistantMessage(text);
                        };
                    }
                }

                private NodeAction createLLMNodeAction(
                        NodeChatModelRegistry modelRegistry,
                        String providerName,
                        String chatModelName,
                        Map<String, Object> modeParams,
                        List<MessageTemplate> messageTemplates,
                        String memoryKey,
                        Integer maxRetryCount,
                        Integer retryIntervalMs,
                        String defaultOutput,
                        String errorNextNode,
                        String outputKeyPrefix) {

                    ChatModel chatModel = modelRegistry.get(new NodeModelConfig(providerName, chatModelName));
                    var chatOptionsBuilder = OpenAiChatOptions.builder().model(chatModelName);

                    Map<String, Object> safeParams = Optional.ofNullable(modeParams).orElse(Map.of());
                    Optional.ofNullable(safeParams.get("temperature"))
                            .filter(Number.class::isInstance)
                            .map(Number.class::cast)
                            .ifPresent(val -> chatOptionsBuilder.temperature(val.doubleValue()));
                    Optional.ofNullable(safeParams.get("seed"))
                            .filter(Number.class::isInstance)
                            .map(Number.class::cast)
                            .ifPresent(val -> chatOptionsBuilder.seed(val.intValue()));
                    Optional.ofNullable(safeParams.get("top_p"))
                            .filter(Number.class::isInstance)
                            .map(Number.class::cast)
                            .ifPresent(val -> chatOptionsBuilder.topP(val.doubleValue()));
                    Optional.ofNullable(safeParams.get("max_tokens"))
                            .filter(Number.class::isInstance)
                            .map(Number.class::cast)
                            .ifPresent(val -> chatOptionsBuilder.maxTokens(val.intValue()));

                    final ChatClient chatClient = ChatClient.builder(chatModel)
                            .defaultOptions(chatOptionsBuilder.build())
                            .build();

                    String nextNodeKey = "next_node";

                    return state -> {
                        int retryCount = Optional.ofNullable(maxRetryCount).orElse(1);
                        int retryInterval = Optional.ofNullable(retryIntervalMs).orElse(1000);
                        Exception lastException = null;

                        while (retryCount-- > 0) {
                            try {
                                List<Message> messages = Optional.ofNullable(messageTemplates)
                                        .orElse(List.of())
                                        .stream()
                                        .map(messageTemplate -> messageTemplate.render(state))
                                        .toList();

                                String content;
                                if (memoryKey == null || memoryKey.isBlank()) {
                                    content = chatClient.prompt().messages(messages).call().content();
                                }
                                else {
                                    Object memory = state.value(memoryKey, List.of());
                                    content = chatClient.prompt()
                                            .system("This is the history of previous requests:\\n" + memory)
                                            .messages(messages)
                                            .call()
                                            .content();
                                }

                                if (content == null) {
                                    throw new IllegalStateException("ChatClient returned null content");
                                }

                                Map<String, Object> result = new HashMap<>(%s);
                                if (memoryKey != null && !memoryKey.isBlank()) {
                                    result.put(memoryKey, content);
                                }
                                return result;
                            }
                            catch (Exception e) {
                                lastException = e;
                                if (retryCount > 0) {
                                    try {
                                        Thread.sleep(retryInterval);
                                    }
                                    catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException("LLM retry interrupted", ie);
                                    }
                                }
                            }
                        }

                        if (defaultOutput != null) {
                            return %s;
                        }
                        if (errorNextNode != null) {
                            return Map.of(nextNodeKey, errorNextNode);
                        }
                        throw new IllegalStateException("LLM node execution failed", lastException);
                    };
                }
                """,
                dialectType.equals(DSLDialectType.DIFY)
                        ? "Map.of(outputKeyPrefix + \"text\", content)"
                        : "Map.of(outputKeyPrefix + \"output\", content, outputKeyPrefix + \"reasoning_content\", content)",
                dialectType.equals(DSLDialectType.DIFY)
                        ? "Map.of(outputKeyPrefix + \"text\", defaultOutput)"
                        : "Map.of(outputKeyPrefix + \"output\", defaultOutput, outputKeyPrefix + \"reasoning_content\", defaultOutput)");
    }

    @Override
    public List<String> getImports() {
        return List.of(
                "org.springframework.ai.chat.messages.Message",
                "org.springframework.ai.chat.messages.AssistantMessage",
                "org.springframework.ai.chat.messages.MessageType",
                "org.springframework.ai.chat.messages.SystemMessage",
                "org.springframework.ai.chat.messages.UserMessage",
                "org.springframework.ai.openai.OpenAiChatOptions",
                "java.util.Optional");
    }

}
