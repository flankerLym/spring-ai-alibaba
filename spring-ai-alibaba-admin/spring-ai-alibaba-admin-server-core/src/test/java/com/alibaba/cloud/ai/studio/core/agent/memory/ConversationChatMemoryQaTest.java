package com.alibaba.cloud.ai.studio.core.agent.memory;

import com.alibaba.cloud.ai.studio.core.base.entity.ConversationMessageEntity;
import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.config.CommonConfig;
import com.alibaba.cloud.ai.studio.core.conversation.ConversationManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConversationChatMemoryQaTest {

    @Test
    void restoreOneQuestionAnswerRowAsTwoMessages() throws Exception {
        RedisManager redis = mock(RedisManager.class);
        ConversationManager manager = mock(ConversationManager.class);
        CommonConfig config = new CommonConfig();
        config.setMaxConversationRoundInCache(4);
        ConversationChatMemory memory = new ConversationChatMemory(redis, config);
        Field field = ConversationChatMemory.class.getDeclaredField("conversationManager");
        field.setAccessible(true);
        field.set(memory, manager);

        ConversationMessageEntity first = new ConversationMessageEntity();
        first.setQuestion("提问A");
        first.setAnswer("回答A");
        ConversationMessageEntity second = new ConversationMessageEntity();
        second.setQuestion("提问B");
        second.setAnswer("回答B");
        when(manager.loadMemoryMessages("100_200", 4)).thenReturn(List.of(first, second));

        List<Message> results = memory.get("100_200");
        assertThat(results).extracting(Message::getText)
                .containsExactly("提问A", "回答A", "提问B", "回答B");
        verify(redis).put(eq("conversation_chat:100_200"), any());
    }

    @Test
    void applyMessageLimitAfterSplittingQuestionAnswerRows() throws Exception {
        RedisManager redis = mock(RedisManager.class);
        ConversationManager manager = mock(ConversationManager.class);
        CommonConfig config = new CommonConfig();
        config.setMaxConversationRoundInCache(3);
        ConversationChatMemory memory = new ConversationChatMemory(redis, config);
        Field field = ConversationChatMemory.class.getDeclaredField("conversationManager");
        field.setAccessible(true);
        field.set(memory, manager);

        ConversationMessageEntity first = new ConversationMessageEntity();
        first.setQuestion("Q1");
        first.setAnswer("A1");
        ConversationMessageEntity second = new ConversationMessageEntity();
        second.setQuestion("Q2");
        second.setAnswer("A2");
        when(manager.loadMemoryMessages("100_200", 3)).thenReturn(List.of(first, second));
        assertThat(memory.get("100_200")).extracting(Message::getText)
                .containsExactly("A1", "Q2", "A2");
    }
}
