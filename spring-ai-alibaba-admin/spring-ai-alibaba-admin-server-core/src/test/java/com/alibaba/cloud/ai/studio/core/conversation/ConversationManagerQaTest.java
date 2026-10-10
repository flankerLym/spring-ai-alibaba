package com.alibaba.cloud.ai.studio.core.conversation;

import com.alibaba.cloud.ai.studio.core.base.entity.ConversationMessageEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ConversationRecordEntity;
import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.base.mapper.ConversationMessageMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.ConversationRecordMapper;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConversationManagerQaTest {

    @Test
    void workflowPersistsExactlyOneRowAndOneTurnForQuestionAndAnswer() {
        ConversationRecordMapper conversations = mock(ConversationRecordMapper.class);
        ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
        RedisManager redis = mock(RedisManager.class);
        ConversationManager manager = new ConversationManager(conversations, messages, redis);
        ConversationRecordEntity record = new ConversationRecordEntity();
        record.setId(200L);
        record.setAppId(100L);
        record.setMessageCount(0);
        when(conversations.selectByIdForUpdate(200L)).thenReturn(record);

        WorkflowContext context = new WorkflowContext();
        context.setAppId("100");
        context.setConversationId("200");
        context.setWorkspaceId("workspace-test");
        context.setRequestId("request-qa-1");
        context.getSysMap().put("query", "查询订单");
        context.setTaskResult("订单已发货");

        AtomicReference<ConversationMessageEntity> saved = new AtomicReference<>();
        when(messages.selectList(any())).thenAnswer(call ->
                saved.get() == null ? List.of() : List.of(saved.get()));
        when(messages.insert(any(ConversationMessageEntity.class))).thenAnswer(call -> {
            ConversationMessageEntity row = call.getArgument(0);
            row.setId(1L);
            saved.set(row);
            return 1;
        });

        manager.saveWorkflowUserMessage(context);
        assertThat(saved.get().getQuestion()).isEqualTo("查询订单");
        assertThat(saved.get().getAnswer()).isNull();
        assertThat(saved.get().getStatus()).isEqualTo("processing");
        assertThat(record.getMessageCount()).isEqualTo(1);

        manager.saveWorkflowAssistantMessage(context);
        assertThat(saved.get().getAnswer()).isEqualTo("订单已发货");
        assertThat(saved.get().getStatus()).isEqualTo("success");
        assertThat(record.getMessageCount()).isEqualTo(1);
        verify(messages, times(1)).insert(any(ConversationMessageEntity.class));
        verify(messages, times(1)).updateById(any(ConversationMessageEntity.class));
    }
}
