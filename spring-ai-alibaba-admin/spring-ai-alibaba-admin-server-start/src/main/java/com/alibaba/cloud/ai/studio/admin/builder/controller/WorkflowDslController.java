/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.studio.admin.builder.controller;

import java.util.Map;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Parses data only; does not run scripts, call models or publish applications. */
@RestController
@RequestMapping("/console/v1/workflow-dsl")
public class WorkflowDslController {

	@PostMapping("/parse")
	public Result<Map<String, Object>> parse(@RequestBody Map<String, String> request) {
		String dsl = request.get("dsl");
		if (dsl == null || dsl.isBlank() || dsl.length() > 2 * 1024 * 1024) {
			throw invalid();
		}
		Map<String, Object> document;
		try {
			LoaderOptions options = new LoaderOptions();
			options.setAllowDuplicateKeys(false);
			options.setMaxAliasesForCollections(0);
			options.setCodePointLimit(2 * 1024 * 1024);
			Object parsed = new Yaml(new SafeConstructor(options)).load(dsl);
			if (!(parsed instanceof Map<?, ?> map) || map.isEmpty()) {
				throw invalid();
			}
			// Serialize only JSON-compatible data; reject recursive aliases and custom tags.
			validate(parsed, 0);
			@SuppressWarnings("unchecked")
			Map<String, Object> value = (Map<String, Object>) map;
			document = value;
		}
		catch (RuntimeException ex) {
			throw invalid();
		}
		return Result.success(RequestContextHolder.getRequestContext().getRequestId(), document);
	}

	private static void validate(Object value, int depth) {
		if (depth > 64) throw invalid();
		if (value instanceof Map<?, ?> map) {
			map.forEach((key, item) -> {
				if (!(key instanceof String)) throw invalid();
				validate(item, depth + 1);
			});
		}
		else if (value instanceof java.util.List<?> list) {
			list.forEach(item -> validate(item, depth + 1));
		}
		else if (value != null && !(value instanceof String) && !(value instanceof Boolean)
				&& !(value instanceof Number)) throw invalid();
	}

	private static BizException invalid() {
		return new BizException(ErrorCode.WORKFLOW_CONFIG_ILLEGAL.toError("Invalid YAML/JSON DSL (maximum 2 MB)"));
	}
}
