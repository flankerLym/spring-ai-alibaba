package com.alibaba.cloud.ai.studio.core.conversation.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 会话持久化入口。
 *
 * 标记一次需要创建/更新 conversation_record 的调用入口。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Conversation {
}