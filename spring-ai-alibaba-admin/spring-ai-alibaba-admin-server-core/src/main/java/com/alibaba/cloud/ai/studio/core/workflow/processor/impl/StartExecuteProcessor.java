/*
 * Copyright 2025 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.cloud.ai.studio.core.workflow.processor.impl;

import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.config.CommonConfig;
import com.alibaba.cloud.ai.studio.core.conversation.ConversationSessionVariablesService;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowInnerService;
import com.alibaba.cloud.ai.studio.core.workflow.processor.AbstractExecuteProcessor;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.CommonParam;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Edge;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeResult;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.ParamSourceEnum;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import com.google.common.collect.Maps;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;

import static com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants.WORKFLOW_SESSION_VARIABLE_KEY_TEMPLATE;

@Component("StartExecuteProcessor")
public class StartExecuteProcessor extends AbstractExecuteProcessor {

    private final ConversationSessionVariablesService sessionVariablesService;

    public StartExecuteProcessor(RedisManager redisManager, WorkflowInnerService workflowInnerService,
            ChatMemory conversationChatMemory, CommonConfig commonConfig,
            ConversationSessionVariablesService sessionVariablesService) {
        super(redisManager, workflowInnerService, conversationChatMemory, commonConfig);
        this.sessionVariablesService = sessionVariablesService;
    }

    @Override
    public String getNodeType() {
        return NodeTypeEnum.START.getCode();
    }

    @Override
    public String getNodeDescription() {
        return NodeTypeEnum.START.getDesc();
    }

    @Override
    public NodeResult innerExecute(DirectedAcyclicGraph<String, Edge> graph, Node node, WorkflowContext context) {
        long start = System.currentTimeMillis();
        NodeResult nodeResult = initNodeResultAndRefreshContext(node, context);

        // System inputs are unchanged.
        Map<String, Object> sysMap = context.getSysMap();
        if (sysMap != null) {
            Map<String, Object> sysObj = new HashMap<>(sysMap);
            context.getVariablesMap().put("sys", sysObj);
        }

        // Load the persisted snapshot via Redis -> PostgreSQL. Defaults only fill missing keys.
        var globalConfig = context.getWorkflowConfig().getGlobalConfig();
        List<CommonParam> params = globalConfig == null || globalConfig.getVariableConfig() == null
                ? List.of() : globalConfig.getVariableConfig().getConversationParams();
        if (!CollectionUtils.isEmpty(params)) {
            Map<String, Object> stored = sessionVariablesService.load(context);
            Map<String, Object> conversation = new HashMap<>();
            for (CommonParam param : params) {
                if (param == null || param.getKey() == null) {
                    continue;
                }
                Object value = param.getDefaultValue();
                if (stored != null && stored.containsKey(param.getKey())) {
                    value = stored.get(param.getKey());
                }
                else if (stored == null) {
                    // Only pre-migration sessions may read the old Redis key once.
                    try {
                        Object legacy = redisManager.get(String.format(WORKFLOW_SESSION_VARIABLE_KEY_TEMPLATE,
                                context.getAppId(), context.getConversationId(), param.getKey()));
                        if (legacy != null) {
                            value = legacy;
                        }
                    }
                    catch (RuntimeException ignored) {
                        // Redis is an optional cache; the declared default remains usable.
                    }
                }
                conversation.put(param.getKey(), value);
            }
            context.getVariablesMap().put(ParamSourceEnum.conversation.name(), conversation);
            // Existing workflows may reference ${session.key}.
            context.getVariablesMap().put("session", conversation);
        }

        // Original Start-node user variable behavior.
        Map<String, Object> userObj = new HashMap<>();
        if (node.getConfig() != null && !CollectionUtils.isEmpty(node.getConfig().getOutputParams())) {
            node.getConfig().getOutputParams().forEach(param -> {
                if (param.getDefaultValue() != null) {
                    userObj.put(param.getKey(), param.getDefaultValue());
                }
            });
        }
        if (context.getUserMap() != null) {
            userObj.putAll(context.getUserMap());
        }
        context.getVariablesMap().put(node.getId(), userObj);

        Map<String, Object> resultMap = Maps.newHashMap();
        resultMap.put("user", context.getUserMap());
        resultMap.put("sys", context.getSysMap());
        resultMap.put("conversation", context.getVariablesMap().get(ParamSourceEnum.conversation.name()));
        nodeResult.setInput(JsonUtils.toJson(resultMap));
        nodeResult.setOutput(JsonUtils.toJson(resultMap));
        nodeResult.setNodeExecTime((System.currentTimeMillis() - start) + "ms");
        return nodeResult;
    }

    @Override
    public void handleVariables(DirectedAcyclicGraph<String, Edge> graph, Node node, WorkflowContext context,
            NodeResult nodeResult) {
        // Start-node variables are already initialized above.
    }
}
