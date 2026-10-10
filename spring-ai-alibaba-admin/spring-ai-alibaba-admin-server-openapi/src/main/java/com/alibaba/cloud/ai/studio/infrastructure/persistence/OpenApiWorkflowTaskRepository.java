package com.alibaba.cloud.ai.studio.infrastructure.persistence;

import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import static com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants.WORKFLOW_TASK_CONTEXT_PREFIX;

/** 异步任务读取适配器：缓存 Key 拼装细节不暴露给应用层。 */
@Repository
@RequiredArgsConstructor
public class OpenApiWorkflowTaskRepository {
    private final RedisManager redisManager;

    public WorkflowContext find(String workspaceId, String taskId) {
        return redisManager.get(WORKFLOW_TASK_CONTEXT_PREFIX + workspaceId + "_" + taskId);
    }
}
