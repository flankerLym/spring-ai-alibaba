package com.alibaba.cloud.ai.studio.openapi.dto.app;

import lombok.Data;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** 对外应用查询契约：只承载字段，不承载领域行为。 */
@Data
	public class PublishedAppApiInfo {

		private String appId;

		private String name;

		private String description;

		private String type;

		private String status;

		private String icon;

		private String source;

		private java.util.Date gmtCreate;

		private java.util.Date gmtModified;

		private String publishedVersion;

		private String prologueText;

		private String method;

		private String api;

		private String asyncApi;

		private String auth;

		private String contentType;

		private List<ApiInputParam> inputSchema = new ArrayList<>();

		private Map<String, Object> requestJson = new LinkedHashMap<>();

		private String schemaError;

	}
