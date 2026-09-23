/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.cloud.ai.studio.runtime.domain.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * Project archive folder shown on the application management page.
 */
@Data
public class ProjectArchiveFolder implements Serializable {

	@JsonProperty("folder_id")
	private String folderId;

	private String name;

	@JsonProperty("app_count")
	private Long appCount;

	@JsonProperty("gmt_create")
	private Date gmtCreate;

	@JsonProperty("gmt_modified")
	private Date gmtModified;

}
