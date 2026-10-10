package com.alibaba.cloud.ai.studio.openapi.dto.conversation;

import lombok.Data;
import java.util.Date;

/** 对外会话接口数据对象，不包含查询逻辑。 */
@Data public class ConversationView {
        private String conversationId;
        private String appId;
        private String userId;
        private String invokeSource;
        private String name;
        private String status;
        private Integer messageCount;
        private Date createdAt;
        private Date updatedAt;
    }
