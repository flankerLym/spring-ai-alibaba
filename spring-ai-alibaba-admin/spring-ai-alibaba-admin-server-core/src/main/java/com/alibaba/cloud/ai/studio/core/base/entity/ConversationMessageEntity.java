package com.alibaba.cloud.ai.studio.core.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Conversation message record.
 *
 * Replaces the preliminary conversation_message mapping and persists to
 * conversation_message_record.
 */
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

	@TableField("parent_message_id")
	private Long parentMessageId;

	private Integer sequence;

	private String role;

	private String content;

	@TableField("content_type")
	private String contentType;

	@TableField("trace_id")
	private String traceId;

	@TableField("model_id")
	private String modelId;

	@TableField("provider_id")
	private String providerId;

	@TableField("request_id")
	private String requestId;

	@TableField("response_id")
	private String responseId;

	@TableField("input_tokens")
	private Integer inputTokens;

	@TableField("output_tokens")
	private Integer outputTokens;

	@TableField("total_tokens")
	private Integer totalTokens;

	private String status;

	@TableField("created_at")
	private Date createdAt;

	@TableField("updated_at")
	private Date updatedAt;

}
