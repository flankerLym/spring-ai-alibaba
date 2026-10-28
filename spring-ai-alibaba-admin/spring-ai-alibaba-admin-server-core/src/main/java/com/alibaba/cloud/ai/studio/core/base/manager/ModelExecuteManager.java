/*
 * Copyright 2024-2026 the original author or authors.
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
package com.alibaba.cloud.ai.studio.core.base.manager;

import com.alibaba.cloud.ai.studio.core.workflow.trace.annotation.WorkflowModelCall;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentResponse;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentStatus;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.ChatMessage;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.MessageRole;
import com.alibaba.cloud.ai.studio.runtime.domain.chat.Usage;
import com.alibaba.cloud.ai.studio.core.model.llm.ModelFactory;
import com.alibaba.cloud.ai.studio.core.model.llm.NativeOptionsChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Model execution manager for handling chat model interactions
 */
@Slf4j
@Component
public class ModelExecuteManager {

	@Resource
	private ModelFactory modelFactory;

	/**
	 * Stream model responses
	 * @param provider Model provider
	 * @param modelId Model identifier
	 * @param parameterMap Model parameters
	 * @param messages Chat messages
	 * @return Flux of agent responses
	 */
	@WorkflowModelCall
	public Flux<AgentResponse> stream(String provider, String modelId, Map<String, Object> parameterMap,
			List<Message> messages) {
		ChatModel chatModel = modelFactory.getChatModel(provider);

		// Doubao only: use the endpoint ID saved in this workflow's model metadata.
		// All other providers keep the original model ID and parameter assembly path.
		String requestModelId = modelId;
		if (chatModel instanceof NativeOptionsChatModel nativeModel
				&& nativeModel.usesProvider("doubao") && parameterMap != null) {
			Object rawMetadata = parameterMap.get("__model_metadata__");
			if (rawMetadata instanceof Map<?, ?> metadata) {
				Object savedModelId = metadata.get("model_id");
				Object savedEndpointId = metadata.get("endpoint_id");
				boolean sameModel = savedModelId == null || modelId.equals(String.valueOf(savedModelId));
				if (sameModel && savedEndpointId != null
						&& StringUtils.isNotBlank(String.valueOf(savedEndpointId))) {
					requestModelId = String.valueOf(savedEndpointId).trim();
				}
			}
		}

		OpenAiChatOptions.Builder chatOptionsBuilder = OpenAiChatOptions.builder().model(requestModelId);
		if (parameterMap != null) {
			if (parameterMap.get("temperate") != null && parameterMap.get("temperate") instanceof Double) {
				chatOptionsBuilder.temperature((Double) parameterMap.get("temperate"));
			}
			if (parameterMap.get("max_tokens") != null && parameterMap.get("max_tokens") instanceof Integer) {
				chatOptionsBuilder.maxTokens((Integer) parameterMap.get("max_tokens"));
			}
			if (parameterMap.get("top_p") != null && parameterMap.get("top_p") instanceof Double) {
				chatOptionsBuilder.topP((Double) parameterMap.get("top_p"));
			}
			if (parameterMap.get("presence_penalty") != null
					&& parameterMap.get("presence_penalty") instanceof Double) {
				chatOptionsBuilder.presencePenalty((Double) parameterMap.get("presence_penalty"));
			}
			if (parameterMap.get("frequency_penalty") != null
					&& parameterMap.get("frequency_penalty") instanceof Double) {
				chatOptionsBuilder.frequencyPenalty((Double) parameterMap.get("frequency_penalty"));
			}
			if (parameterMap.get("max_tokens") != null && parameterMap.get("max_tokens") instanceof Integer) {
				chatOptionsBuilder.maxTokens((Integer) parameterMap.get("max_tokens"));
			}
			if (parameterMap.get("seed") != null && parameterMap.get("seed") instanceof Integer) {
				chatOptionsBuilder.seed((Integer) parameterMap.get("seed"));
			}
			if (parameterMap.get("response_format") != null && parameterMap.get("response_format") instanceof String) {
				chatOptionsBuilder.responseFormat(ResponseFormat.builder()
						.type(ResponseFormat.Type.valueOf(parameterMap.get("response_format").toString().toUpperCase()))
						.build());
			}
		}
		chatOptionsBuilder.streamUsage(true);

		ChatOptions chatOptions = chatOptionsBuilder.build();
		Prompt prompt = new Prompt(messages, chatOptions);
		// Call the model's streaming API directly. ChatClient.stream() requires
		// StreamAdvisor instances; SimpleLoggerAdvisor is a regular advisor and is not
		// sufficient for this workflow streaming path.
		Flux<AgentResponse> stream = chatModel.stream(prompt)
			.concatMap(response -> convertResponse(response).flux());

		// DashScope may complete the streaming publisher without a usable generation
		// (for example, when it only returns an empty/usage chunk). Retry that empty
		// result once through the same provider's non-streaming ChatModel API.
		return stream.switchIfEmpty(Flux.defer(() -> convertResponse(chatModel.call(prompt))
			.switchIfEmpty(Mono.error(new IllegalStateException("模型未返回有效回复")))
			.flux()));
	}

	/**
	 * Convert chat response to agent response
	 * @param chatResponse Original chat response
	 * @return Mono containing the converted agent response
	 */
	protected Mono<AgentResponse> convertResponse(ChatResponse chatResponse) {
		if (chatResponse == null) {
			return Mono.empty();
		}

		org.springframework.ai.chat.metadata.Usage usage = chatResponse.getMetadata().getUsage();

		AgentResponse.AgentResponseBuilder responseBuilder = AgentResponse.builder()
			.model(chatResponse.getMetadata().getModel())
				.providerResponseId(chatResponse.getMetadata().getId())
				.usage(Usage.builder()
				.promptTokens(usage.getPromptTokens())
				.completionTokens(usage.getCompletionTokens())
				.totalTokens(usage.getTotalTokens())
				.build());

		if (!CollectionUtils.isEmpty(chatResponse.getResults())) {
			Generation generation = chatResponse.getResults().get(0);
			String finishReason = generation.getMetadata().getFinishReason();
			AgentStatus status = AgentStatus.toAgentStatus(finishReason);

			Object reasoningContentObj = generation.getOutput().getMetadata().get("reasoningContent");
			String reasoningContent = reasoningContentObj == null ? null : String.valueOf(reasoningContentObj);
			reasoningContent = StringUtils.isBlank(reasoningContent) ? null : reasoningContent;

			ChatMessage.ChatMessageBuilder messageBuilder = ChatMessage.builder()
				.role(MessageRole.ASSISTANT)
				.content(generation.getOutput().getText())
				.reasoningContent(reasoningContent);

			responseBuilder.status(status).message(messageBuilder.build());
		}
		else {
			return Mono.empty();
		}

		return Mono.just(responseBuilder.build());
	}

}
