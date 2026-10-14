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
package com.alibaba.cloud.ai.studio.core.model.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.service.ArkService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.observation.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.util.*;
import java.util.function.Supplier;

/** Spring AI adapter backed by the official Volcengine Ark Java SDK, not OpenAiApi. */
public final class ArkChatModel implements ChatModel {
    private static final ObjectMapper JSON = ArkService.defaultObjectMapper();
    private final Supplier<ArkService> services;
    private final ToolCallingManager tools;
    private final ObservationRegistry registry;
    private final ChatModelObservationConvention convention;

    public ArkChatModel(Supplier<ArkService> services, ToolCallingManager tools,
            ObservationRegistry registry, ChatModelObservationConvention convention) {
        this.services = services;
        this.tools = tools;
        this.registry = registry;
        this.convention = convention;
    }

    @Override public ChatResponse call(Prompt prompt) { return call(prompt, 0); }

    private ChatResponse call(Prompt prompt, int depth) {
        checkDepth(depth);
        ChatModelObservationContext context = context(prompt);
        ChatResponse response = observation(context).observe(() -> {
            ArkService service = services.get();
            try {
                ChatResponse result = response(JSON.valueToTree(service.createChatCompletion(request(prompt, false))));
                context.setResponse(result);
                return result;
            }
            finally { service.shutdownExecutor(); }
        });
        if (!executeInternally(prompt, response)) return response;
        ToolExecutionResult result = tools.executeToolCalls(prompt, response);
        if (result.returnDirect()) return ChatResponse.builder().from(response)
                .generations(ToolExecutionResult.buildGenerations(result)).build();
        return call(new Prompt(result.conversationHistory(), prompt.getOptions()), depth + 1);
    }

    @Override public Flux<ChatResponse> stream(Prompt prompt) { return stream(prompt, 0); }

    private Flux<ChatResponse> stream(Prompt prompt, int depth) {
        return Flux.deferContextual(reactorContext -> {
            checkDepth(depth);
            ChatModelObservationContext context = context(prompt);
            Observation observation = observation(context);
            observation.parentObservation(reactorContext.getOrDefault(ObservationThreadLocalAccessor.KEY, null)).start();
            StreamState state = new StreamState();
            Flux<ChatResponse> source = Flux.using(services::get,
                    service -> Flux.from(service.streamChatCompletion(request(prompt, true)))
                            .map(chunk -> state.accept(JSON.valueToTree(chunk))), ArkService::shutdownExecutor);
            return source.concatWith(Flux.defer(() -> {
                ChatResponse complete = state.complete();
                context.setResponse(complete);
                if (!complete.hasToolCalls()) return Flux.empty();
                if (!executeInternally(prompt, complete)) return Flux.just(state.toolResponse());
                return reactor.core.publisher.Mono.fromCallable(() -> tools.executeToolCalls(prompt, complete))
                        .subscribeOn(Schedulers.boundedElastic()).flatMapMany(result -> result.returnDirect()
                                ? Flux.just(ChatResponse.builder().from(complete)
                                        .generations(ToolExecutionResult.buildGenerations(result)).build())
                                : stream(new Prompt(result.conversationHistory(), prompt.getOptions()), depth + 1));
            })).doOnError(observation::error).doFinally(signal -> observation.stop())
                    .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
        });
    }

    private static void checkDepth(int depth) {
        if (depth >= 32) throw new IllegalStateException("Ark tool call limit exceeded (32)");
    }

    private boolean executeInternally(Prompt prompt, ChatResponse response) {
        return response.hasToolCalls() && prompt.getOptions() instanceof ToolCallingChatOptions options
                && !Boolean.FALSE.equals(options.getInternalToolExecutionEnabled());
    }

    private ChatModelObservationContext context(Prompt prompt) {
        return ChatModelObservationContext.builder().prompt(prompt).provider("doubao").build();
    }

    private Observation observation(ChatModelObservationContext context) {
        return ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(convention,
                new DefaultChatModelObservationConvention(), () -> context, registry);
    }

    //转换层
    ChatCompletionRequest request(Prompt prompt, boolean stream) {
        ChatOptions options = prompt.getOptions();
        if (options == null || options.getModel() == null || options.getModel().isBlank()) {
            throw new IllegalArgumentException("Doubao requires a model ID or endpoint ID");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", options.getModel());
        body.put("stream", stream);
        put(body, "temperature", options.getTemperature());
        put(body, "top_p", options.getTopP());
        put(body, "max_tokens", options.getMaxTokens());
        put(body, "stop", options.getStopSequences());
        put(body, "frequency_penalty", options.getFrequencyPenalty());
        put(body, "presence_penalty", options.getPresencePenalty());
        if (options instanceof OpenAiChatOptions legacy) {
            put(body, "tool_choice", legacy.getToolChoice());
            put(body, "max_completion_tokens", legacy.getMaxCompletionTokens());
            put(body, "reasoning_effort", legacy.getReasoningEffort());
            put(body, "parallel_tool_calls", legacy.getParallelToolCalls());
            if (legacy.getN() != null && legacy.getN() != 1) {
                throw new IllegalArgumentException("Ark adapter requires n=1");
            }
            if (legacy.getExtraBody() != null) {
                for (String key : List.of("thinking", "reasoning_effort", "repetition_penalty")) {
                    if (!"thinking".equals(key)) put(body, key, legacy.getExtraBody().get(key));
                }
            }
        }
        if (options instanceof ToolCallingChatOptions toolOptions) {
            List<Map<String, Object>> definitions = new ArrayList<>();
            tools.resolveToolDefinitions(toolOptions).forEach(tool -> {
                try {
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", tool.name());
                    put(function, "description", tool.description());
                    function.put("parameters", JSON.readTree(tool.inputSchema()));
                    definitions.add(Map.of("type", "function", "function", function));
                }
                catch (java.io.IOException error) { throw new IllegalArgumentException("Invalid tool schema", error); }
            });
            if (!definitions.isEmpty()) body.put("tools", definitions);
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            if (message instanceof ToolResponseMessage toolMessage) {
                for (ToolResponseMessage.ToolResponse tool : toolMessage.getResponses()) {
                    messages.add(Map.of("role", "tool", "tool_call_id", tool.id(),
                            "content", Objects.toString(tool.responseData(), "")));
                }
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.getMessageType().getValue());
            item.put("content", message.getText());
            if (message instanceof UserMessage user && !user.getMedia().isEmpty()) {
                List<Map<String, Object>> content = new ArrayList<>();
                if (user.getText() != null) content.add(Map.of("type", "text", "text", user.getText()));
                for (Media media : user.getMedia()) {
                    if (!"image".equalsIgnoreCase(media.getMimeType().getType())) {
                        throw new IllegalArgumentException("Ark adapter supports image media only: " + media.getMimeType());
                    }
                    String url = imageUrl(media);
                    content.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
                }
                item.put("content", content);
            }
            if (message instanceof AssistantMessage assistant) {
                if (assistant.hasToolCalls()) item.put("tool_calls", assistant.getToolCalls().stream()
                        .map(tool -> Map.of("id", tool.id(), "type", "function", "function",
                                Map.of("name", tool.name(), "arguments", tool.arguments()))).toList());
                put(item, "reasoning_content", assistant.getMetadata().get("reasoningContent"));
            }
            messages.add(item);
        }
        body.put("messages", messages);
        ChatCompletionRequest request = JSON.convertValue(body, ChatCompletionRequest.class);
        if (stream) request.setStreamOptions(ChatCompletionRequest.ChatCompletionRequestStreamOptions.of(true));
        if (options instanceof OpenAiChatOptions legacy) {
            if (legacy.getResponseFormat() != null) request.setResponseFormat(responseFormat(legacy.getResponseFormat()));
            Object thinking = legacy.getExtraBody() == null ? null : legacy.getExtraBody().get("thinking");
            if (thinking != null) {
                JsonNode value = JSON.valueToTree(thinking);
                String type = value.isTextual() ? value.asText() : value.path("type").asText(null);
                if (type != null) request.setThinking(new ChatCompletionRequest.ChatCompletionRequestThinking(type));
            }
        }
        return request;
    }

    private static ChatCompletionRequest.ChatCompletionRequestResponseFormat responseFormat(Object value) {
        JsonNode json = JSON.valueToTree(value);
        String type = json.path("type").asText("json_object");
        var result = new ChatCompletionRequest.ChatCompletionRequestResponseFormat(type);
        JsonNode schema = json.has("json_schema") ? json.path("json_schema") : json.path("jsonSchema");
        if (schema.isObject()) {
            var details = new com.volcengine.ark.runtime.model.completion.chat.ResponseFormatJSONSchemaJSONSchemaParam(
                    schema.path("name").asText("response"), schema.path("description").asText(null),
                    schema.get("schema"), schema.path("strict").asBoolean(false));
            result.setJsonSchema(details);
        }
        return result;
    }

    private static String imageUrl(Media media) {
        Object data = media.getData();
        if (data instanceof byte[] bytes) return dataUri(media, bytes);
        if (data instanceof Resource resource) {
            try (var input = resource.getInputStream()) {
                byte[] bytes = input.readAllBytes();
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                return text.startsWith("data:image/") ? text : dataUri(media, bytes);
            }
            catch (java.io.IOException error) { throw new IllegalArgumentException("Cannot read image resource", error); }
        }
        if (data instanceof java.net.URI uri) return uri.toString();
        if (data instanceof java.net.URL url) return url.toExternalForm();
        if (data instanceof CharSequence value) return value.toString();
        throw new IllegalArgumentException("Unsupported image data: "
                + (data == null ? "null" : data.getClass().getName()));
    }

    private static String dataUri(Media media, byte[] bytes) {
        return "data:" + media.getMimeType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    static ChatResponse response(JsonNode root) {
        List<Generation> generations = new ArrayList<>();
        for (JsonNode choice : root.path("choices")) {
            JsonNode message = choice.path("message");
            if (message.isMissingNode()) message = choice.path("delta");
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            for (JsonNode tool : message.path("tool_calls")) {
                calls.add(new AssistantMessage.ToolCall(tool.path("id").asText(), "function",
                        tool.path("function").path("name").asText(),
                        tool.path("function").path("arguments").asText()));
            }
            Map<String, Object> properties = new HashMap<>();
            if (message.hasNonNull("reasoning_content")) properties.put("reasoningContent",
                    message.path("reasoning_content").asText());
            AssistantMessage output = AssistantMessage.builder().content(message.path("content").asText(""))
                    .properties(properties).toolCalls(calls).build();
            generations.add(new Generation(output, ChatGenerationMetadata.builder()
                    .finishReason(choice.path("finish_reason").asText("")).build()));
        }
        // Usage-only SDK chunks still reach the existing token/trace conversion path.
        if (generations.isEmpty()) generations.add(new Generation(new AssistantMessage("")));
        JsonNode usage = root.path("usage");
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().id(root.path("id").asText(""))
                .model(root.path("model").asText(""))
                .usage(new DefaultUsage(usage.path("prompt_tokens").asInt(),
                        usage.path("completion_tokens").asInt(), usage.path("total_tokens").asInt())).build();
        return new ChatResponse(generations, metadata);
    }

    /** Each subscription owns its accumulator. Tool arguments may span many SDK chunks. */
    private static final class StreamState {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Map<Integer, Map<String, StringBuilder>> calls = new TreeMap<>();
        private String id = "", model = "", finish = "";
        private JsonNode usage = JSON.createObjectNode();

        ChatResponse accept(JsonNode chunk) {
            if (chunk.hasNonNull("id")) id = chunk.path("id").asText();
            if (chunk.hasNonNull("model")) model = chunk.path("model").asText();
            if (chunk.hasNonNull("usage")) usage = chunk.path("usage");
            for (JsonNode choice : chunk.path("choices")) {
                if (choice.path("index").asInt() != 0) throw new IllegalArgumentException("Ark adapter requires n=1");
                JsonNode delta = choice.has("message") ? choice.path("message") : choice.path("delta");
                text.append(delta.path("content").asText(""));
                reasoning.append(delta.path("reasoning_content").asText(""));
                if (choice.hasNonNull("finish_reason")) finish = choice.path("finish_reason").asText();
                for (JsonNode tool : delta.path("tool_calls")) {
                    Map<String, StringBuilder> parts = calls.computeIfAbsent(tool.path("index").asInt(),
                            index -> new HashMap<>());
                    append(parts, "id", tool.path("id"));
                    append(parts, "name", tool.path("function").path("name"));
                    append(parts, "arguments", tool.path("function").path("arguments"));
                }
            }
            // Partial tool JSON must not be executed or emitted as complete calls.
            JsonNode clean = chunk.deepCopy();
            for (JsonNode choice : clean.path("choices")) {
                JsonNode message = choice.has("message") ? choice.path("message") : choice.path("delta");
                if (message.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) message).remove("tool_calls");
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) clean).put("id", id).put("model", model);
            if (clean.path("choices").isEmpty()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) clean).set("choices", JSON.valueToTree(
                        List.of(Map.of("finish_reason", finish, "message", Map.of("content", "")))));
            }
            return response(clean);
        }

        private void append(Map<String, StringBuilder> parts, String key, JsonNode value) {
            if (value.isTextual()) parts.computeIfAbsent(key, ignored -> new StringBuilder()).append(value.asText());
        }

        ChatResponse complete() { return aggregate(text.toString(), reasoning.toString()); }
        ChatResponse toolResponse() { return aggregate("", ""); }

        private ChatResponse aggregate(String content, String reasoningContent) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (Map<String, StringBuilder> parts : calls.values()) tools.add(Map.of(
                    "id", parts.getOrDefault("id", new StringBuilder()).toString(), "type", "function",
                    "function", Map.of("name", parts.getOrDefault("name", new StringBuilder()).toString(),
                            "arguments", parts.getOrDefault("arguments", new StringBuilder()).toString())));
            return response(JSON.valueToTree(Map.of("id", id, "model", model, "usage", usage,
                    "choices", List.of(Map.of("finish_reason", finish, "message", Map.of(
                            "content", content, "reasoning_content", reasoningContent, "tool_calls", tools))))));
        }
    }
}
