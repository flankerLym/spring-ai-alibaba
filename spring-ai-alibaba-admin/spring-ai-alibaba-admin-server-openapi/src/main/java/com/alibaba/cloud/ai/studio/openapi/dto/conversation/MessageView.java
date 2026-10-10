package com.alibaba.cloud.ai.studio.openapi.dto.conversation;

import lombok.Data;
import java.util.Date;

/** 对外问答记录：一轮问答对应 conversation_message_record 中的一行。 */
@Data
public class MessageView {
    private String messageId;
    private String question;
    private String answer;
    private String traceId;
    private String requestId;
    private String userId;
    private String status;
    private Date createdAt;
    private Date updatedAt;
}
