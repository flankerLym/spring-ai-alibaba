/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.nodedata;

import com.alibaba.cloud.ai.studio.admin.builder.generator.model.Variable;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.VariableSelector;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.VariableType;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.NodeData;
import com.alibaba.cloud.ai.studio.admin.builder.generator.service.dsl.DSLDialectType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class QuestionClassifierNodeData extends NodeData {

    public static Variable getDefaultOutputSchema(DSLDialectType dialectType) {
        return switch (dialectType) {
            case DIFY -> new Variable("class_name", VariableType.STRING);
            case STUDIO -> new Variable("subject", VariableType.STRING);
            default -> new Variable("text", VariableType.STRING);
        };
    }

    private String providerName;

    private String chatModeName;

    private Map<String, Object> modeParams;

    private VariableSelector inputSelector;

    private String outputKey;

    private List<ClassConfig> classes;

    private String promptTemplate;

    private Map<String, String> classIdToName;

    public record ClassConfig(String id, String classTemplate) {
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getChatModeName() {
        return chatModeName;
    }

    public void setChatModeName(String chatModeName) {
        this.chatModeName = chatModeName;
    }

    public Map<String, Object> getModeParams() {
        return modeParams;
    }

    public void setModeParams(Map<String, Object> modeParams) {
        this.modeParams = modeParams;
    }

    public VariableSelector getInputSelector() {
        return inputSelector;
    }

    public void setInputSelector(VariableSelector inputSelector) {
        this.inputSelector = inputSelector;
    }

    public String getOutputKey() {
        return outputKey;
    }

    public void setOutputKey(String outputKey) {
        this.outputKey = outputKey;
    }

    public List<ClassConfig> getClasses() {
        return classes;
    }

    public void setClasses(List<ClassConfig> classes) {
        this.classes = classes;
        updateClassIdToName();
    }

    public String getPromptTemplate() {
        return promptTemplate;
    }

    public void setPromptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
    }

    public Map<String, String> getClassIdToName() {
        return classIdToName;
    }

    private void updateClassIdToName() {
        AtomicInteger count = new AtomicInteger(1);
        this.classIdToName = this.getClasses()
                .stream()
                .map(ClassConfig::id)
                .collect(Collectors.toUnmodifiableMap(id -> id, name -> "case_" + (count.getAndIncrement())));
    }

}
