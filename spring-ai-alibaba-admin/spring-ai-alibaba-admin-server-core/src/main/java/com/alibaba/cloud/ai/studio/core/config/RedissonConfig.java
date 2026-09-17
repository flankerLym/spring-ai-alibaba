/*
* Copyright 2024 the original author or authors.
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

package com.alibaba.cloud.ai.studio.core.config;

import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.type.WritableTypeId;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeResolverBuilder;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.xml.datatype.XMLGregorianCalendar;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Title redisson config.<br>
 * Description redisson config.<br>
 *
 * @since 1.0.0.3
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private Integer port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private Integer database;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.setCodec(createRedisCodec());

        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectTimeout(10000)
                .setTimeout(15000)
                .setRetryAttempts(3)
                .setRetryInterval(1000);

        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }

        return Redisson.create(config);
    }

    static JsonJacksonCodec createRedisCodec() {
        return new SpringAiJsonJacksonCodec(createRedisObjectMapper());
    }

    static ObjectMapper createRedisObjectMapper() {
        ObjectMapper mapper = JsonUtils.getObjectMapper().copy();

        SimpleModule module = new SimpleModule("spring-ai-message-redis-module");
        module.addSerializer(UserMessage.class, new UserMessageSerializer());
        module.addDeserializer(UserMessage.class, new UserMessageDeserializer());
        module.addSerializer(AssistantMessage.class, new AssistantMessageSerializer());
        module.addDeserializer(AssistantMessage.class, new AssistantMessageDeserializer());

        mapper.registerModule(module);
        return mapper;
    }

    /**
     * Redisson 3.27.2 enables NON_FINAL default typing for values.
     * JsonNode is only an intermediate tree used inside message deserializers,
     * so it must not itself require an @class discriminator.
     */
    private static final class SpringAiJsonJacksonCodec extends JsonJacksonCodec {

        private SpringAiJsonJacksonCodec(ObjectMapper mapper) {
            super(mapper);
        }

        @Override
        protected void initTypeInclusion(ObjectMapper mapper) {
            TypeResolverBuilder<?> typer = new ObjectMapper.DefaultTypeResolverBuilder(
                    ObjectMapper.DefaultTyping.NON_FINAL) {
                @Override
                public boolean useForType(JavaType type) {
                    while (type.isArrayType()) {
                        type = type.getContentType();
                    }

                    Class<?> rawClass = type.getRawClass();
                    if (rawClass != null && JsonNode.class.isAssignableFrom(rawClass)) {
                        return false;
                    }
                    if (rawClass == Long.class) {
                        return true;
                    }
                    if (rawClass == XMLGregorianCalendar.class) {
                        return false;
                    }
                    return !type.isFinal();
                }
            };

            typer.init(JsonTypeInfo.Id.CLASS, null);
            typer.inclusion(JsonTypeInfo.As.PROPERTY);
            mapper.setDefaultTyping(typer);
        }
    }

    /*
     * These serializers follow the same shape as Spring AI Alibaba's
     * UserMessageHandler / AssistantMessageHandler:
     *
     * UserMessage      -> { text, metadata }
     * AssistantMessage -> { text, toolCalls, metadata }
     *
     * This is important because Redisson's default field serializer writes
     * Spring AI's protected field "textContent", while the official message
     * handlers deserialize the stable field name "text".
     */
    private static final class UserMessageSerializer extends StdSerializer<UserMessage> {

        private UserMessageSerializer() {
            super(UserMessage.class);
        }

        @Override
        public void serialize(UserMessage message,
                              JsonGenerator generator,
                              SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            writeFields(message, generator);
            generator.writeEndObject();
        }

        @Override
        public void serializeWithType(UserMessage message,
                                      JsonGenerator generator,
                                      SerializerProvider provider,
                                      TypeSerializer typeSerializer) throws IOException {
            WritableTypeId typeId = typeSerializer.writeTypePrefix(
                    generator,
                    typeSerializer.typeId(message, JsonToken.START_OBJECT));
            writeFields(message, generator);
            typeSerializer.writeTypeSuffix(generator, typeId);
        }

        private void writeFields(UserMessage message, JsonGenerator generator) throws IOException {
            generator.writeStringField("text", message.getText());
            generator.writeObjectField("metadata", message.getMetadata());
        }
    }

    private static final class AssistantMessageSerializer extends StdSerializer<AssistantMessage> {

        private AssistantMessageSerializer() {
            super(AssistantMessage.class);
        }

        @Override
        public void serialize(AssistantMessage message,
                              JsonGenerator generator,
                              SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            writeFields(message, generator);
            generator.writeEndObject();
        }

        @Override
        public void serializeWithType(AssistantMessage message,
                                      JsonGenerator generator,
                                      SerializerProvider provider,
                                      TypeSerializer typeSerializer) throws IOException {
            WritableTypeId typeId = typeSerializer.writeTypePrefix(
                    generator,
                    typeSerializer.typeId(message, JsonToken.START_OBJECT));
            writeFields(message, generator);
            typeSerializer.writeTypeSuffix(generator, typeId);
        }

        private void writeFields(AssistantMessage message, JsonGenerator generator) throws IOException {
            generator.writeStringField("text", message.getText());

            generator.writeArrayFieldStart("toolCalls");
            for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
                generator.writeStartObject();
                generator.writeStringField("id", toolCall.id());
                generator.writeStringField("name", toolCall.name());
                generator.writeStringField("type", toolCall.type());
                generator.writeStringField("arguments", toolCall.arguments());
                generator.writeEndObject();
            }
            generator.writeEndArray();

            generator.writeObjectField("metadata", message.getMetadata());
        }
    }

    private static Map<String, Object> readMetadata(ObjectMapper mapper, JsonNode parentNode) {
        JsonNode metadataNode = parentNode.get("metadata");
        if (metadataNode == null || metadataNode.isNull()) {
            return Map.of();
        }
        return mapper.convertValue(metadataNode, new TypeReference<Map<String, Object>>() {
        });
    }

    private static String readText(JsonNode node) {
        JsonNode textNode = node.get("text");

        // Backward compatibility with data written by Redisson's default
        // field serializer before the dedicated message serializers were added.
        if (textNode == null || textNode.isNull()) {
            textNode = node.get("textContent");
        }

        return textNode == null || textNode.isNull() ? "" : textNode.asText("");
    }

    private static final class UserMessageDeserializer extends StdDeserializer<UserMessage> {

        private UserMessageDeserializer() {
            super(UserMessage.class);
        }

        @Override
        public UserMessage deserialize(JsonParser parser,
                                       DeserializationContext context) throws IOException {
            ObjectMapper mapper = (ObjectMapper) parser.getCodec();
            JsonNode node = mapper.readTree(parser);

            return UserMessage.builder()
                    .text(readText(node))
                    .metadata(readMetadata(mapper, node))
                    .build();
        }
    }

    private static final class AssistantMessageDeserializer extends StdDeserializer<AssistantMessage> {

        private AssistantMessageDeserializer() {
            super(AssistantMessage.class);
        }

        @Override
        public AssistantMessage deserialize(JsonParser parser,
                                            DeserializationContext context) throws IOException {
            ObjectMapper mapper = (ObjectMapper) parser.getCodec();
            JsonNode node = mapper.readTree(parser);

            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
            JsonNode toolCallsNode = node.get("toolCalls");

            /*
             * Old Redisson default typing may represent a List as
             * ["java.util....", [ ... ]]. New dedicated serializers write a
             * plain JSON array. Accept both and ignore non-object wrapper items.
             */
            if (toolCallsNode != null && toolCallsNode.isArray()
                    && toolCallsNode.size() == 2
                    && toolCallsNode.get(0).isTextual()
                    && toolCallsNode.get(1).isArray()) {
                toolCallsNode = toolCallsNode.get(1);
            }

            if (toolCallsNode != null && toolCallsNode.isArray()) {
                for (JsonNode toolCallNode : toolCallsNode) {
                    if (!toolCallNode.isObject()) {
                        continue;
                    }
                    toolCalls.add(new AssistantMessage.ToolCall(
                            toolCallNode.path("id").asText(""),
                            toolCallNode.path("type").asText(""),
                            toolCallNode.path("name").asText(""),
                            toolCallNode.path("arguments").asText("")));
                }
            }

            return AssistantMessage.builder()
                    .content(readText(node))
                    .properties(readMetadata(mapper, node))
                    .toolCalls(toolCalls)
                    .build();
        }
    }
}
