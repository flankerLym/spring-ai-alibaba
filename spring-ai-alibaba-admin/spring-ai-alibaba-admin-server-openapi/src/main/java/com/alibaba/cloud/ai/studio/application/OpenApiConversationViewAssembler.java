package com.alibaba.cloud.ai.studio.application;

import com.alibaba.cloud.ai.studio.core.base.entity.ConversationMessageEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ConversationRecordEntity;
import com.alibaba.cloud.ai.studio.openapi.dto.conversation.ConversationView;
import com.alibaba.cloud.ai.studio.openapi.dto.conversation.MessageView;
import org.springframework.stereotype.Component;

/** 查询响应装配器：实体到对外字段的映射，不包含查询和权限判断。 */
@Component
public class OpenApiConversationViewAssembler {

    public ConversationView conversationView(ConversationRecordEntity entity) {
        ConversationView view = new ConversationView();
        view.setConversationId(entity.getId().toString());
        view.setAppId(entity.getAppId().toString());
        view.setUserId(entity.getUserId());
        view.setInvokeSource(entity.getInvokeSource());
        view.setName(entity.getName());
        view.setStatus(entity.getStatus());
        view.setMessageCount(entity.getMessageCount());
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        return view;
    }

    public MessageView messageView(ConversationMessageEntity entity) {
        MessageView view = new MessageView();
        view.setMessageId(entity.getMessageId() == null ? null : entity.getMessageId().toString());
        // 一条数据库记录代表一次问答，不再存在 parentMessageId/sequence/role/content。
        view.setQuestion(entity.getQuestion());
        view.setAnswer(entity.getAnswer());
        view.setTraceId(entity.getTraceId());
        view.setRequestId(entity.getRequestId());
        view.setUserId(entity.getUserId());
        view.setStatus(entity.getStatus());
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        return view;
    }
}
