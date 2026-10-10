package com.alibaba.cloud.ai.studio.core.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** A single persisted question-and-answer turn (one row, ordered by created_at + id). */
@Data
@TableName("conversation_message_record")
public class ConversationMessageEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("app_id")
    private Long appId;

    @TableField("conversation_id")
    private Long conversationId;

    @TableField("message_id")
    private Long messageId;

    private String question;

    private String answer;

    @TableField("trace_id")
    private String traceId;

    @TableField("request_id")
    private String requestId;

    @TableField("user_id")
    private String userId;

    private String status;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}
