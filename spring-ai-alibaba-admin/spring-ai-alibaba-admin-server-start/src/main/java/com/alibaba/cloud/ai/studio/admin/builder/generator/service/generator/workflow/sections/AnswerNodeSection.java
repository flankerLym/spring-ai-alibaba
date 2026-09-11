/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.admin.builder.generator.service.generator.workflow.sections;

import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.Node;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.NodeType;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.nodedata.AnswerNodeData;
import com.alibaba.cloud.ai.studio.admin.builder.generator.service.generator.workflow.NodeSection;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AnswerNodeSection implements NodeSection<AnswerNodeData> {

    @Override
    public boolean support(NodeType nodeType) {
        return NodeType.ANSWER.equals(nodeType);
    }

    @Override
    public String render(Node node, String varName) {
        AnswerNodeData d = (AnswerNodeData) node.getData();
        String id = node.getId();
        StringBuilder sb = new StringBuilder();

        sb.append("// —— AnswerNode [").append(id).append("] ——\n");
        sb.append("AnswerNode ").append(varName).append(" = AnswerNode.builder()\n");

        if (d.getAnswer() != null) {
            sb.append(".answer(\"").append(escape(d.getAnswer())).append("\")\n");
        }

        sb.append(String.format(".outputKey(\"%s\")%n", d.getOutputKey()));
        sb.append(".build();\n");
        sb.append("stateGraph.addNode(\"")
                .append(varName)
                .append("\", AsyncNodeAction.node_async(")
                .append(varName)
                .append("));\n\n");

        // Do not force AnswerNode -> END.
        // Dify Answer nodes may still have outgoing edges. WorkflowProjectGenerator
        // connects only true zero-outdegree nodes to END.
        return sb.toString();
    }

    @Override
    public List<String> getImports() {
        return List.of("com.alibaba.cloud.ai.graph.node.AnswerNode");
    }

}
