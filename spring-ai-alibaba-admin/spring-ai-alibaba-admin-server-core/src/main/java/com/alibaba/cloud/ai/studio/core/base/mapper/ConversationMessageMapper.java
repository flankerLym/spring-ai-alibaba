package com.alibaba.cloud.ai.studio.core.base.mapper;

import com.alibaba.cloud.ai.studio.core.base.entity.ConversationMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * Conversation message mapper.
 *
 * The entity now maps to conversation_message_record instead of the old
 * conversation_message table.
 */
public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {

}
