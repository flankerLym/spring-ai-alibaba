/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.core.agent.memory;

import com.alibaba.cloud.ai.studio.core.base.entity.ConversationMessageEntity;
import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.config.CommonConfig;
import com.alibaba.cloud.ai.studio.core.conversation.ConversationManager;
import com.alibaba.cloud.ai.studio.core.conversation.ConversationPersistenceScope;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Restores one DB question-answer row as two Spring AI messages without changing model input. */
@Component
public class ConversationChatMemory implements ChatMemory {

    public static String CONVERSATION_CHAT_MEMORY_PREFIX = "conversation_chat:%s";

    private final RedisManager redisManager;
    private final Integer maxMessages;

    @Resource
    private ConversationManager conversationManager;

    public ConversationChatMemory(RedisManager redisManager, CommonConfig commonConfig) {
        this.redisManager = redisManager;
        this.maxMessages = commonConfig.getMaxConversationRoundInCache();
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (conversationManager != null && !ConversationPersistenceScope.isWorkflowEnd()) {
            conversationManager.appendMemoryMessages(conversationId, messages);
        }

        String key = getConversationMemoryCacheKey(conversationId);
        Deque<Message> historyMessages = redisManager.get(key);
        if (Objects.isNull(historyMessages)) {
            historyMessages = new ArrayDeque<>();
        }
        for (Message message : messages) {
            if (message != null) {
                historyMessages.offer(message);
            }
        }
        int messageLimit = Math.max(0, maxMessages);
        while (historyMessages.size() > messageLimit) {
            historyMessages.poll();
        }
        redisManager.put(key, historyMessages);
    }

    @Override
    public List<Message> get(String conversationId) {
        String key = getConversationMemoryCacheKey(conversationId);
        Deque<Message> all = redisManager.get(key);
        if (all != null) {
            return all.stream().toList();
        }
        if (conversationManager == null) {
            return List.of();
        }

        int messageLimit = Math.max(0, maxMessages);
        if (messageLimit == 0) {
            return List.of();
        }
        List<ConversationMessageEntity> rows = conversationManager.loadMemoryMessages(conversationId, messageLimit);
        if (rows.isEmpty()) {
            return List.of();
        }

        Deque<Message> restored = new ArrayDeque<>();
        for (ConversationMessageEntity turn : rows) {
            if (turn.getQuestion() != null) {
                restored.offer(new UserMessage(turn.getQuestion()));
            }
            if (turn.getAnswer() != null) {
                restored.offer(new AssistantMessage(turn.getAnswer()));
            }
        }
        while (restored.size() > messageLimit) {
            restored.poll();
        }
        redisManager.put(key, restored);
        return restored.stream().toList();
    }

    @Override
    public void clear(String conversationId) {
        if (conversationManager != null) {
            conversationManager.clearMemory(conversationId);
        }
        else {
            redisManager.delete(getConversationMemoryCacheKey(conversationId));
        }
    }

    private String getConversationMemoryCacheKey(String conversationId) {
        return String.format(CONVERSATION_CHAT_MEMORY_PREFIX, conversationId);
    }
}
