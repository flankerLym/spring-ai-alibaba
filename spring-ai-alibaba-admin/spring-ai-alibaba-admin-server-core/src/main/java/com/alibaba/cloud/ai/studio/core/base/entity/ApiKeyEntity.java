/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.studio.core.base.entity;

import com.alibaba.cloud.ai.studio.runtime.enums.CommonStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** API Key persistence entity. */
@Data
@TableName("api_key")
public class ApiKeyEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("account_id")
    private String accountId;

    @TableField("api_key")
    private String apiKey;

    private CommonStatus status;

    private String description;

    @TableField("company_name")
    private String companyName;

    /** ALL or CUSTOM. Existing keys default to ALL for backward compatibility. */
    @TableField("scope_type")
    private String scopeType;

    @TableField("gmt_create")
    private Date gmtCreate;

    @TableField("gmt_modified")
    private Date gmtModified;

    private String creator;

    private String modifier;
}
