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

package com.alibaba.cloud.ai.studio.controller;

import com.alibaba.cloud.ai.studio.core.base.service.AppService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowConfig;
import com.alibaba.cloud.ai.studio.openapi.OpenApiResult;
import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.app.AgentConfig;
import com.alibaba.cloud.ai.studio.runtime.domain.app.AppQuery;
import com.alibaba.cloud.ai.studio.runtime.domain.app.Application;
import com.alibaba.cloud.ai.studio.runtime.domain.app.ApplicationVersion;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.CommonParam;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import com.alibaba.cloud.ai.studio.runtime.enums.AppStatus;
import com.alibaba.cloud.ai.studio.runtime.enums.AppType;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI application query endpoint.
 *
 * Supports paginated application queries and returns business-facing OpenAPI metadata.
 * External request and response field names use lower camel case.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "openapi-apps")
@RequestMapping("/api/v1/apps")
public class OpenApiAppController {

	private static final int DEFAULT_PAGE_NUM = 1;

	private static final int DEFAULT_PAGE_SIZE = 20;

	private static final int MAX_PAGE_SIZE = 100;

	private static final String WORKFLOW_API = "/api/v1/apps/workflow/completions";

	private static final String WORKFLOW_ASYNC_API = "/api/v1/apps/workflow/async-completions";

	private static final String CHAT_API = "/api/v1/apps/chat/completions";

	private final AppService appService;

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
	@PostMapping("")
	@Operation(summary = "Query application details by filter conditions with pagination")
	public OpenApiResult<AppPageResponse> queryAppDetails(@RequestBody AppDetailQuery filter) {
		RequestContext context = RequestContextHolder.getRequestContext();
		validateFilter(filter);

		PagingList<Application> page = loadApps(filter);
		List<PublishedAppApiInfo> records = new ArrayList<>();

		if (page != null && !CollectionUtils.isEmpty(page.getRecords())) {
			for (Application app : page.getRecords()) {
				records.add(buildApiInfo(app));
			}
		}

		AppPageResponse response = new AppPageResponse();
		response.setPageNum(page == null || page.getCurrent() == null ? filter.getPageNum() : page.getCurrent());
		response.setPageSize(page == null || page.getSize() == null ? filter.getPageSize() : page.getSize());
		response.setTotal(page == null || page.getTotal() == null ? 0L : page.getTotal());
		response.setRecords(records);

		return OpenApiResult.success(context.getRequestId(), response);
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

	private PublishedAppApiInfo buildApiInfo(Application app) {
		PublishedAppApiInfo info = new PublishedAppApiInfo();
		info.setAppId(app.getAppId());
		info.setName(app.getName());
		info.setDescription(app.getDescription());
		info.setType(app.getType() == null ? null : app.getType().getValue());
		info.setStatus(app.getStatus() == null ? null : app.getStatus().getValue());
		info.setIcon(app.getIcon());
		info.setSource(app.getSource());
		info.setGmtCreate(app.getGmtCreate());
		info.setGmtModified(app.getGmtModified());
		info.setMethod("POST");
		info.setAuth("Authorization: Bearer <API_KEY>");
		info.setContentType("application/json");

		try {
			ApplicationVersion publishedVersion =
					appService.getAppVersion(app.getAppId(), "lastPublished");
			if (publishedVersion == null || StringUtils.isBlank(publishedVersion.getConfig())) {
				info.setSchemaError("Published version not found");
				return info;
			}

			info.setPublishedVersion(publishedVersion.getVersion());

			if (app.getType() == AppType.WORKFLOW) {
				buildWorkflowInfo(info, app, publishedVersion);
			}
			else if (app.getType() == AppType.BASIC) {
				buildBasicAppInfo(info, app, publishedVersion);
			}
			else {
				info.setSchemaError("Unsupported application type: " + app.getType());
			}
		}
		catch (Exception e) {
			info.setSchemaError(e.getMessage());
		}

		return info;
	}

	private void buildWorkflowInfo(PublishedAppApiInfo info, Application app,
			ApplicationVersion publishedVersion) {

		WorkflowConfig workflowConfig =
				JsonUtils.fromJson(publishedVersion.getConfig(), WorkflowConfig.class);

		List<ApiInputParam> params = new ArrayList<>();

		ApiInputParam query = new ApiInputParam();
		query.setKey("query");
		query.setType("String");
		query.setDesc("User query");
		query.setRequired(false);
		query.setSource("sys");
		query.setDefaultValue(null);
		params.add(query);

		if (workflowConfig != null && !CollectionUtils.isEmpty(workflowConfig.getNodes())) {
			workflowConfig.getNodes()
				.stream()
				.filter(node -> NodeTypeEnum.START.getCode().equals(node.getType()))
				.findFirst()
				.ifPresent(startNode -> appendStartParams(params, startNode));
		}

		info.setApi(WORKFLOW_API);
		info.setAsyncApi(WORKFLOW_ASYNC_API);
		info.setInputSchema(params);
		info.setRequestJson(buildWorkflowRequestExample(app.getAppId(), params));
	}

	private void appendStartParams(List<ApiInputParam> params, Node startNode) {
		if (startNode.getConfig() == null
				|| CollectionUtils.isEmpty(startNode.getConfig().getOutputParams())) {
			return;
		}

		for (Node.OutputParam outputParam : startNode.getConfig().getOutputParams()) {
			if (outputParam == null || StringUtils.isBlank(outputParam.getKey())
					|| "query".equals(outputParam.getKey())) {
				continue;
			}

			ApiInputParam param = copyParam(outputParam);
			param.setSource("user");
			params.add(param);
		}
	}

	private Map<String, Object> buildWorkflowRequestExample(String appId,
			List<ApiInputParam> params) {

		Map<String, Object> request = new LinkedHashMap<>();
		request.put("appId", appId);
		request.put("conversationId", "");
		request.put("stream", false);
		request.put("draft", false);

		List<Map<String, Object>> inputParams = new ArrayList<>();
		for (ApiInputParam param : params) {
			Map<String, Object> input = new LinkedHashMap<>();
			input.put("key", param.getKey());
			input.put("type", param.getType());
			input.put("source", param.getSource());
			input.put("value", exampleValue(param));
			inputParams.add(input);
		}
		request.put("inputParams", inputParams);

		return request;
	}

	private void buildBasicAppInfo(PublishedAppApiInfo info, Application app,
			ApplicationVersion publishedVersion) {

		AgentConfig config =
				JsonUtils.fromJson(publishedVersion.getConfig(), AgentConfig.class);

		if (config != null && config.getPrologue() != null
				&& StringUtils.isNotBlank(config.getPrologue().getPrologueText())) {
			info.setPrologueText(config.getPrologue().getPrologueText());
		}

		List<ApiInputParam> params = new ArrayList<>();

		ApiInputParam messages = new ApiInputParam();
		messages.setKey("messages");
		messages.setType("Array");
		messages.setDesc("Chat messages");
		messages.setRequired(true);
		messages.setSource("body");
		params.add(messages);

		if (config != null && !CollectionUtils.isEmpty(config.getPromptVariables())) {
			for (AgentConfig.PromptVariable variable : config.getPromptVariables()) {
				ApiInputParam param = new ApiInputParam();
				param.setKey(variable.getName());
				param.setType(variable.getType());
				param.setDesc(variable.getDescription());
				param.setRequired(false);
				param.setSource("promptVariables");
				param.setDefaultValue(variable.getDefaultValue());
				params.add(param);
			}
		}

		Map<String, Object> request = new LinkedHashMap<>();
		request.put("appId", app.getAppId());
		request.put("conversationId", "");
		request.put("stream", false);

		List<Map<String, Object>> messagesExample = new ArrayList<>();
		Map<String, Object> userMessage = new LinkedHashMap<>();
		userMessage.put("role", "user");
		userMessage.put("contentType", "text");
		userMessage.put("content", "你好");
		messagesExample.add(userMessage);
		request.put("messages", messagesExample);

		Map<String, Object> promptVariables = new LinkedHashMap<>();
		for (ApiInputParam param : params) {
			if ("promptVariables".equals(param.getSource())) {
				promptVariables.put(param.getKey(), exampleValue(param));
			}
		}
		request.put("promptVariables", promptVariables);

		info.setApi(CHAT_API);
		info.setInputSchema(params);
		info.setRequestJson(request);
	}

	private ApiInputParam copyParam(CommonParam source) {
		ApiInputParam target = new ApiInputParam();
		target.setKey(source.getKey());
		target.setType(source.getType());
		target.setDesc(source.getDesc());
		target.setRequired(Boolean.TRUE.equals(source.getRequired()));
		target.setDefaultValue(source.getDefaultValue());
		return target;
	}

	private Object exampleValue(ApiInputParam param) {
		if (param.getDefaultValue() != null) {
			return param.getDefaultValue();
		}

		if ("query".equals(param.getKey())) {
			return "请输入用户问题";
		}

		String type = StringUtils.defaultString(param.getType()).toLowerCase();
		return switch (type) {
			case "number", "integer", "long", "double", "float" -> 0;
			case "boolean" -> false;
			case "array", "list" -> List.of();
			case "object", "json" -> Map.of();
			default -> "";
		};
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<OpenApiResult<Void>> handleException(Exception exception) {
		RequestContext context = RequestContextHolder.getRequestContext();
		String requestId = context == null ? "" : context.getRequestId();

		Error error;
		if (exception instanceof BizException bizException) {
			error = bizException.getError();
		}
		else if (exception instanceof HttpMessageNotReadableException) {
			error = ErrorCode.INVALID_JSON.toError();
		}
		else {
			error = ErrorCode.SYSTEM_ERROR.toError();
		}

		return ResponseEntity.status(error.getStatusCode())
			.body(OpenApiResult.error(requestId, error));
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
