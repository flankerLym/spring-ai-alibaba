package com.alibaba.cloud.ai.studio.openapi.dto.app;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonAlias;

/** 对外应用查询契约：只承载字段，不承载领域行为。 */
@Data
	public class AppDetailQuery {

		private String appId;

		private String name;

		private String type;

		private String status;

		@JsonAlias({ "current", "page" })
		private Integer pageNum = 1;

		@JsonAlias("size")
		private Integer pageSize = 20;

	}
