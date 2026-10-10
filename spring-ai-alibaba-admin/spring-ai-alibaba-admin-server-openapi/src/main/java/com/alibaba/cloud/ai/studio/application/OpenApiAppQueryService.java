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

package com.alibaba.cloud.ai.studio.application;

import com.alibaba.cloud.ai.studio.core.base.service.AppService;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.app.AppQuery;
import com.alibaba.cloud.ai.studio.runtime.domain.app.Application;
import com.alibaba.cloud.ai.studio.runtime.enums.AppStatus;
import com.alibaba.cloud.ai.studio.runtime.enums.AppType;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI application query use case.
 *
 * Supports paginated application queries and returns business-facing OpenAPI metadata.
 * External request and response field names use lower camel case.
 */
@Service
@RequiredArgsConstructor
public class OpenApiAppQueryService {

	private static final int DEFAULT_PAGE_NUM = 1;

	private static final int DEFAULT_PAGE_SIZE = 20;

	private static final int MAX_PAGE_SIZE = 100;

	private final AppService appService;

	private final OpenApiAppViewAssembler assembler;

	/**
	 * Queries applications by filter conditions with pagination.
	 *
	 * Request example:
	 * {
	 *   "name": "demo",
	 *   "type": "workflow",
	 *   "status": "published",
	 *   "pageNum": 1,
	 *   "pageSize": 20
	 * }
	 */
	public AppPageResponse queryAppDetails(AppDetailQuery filter) {
		validateFilter(filter);

		PagingList<Application> page = loadApps(filter);
		List<PublishedAppApiInfo> records = new ArrayList<>();

		if (page != null && !CollectionUtils.isEmpty(page.getRecords())) {
			for (Application app : page.getRecords()) {
				records.add(assembler.buildApiInfo(app));
			}
		}

		AppPageResponse response = new AppPageResponse();
		response.setPageNum(page == null || page.getCurrent() == null ? filter.getPageNum() : page.getCurrent());
		response.setPageSize(page == null || page.getSize() == null ? filter.getPageSize() : page.getSize());
		response.setTotal(page == null || page.getTotal() == null ? 0L : page.getTotal());
		response.setRecords(records);

		return response;
	}

	private void validateFilter(AppDetailQuery filter) {
		if (filter == null) {
			throw new BizException(ErrorCode.MISSING_PARAMS.toError("request body is required"));
		}

		if (StringUtils.isBlank(filter.getAppId())
				&& StringUtils.isBlank(filter.getName())
				&& StringUtils.isBlank(filter.getType())
				&& StringUtils.isBlank(filter.getStatus())) {
			throw new BizException(ErrorCode.MISSING_PARAMS
				.toError("At least one filter is required: appId, name, type or status"));
		}

		if (filter.getPageNum() == null) {
			filter.setPageNum(DEFAULT_PAGE_NUM);
		}
		if (filter.getPageSize() == null) {
			filter.setPageSize(DEFAULT_PAGE_SIZE);
		}

		if (filter.getPageNum() < 1) {
			throw new BizException(ErrorCode.INVALID_PARAMS
				.toError("pageNum", "pageNum must be greater than or equal to 1"));
		}
		if (filter.getPageSize() < 1 || filter.getPageSize() > MAX_PAGE_SIZE) {
			throw new BizException(ErrorCode.INVALID_PARAMS
				.toError("pageSize", "pageSize must be between 1 and " + MAX_PAGE_SIZE));
		}

		normalizeType(filter.getType());
		parseStatus(filter.getStatus());
	}

	private PagingList<Application> loadApps(AppDetailQuery filter) {
		if (StringUtils.isNotBlank(filter.getAppId())) {
			Application app = appService.getApp(filter.getAppId());
			boolean matched = matchesFilter(app, filter);
			long total = matched ? 1L : 0L;

			List<Application> records = matched && filter.getPageNum() == 1
					? List.of(app)
					: List.of();

			return new PagingList<>(filter.getPageNum(), filter.getPageSize(), total, records);
		}

		AppQuery query = new AppQuery();
		query.setCurrent(filter.getPageNum());
		query.setSize(filter.getPageSize());
		query.setName(StringUtils.trimToNull(filter.getName()));
		query.setType(normalizeType(filter.getType()));
		query.setStatus(parseStatus(filter.getStatus()));

		PagingList<Application> page = appService.listApps(query);
		if (page == null) {
			return new PagingList<>(
					filter.getPageNum(),
					filter.getPageSize(),
					0L,
					List.of());
		}
		return page;
	}

	private boolean matchesFilter(Application app, AppDetailQuery filter) {
		if (app == null) {
			return false;
		}

		if (StringUtils.isNotBlank(filter.getName())
				&& (app.getName() == null
					|| !app.getName().toLowerCase().contains(filter.getName().trim().toLowerCase()))) {
			return false;
		}

		String type = normalizeType(filter.getType());
		if (StringUtils.isNotBlank(type)
				&& (app.getType() == null || !type.equals(app.getType().getValue()))) {
			return false;
		}

		AppStatus status = parseStatus(filter.getStatus());
		return status == null || status == app.getStatus();
	}

	private String normalizeType(String type) {
		if (StringUtils.isBlank(type)) {
			return null;
		}

		String normalized = type.trim().toLowerCase();
		for (AppType appType : AppType.values()) {
			if (appType.getValue().equalsIgnoreCase(normalized)
					|| appType.name().equalsIgnoreCase(normalized)) {
				return appType.getValue();
			}
		}

		throw new BizException(ErrorCode.INVALID_PARAMS
			.toError("type", "supported values: basic, workflow"));
	}

	private AppStatus parseStatus(String status) {
		if (StringUtils.isBlank(status)) {
			return null;
		}

		String normalized = status.trim();
		for (AppStatus appStatus : AppStatus.values()) {
			if (appStatus.getValue().equalsIgnoreCase(normalized)
					|| appStatus.name().equalsIgnoreCase(normalized)) {
				if (appStatus == AppStatus.DELETED) {
					throw new BizException(ErrorCode.INVALID_PARAMS
						.toError("status", "deleted applications are not externally queryable"));
				}
				return appStatus;
			}
		}

		throw new BizException(ErrorCode.INVALID_PARAMS
			.toError("status", "supported values: draft, published, published_editing"));
	}

	@Data
	public static class AppDetailQuery {

		private String appId;

		private String name;

		private String type;

		private String status;

		@JsonAlias({ "current", "page" })
		private Integer pageNum = DEFAULT_PAGE_NUM;

		@JsonAlias("size")
		private Integer pageSize = DEFAULT_PAGE_SIZE;

	}

	@Data
	public static class AppPageResponse {

		private Integer pageNum;

		private Integer pageSize;

		private Long total;

		private List<PublishedAppApiInfo> records = new ArrayList<>();

	}

	@Data
	public static class PublishedAppApiInfo {

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

	@Data
	public static class ApiInputParam {

		private String key;

		private String type;

		private String desc;

		private Boolean required;

		private String source;

		private Object defaultValue;

	}

}
