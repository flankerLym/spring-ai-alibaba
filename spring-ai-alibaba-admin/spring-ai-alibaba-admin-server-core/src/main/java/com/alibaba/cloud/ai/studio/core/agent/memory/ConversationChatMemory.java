/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * ChatMemory backed by Redis with conversation_message_record as durable fallback.
 *
 * Workflow END DB persistence is handled once by ConversationPersistenceAspect. The
 * existing END call to ChatMemory.add() therefore refreshes Redis only while the END
 * scope is active. Other ChatMemory callers (for example Agent) persist through the
 * unified ConversationManager.
 */
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

		// Generic ChatMemory callers persist synchronously. Workflow END is persisted by
		// the END lifecycle aspect with trace/request metadata, so avoid duplicate rows.
		if (conversationManager != null && !ConversationPersistenceScope.isWorkflowEnd()) {
			conversationManager.appendMemoryMessages(conversationId, messages);
		}

		String key = getConversationMemoryCacheKey(conversationId);
		Deque<Message> historyMessages = redisManager.get(key);

		if (Objects.isNull(historyMessages)) {
			historyMessages = new ArrayDeque<>();
		}

		for (Message message : messages) {
			historyMessages.offer(message);
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
		List<ConversationMessageEntity> rows =
				conversationManager.loadMemoryMessages(conversationId, messageLimit);
		if (rows.isEmpty()) {
			return List.of();
		}

		Deque<Message> restored = new ArrayDeque<>();
		for (ConversationMessageEntity row : rows) {
			Message message = toSpringMessage(row);
			if (message != null) {
				restored.offer(message);
			}
		}

		redisManager.put(key, restored);
		return restored.stream().toList();
	}

	@Override
	public void clear(String conversationId) {
		if (conversationManager != null) {
			conversationManager.clearMemory(conversationId);
			return;
		}
		redisManager.delete(getConversationMemoryCacheKey(conversationId));
	}

	private Message toSpringMessage(ConversationMessageEntity entity) {
		if (entity == null || entity.getRole() == null) {
			return null;
		}

		String role = entity.getRole().toLowerCase(Locale.ROOT);
		String content = entity.getContent() == null ? "" : entity.getContent();

		return switch (role) {
			case "user" -> new UserMessage(content);
			case "assistant" -> new AssistantMessage(content);
			case "system" -> new SystemMessage(content);
			default -> null;
		};
	}

	private String getConversationMemoryCacheKey(String conversationId) {
		return String.format(CONVERSATION_CHAT_MEMORY_PREFIX, conversationId);
	}

}
