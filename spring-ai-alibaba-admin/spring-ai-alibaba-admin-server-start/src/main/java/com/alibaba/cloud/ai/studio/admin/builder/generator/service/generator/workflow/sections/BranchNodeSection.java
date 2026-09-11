/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.admin.builder.generator.service.generator.workflow.sections;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.alibaba.cloud.ai.studio.admin.builder.generator.model.VariableSelector;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.VariableType;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.Case;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.Edge;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.Node;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.NodeType;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.nodedata.BranchNodeData;
import com.alibaba.cloud.ai.studio.admin.builder.generator.service.generator.workflow.NodeSection;
import org.springframework.stereotype.Component;

@Component
public class BranchNodeSection implements NodeSection<BranchNodeData> {

    @Override
    public boolean support(NodeType nodeType) {
        return NodeType.BRANCH.equals(nodeType);
    }

    @Override
    public String render(Node node, String varName) {
        return String.format(
                "// —— BranchNode [%s] ——%n"
                        + "stateGraph.addNode(\"%s\", AsyncNodeAction.node_async(state -> Map.of()));%n%n",
                node.getId(), varName);
    }

    @Override
    public String renderEdges(BranchNodeData branchNodeData, List<Edge> edges) {
        String srcVar = branchNodeData.getVarName();
        StringBuilder sb = new StringBuilder();
        List<Case> cases = branchNodeData.getCases();

        AtomicInteger count = new AtomicInteger(1);
        Map<String, String> caseIdToName = cases.stream()
                .map(Case::getId)
                .collect(Collectors.toUnmodifiableMap(id -> id, id -> {
                    if (id.equalsIgnoreCase("default")
                            || id.equalsIgnoreCase("true")
                            || id.equalsIgnoreCase("false")) {
                        return id;
                    }
                    return "case_" + count.getAndIncrement();
                }));

        StringBuilder conditionsBuffer = new StringBuilder();
        for (Case c : cases) {
            String logicalOperator = " " + c.getLogicalOperator().getCodeValue() + " ";
            List<String> expressions = c.getConditions().stream().map(condition -> {
                String constValue = condition.getValue();
                if (condition.getReferenceValue() != null
                        && (VariableType.STRING.equals(condition.getVarType())
                        || VariableType.FILE.equals(condition.getVarType()))) {
                    constValue = "\"" + constValue + "\"";
                }

                String objName = generateSafeVariableAccess(condition);
                return condition.getComparisonOperator().convert(objName, constValue);
            }).toList();

            conditionsBuffer.append("if(")
                    .append(String.join(logicalOperator, expressions))
                    .append(") {\n")
                    .append(String.format("return \"%s\";", caseIdToName.get(c.getId())))
                    .append("}\n");
        }

        conditionsBuffer.append(String.format(
                "return \"%s\";", branchNodeData.getDefaultCase()));

        Map<String, String> edgeCaseMap = edges.stream()
                .collect(Collectors.toMap(
                        e -> caseIdToName.getOrDefault(e.getSourceHandle(), e.getSourceHandle()),
                        Edge::getTarget));

        String edgeCaseMapStr = "Map.of(" + edgeCaseMap.entrySet()
                .stream()
                .flatMap(e -> Stream.of(e.getKey(), e.getValue()))
                .map(v -> String.format("\"%s\"", v))
                .collect(Collectors.joining(", ")) + ")";

        sb.append("stateGraph.addConditionalEdges(\"")
                .append(srcVar)
                .append("\", edge_async(state -> {\n")
                .append(conditionsBuffer)
                .append("}), ")
                .append(edgeCaseMapStr)
                .append(");\n\n");

        return sb.toString();
    }

    private String generateSafeVariableAccess(Case.Condition condition) {
        VariableType varType = condition.getVarType();
        String variablePath = buildVariablePath(condition);

        return switch (varType) {
            case FILE -> {
                VariableSelector selector = condition.getTargetSelector();
                boolean accessExtension = selector != null
                        && ((selector.getLabel() != null && selector.getLabel().contains("extension"))
                        || (selector.getName() != null && selector.getName().contains("extension")));

                if (accessExtension) {
                    yield String.format(
                            "state.value(\"%s\", String.class).orElse(\"\")", variablePath);
                }

                yield String.format(
                        "state.value(\"%s\", java.io.File.class).map(file -> { "
                                + "String name = file.getName(); "
                                + "int dotIndex = name.lastIndexOf('.'); "
                                + "return dotIndex > 0 ? name.substring(dotIndex) : \"\"; "
                                + "}).orElse(\"\")",
                        variablePath);
            }
            case STRING -> String.format(
                    "state.value(\"%s\", String.class).orElse(\"\")", variablePath);
            case NUMBER -> String.format(
                    "state.value(\"%s\", Number.class).orElse(0)", variablePath);
            case BOOLEAN -> String.format(
                    "state.value(\"%s\", Boolean.class).orElse(false)", variablePath);
            case ARRAY_FILE, ARRAY_NUMBER, ARRAY_STRING, ARRAY_OBJECT, ARRAY_BOOLEAN, ARRAY ->
                    String.format("state.value(\"%s\", List.class).orElse(List.of())", variablePath);
            case OBJECT -> String.format(
                    "state.value(\"%s\", Object.class).orElse(null)", variablePath);
        };
    }

    private String buildVariablePath(Case.Condition condition) {
        VariableSelector variableSelector = condition.getTargetSelector();
        if (variableSelector == null) {
            return "unknown";
        }
        return Optional.ofNullable(variableSelector.getNameInCode()).orElse("unknown");
    }

    @Override
    public List<String> getImports() {
        return List.of("static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async");
    }

}
