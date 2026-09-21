package com.alibaba.cloud.ai.studio.core.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Conversation record.
 */
@Data
@TableName("conversation_record")
public class ConversationRecordEntity {

	@TableId(value = "id", type = IdType.INPUT)
	private Long id;

	@TableField("app_id")
	private Long appId;

	private String status;

	private String name;

	@TableField("invoke_source")
	private String invokeSource;

	@TableField("message_count")
	private Integer messageCount;

	@TableField("user_id")
	private Long userId;

	@TableField("created_at")
	private Date createdAt;

	@TableField("updated_at")
	private Date updatedAt;

}
