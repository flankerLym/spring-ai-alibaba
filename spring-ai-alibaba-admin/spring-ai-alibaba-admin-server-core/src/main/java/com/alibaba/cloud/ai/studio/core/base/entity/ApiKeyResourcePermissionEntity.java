/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.cloud.ai.studio.core.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** One API key permission row for either an application or an archive folder. */
@Data
@TableName("api_key_resource_permission")
public class ApiKeyResourcePermissionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("workspace_id")
    private String workspaceId;

    @TableField("api_key_id")
    private Long apiKeyId;

    /** APP or FOLDER. */
    @TableField("resource_type")
    private String resourceType;

    /** appId or folderId. */
    @TableField("resource_id")
    private String resourceId;

    @TableField("gmt_create")
    private Date gmtCreate;

    private String creator;
}
