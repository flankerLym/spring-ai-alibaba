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

package com.alibaba.cloud.ai.studio.runtime.domain.account;

import com.alibaba.cloud.ai.studio.runtime.enums.CommonStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** API Key business information. */
@Data
public class ApiKey implements Serializable {

    private Long id;

    @JsonProperty("api_key")
    private String apiKey;

    private String description;

    /** Company name, stored in api_key.company_name (varchar(200)). */
    private String companyName;

    @JsonProperty("account_id")
    private String accountId;

    private CommonStatus status;

    @JsonProperty("gmt_create")
    private Date gmtCreate;

    @JsonProperty("gmt_modified")
    private Date gmtModified;

    private String creator;

    private String modifier;
}
