/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.admin.builder.generator.service.generator.workflow.sections;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.Edge;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.Node;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.NodeType;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.nodedata.QuestionClassifierNodeData;
import com.alibaba.cloud.ai.studio.admin.builder.generator.service.dsl.DSLDialectType;
import com.alibaba.cloud.ai.studio.admin.builder.generator.service.generator.workflow.NodeSection;
import com.alibaba.cloud.ai.studio.admin.builder.generator.utils.ObjectToCodeUtil;
import org.springframework.stereotype.Component;

@Component
public class QuestionClassifierNodeSection implements NodeSection<QuestionClassifierNodeData> {

    @Override
    public boolean support(NodeType nodeType) {
        return NodeType.QUESTION_CLASSIFIER.equals(nodeType);
    }

    @Override
    public String render(Node node, String varName) {
        QuestionClassifierNodeData nodeData = (QuestionClassifierNodeData) node.getData();

        return String.format("""
                // —— QuestionClassifierNode [%s] —— provider=%s, model=%s
                stateGraph.addNode("%s", AsyncNodeAction.node_async(
                    createQuestionClassifierAction(modelRegistry, %s, %s, %s, "%s", "%s", %s, %s)
                ));

                """,
                node.getId(),
                nodeData.getProviderName(),
                nodeData.getChatModeName(),
                varName,
                ObjectToCodeUtil.toCode(nodeData.getProviderName()),
                ObjectToCodeUtil.toCode(nodeData.getChatModeName()),
                ObjectToCodeUtil.toCode(nodeData.getModeParams()),
                nodeData.getInputSelector().getNameInCode(),
                nodeData.getOutputKey(),
                ObjectToCodeUtil.toCode(nodeData.getClasses()
                        .stream()
                        .collect(Collectors.toUnmodifiableMap(
                                QuestionClassifierNodeData.ClassConfig::id,
                                QuestionClassifierNodeData.ClassConfig::classTemplate,
                                (a, b) -> b))),
                ObjectToCodeUtil.toCode(List.of(nodeData.getPromptTemplate())));
    }

    @Override
    public String renderEdges(QuestionClassifierNodeData nodeData, List<Edge> edges) {
        Map<String, String> classIdToName = nodeData.getClassIdToName();

        String edgeCode = String.format("""
                state -> {
                    String result = state.value("%s").orElseThrow().toString();
                    %s
                    throw new RuntimeException("invalid output: " + result);
                }
                """,
                nodeData.getOutputKey(),
                nodeData.getClasses()
                        .stream()
                        .map(QuestionClassifierNodeData.ClassConfig::id)
                        .map(id -> String.format("""
                                if ("%s".equals(result)) {
                                    return "%s";
                                }
                                """, id, classIdToName.getOrDefault(id, id)))
                        .collect(Collectors.joining("\n")));

        Map<String, String> caseToTarget = edges.stream()
                .collect(Collectors.toUnmodifiableMap(
                        e -> classIdToName.getOrDefault(e.getSourceHandle(), e.getSourceHandle()),
                        Edge::getTarget));

        return String.format("""
                // render QuestionClassifierNode [%s]'s edge
                stateGraph.addConditionalEdges("%s", AsyncEdgeAction.edge_async(%s), %s);

                """,
                nodeData.getVarName(),
                nodeData.getVarName(),
                edgeCode,
                ObjectToCodeUtil.toCode(caseToTarget));
    }

    @Override
    public String assistMethodCode(DSLDialectType dialectType) {
        return switch (dialectType) {
            case DIFY, STUDIO -> """
                    private NodeAction createQuestionClassifierAction(
                            NodeChatModelRegistry modelRegistry,
                            String providerName,
                            String chatModelName,
                            Map<String, Object> modeParams,
                            String inputKey,
                            String outputKey,
                            Map<String, String> categories,
                            List<String> instructions) {

                        ChatModel chatModel = modelRegistry.get(
                                new NodeModelConfig(providerName, chatModelName));

                        var chatOptionsBuilder = OpenAiChatOptions.builder().model(chatModelName);
                        Map<String, Object> safeParams = Optional.ofNullable(modeParams).orElse(Map.of());

                        Optional.ofNullable(safeParams.get("temperature"))
                                .filter(Number.class::isInstance)
                                .map(Number.class::cast)
                                .ifPresent(val -> chatOptionsBuilder.temperature(val.doubleValue()));
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

                        return QuestionClassifierNode.builder()
                                .chatClient(chatClient)
                                .inputTextKey(inputKey)
                                .outputKey(outputKey)
                                .categories(categories)
                                .classificationInstructions(instructions)
                                .build();
                    }
                    """;
            default -> "";
        };
    }

    @Override
    public List<String> getImports() {
        return List.of(
                "com.alibaba.cloud.ai.graph.node.QuestionClassifierNode",
                "org.springframework.ai.openai.OpenAiChatOptions",
                "java.util.Optional");
    }

}
