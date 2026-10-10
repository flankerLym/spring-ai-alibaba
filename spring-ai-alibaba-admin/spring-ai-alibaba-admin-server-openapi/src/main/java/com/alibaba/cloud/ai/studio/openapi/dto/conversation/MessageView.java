package com.alibaba.cloud.ai.studio.openapi.dto.conversation;

import lombok.Data;
import java.util.Date;

/** 对外会话接口数据对象，不包含查询逻辑。 */
@Data public class MessageView {
        private String messageId;
        private String parentMessageId;
        private Integer sequence;
        private String role;
        private String content;
        private String contentType;
        private String traceId;
        private String requestId;
        private String userId;
        private String status;
        private Date createdAt;
        private Date updatedAt;
    }
