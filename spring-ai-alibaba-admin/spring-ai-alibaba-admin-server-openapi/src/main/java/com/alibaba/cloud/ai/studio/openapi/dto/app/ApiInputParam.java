package com.alibaba.cloud.ai.studio.openapi.dto.app;

import lombok.Data;

/** 对外应用查询契约：只承载字段，不承载领域行为。 */
@Data
	public class ApiInputParam {

		private String key;

		private String type;

		private String desc;

		private Boolean required;

		private String source;

		private Object defaultValue;

	}
