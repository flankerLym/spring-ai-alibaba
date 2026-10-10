package com.alibaba.cloud.ai.studio.application;

import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeResult;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.AsyncResultResponse;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 异步返回装配器：只负责取 Output/End 节点对外字段。 */
@Component
public class OpenApiAsyncResultAssembler {
    public AsyncResultResponse assemble(String taskId, WorkflowContext context) {
        AsyncResultResponse response = new AsyncResultResponse();
        response.setTaskId(taskId);
        response.setRequestId(context.getRequestId());
        response.setConversationId(context.getConversationId());
        response.setTaskStatus(context.getTaskStatus());
        response.setErrorCode(context.getErrorCode());
        response.setErrorInfo(context.getErrorInfo());
        if (context.getStartTime() > 0) {
            response.setTaskExecTime((System.currentTimeMillis() - context.getStartTime()) + "ms");
        }
        CopyOnWriteArrayList<String> executionOrder = context.getExecuteOrderList();
        ConcurrentHashMap<String, NodeResult> results = context.getNodeResultMap();
        List<AsyncResultResponse.Output> outputs = Lists.newArrayList();
        if (CollectionUtils.isNotEmpty(executionOrder)) {
            executionOrder.forEach(nodeId -> {
                if (results == null) return;
                NodeResult result = results.get(nodeId);
                if (result == null) return;
                if (!NodeTypeEnum.OUTPUT.getCode().equals(result.getNodeType())
                        && !NodeTypeEnum.END.getCode().equals(result.getNodeType())) return;
                AsyncResultResponse.Output output = new AsyncResultResponse.Output();
                output.setNodeId(nodeId);
                output.setNodeName(result.getNodeName());
                output.setNodeType(result.getNodeType());
                output.setNodeStatus(result.getNodeStatus());
                output.setContent(result.getOutput());
                outputs.add(output);
            });
        }
        response.setOutputs(outputs);
        return response;
    }
}
