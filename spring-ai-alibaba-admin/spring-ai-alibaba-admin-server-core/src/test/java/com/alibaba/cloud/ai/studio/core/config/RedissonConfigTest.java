package com.alibaba.cloud.ai.studio.core.config;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.redisson.codec.JsonJacksonCodec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedissonConfigTest {

    @Test
    void shouldRoundTripSpringAiMessagesThroughActualRedissonCodec() throws Exception {
        JsonJacksonCodec codec = RedissonConfig.createRedisCodec();

        Deque<Message> source = new ArrayDeque<>();
        source.add(UserMessage.builder()
                .text("请记住：我的测试代号是蓝鲸731。")
                .metadata(Map.of("source", "memory-test"))
                .build());
        source.add(AssistantMessage.builder()
                .content("已记住蓝鲸731")
                .properties(Map.of("round", 1))
                .build());

        ByteBuf encoded = codec.getValueEncoder().encode(source);

        String json;
        Object decoded;
        try {
            json = encoded.toString(StandardCharsets.UTF_8);
            decoded = codec.getValueDecoder().decode(encoded.copy(), null);
        }
        finally {
            encoded.release();
        }

        // Dedicated serializers must write the official handler field "text".
        assertThat(json).contains("\"text\":\"请记住：我的测试代号是蓝鲸731。\"");
        assertThat(json).contains("\"text\":\"已记住蓝鲸731\"");

        assertThat(decoded).isInstanceOf(ArrayDeque.class);
        List<?> messages = new ArrayList<>((Deque<?>) decoded);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);

        UserMessage user = (UserMessage) messages.get(0);
        AssistantMessage assistant = (AssistantMessage) messages.get(1);

        assertThat(user.getText()).isEqualTo("请记住：我的测试代号是蓝鲸731。");
        assertThat(user.getMetadata()).containsEntry("source", "memory-test");

        assertThat(assistant.getText()).isEqualTo("已记住蓝鲸731");
        assertThat(assistant.getToolCalls()).isEmpty();
        assertThat(assistant.getMetadata()).containsEntry("round", 1);
    }
}
