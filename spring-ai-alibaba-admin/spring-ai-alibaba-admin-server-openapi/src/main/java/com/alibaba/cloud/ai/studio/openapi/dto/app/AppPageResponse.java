package com.alibaba.cloud.ai.studio.openapi.dto.app;

import lombok.Data;
import java.util.List;
import java.util.ArrayList;

/** 对外应用查询契约：只承载字段，不承载领域行为。 */
@Data
	public class AppPageResponse {

		private Integer pageNum;

		private Integer pageSize;

		private Long total;

		private List<PublishedAppApiInfo> records = new ArrayList<>();

	}
