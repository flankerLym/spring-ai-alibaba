package com.alibaba.cloud.ai.studio.application;

import com.alibaba.cloud.ai.studio.openapi.dto.app.ApiInputParam;
import com.alibaba.cloud.ai.studio.openapi.dto.app.PublishedAppApiInfo;
import com.alibaba.cloud.ai.studio.core.base.service.AppService;
import com.alibaba.cloud.ai.studio.core.workflow.WorkflowConfig;
import com.alibaba.cloud.ai.studio.runtime.domain.app.AgentConfig;
import com.alibaba.cloud.ai.studio.runtime.domain.app.Application;
import com.alibaba.cloud.ai.studio.runtime.domain.app.ApplicationVersion;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.CommonParam;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeTypeEnum;
import com.alibaba.cloud.ai.studio.runtime.enums.AppType;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projection assembler for public application details and call examples. */
@Component
@RequiredArgsConstructor
public class OpenApiAppViewAssembler {
    private static final String WORKFLOW_API = "/api/v1/apps/workflow/completions";
    private static final String WORKFLOW_ASYNC_API = "/api/v1/apps/workflow/async-completions";
    private static final String CHAT_API = "/api/v1/apps/chat/completions";

    private final AppService appService;

	public PublishedAppApiInfo buildApiInfo(Application app) {
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

}
