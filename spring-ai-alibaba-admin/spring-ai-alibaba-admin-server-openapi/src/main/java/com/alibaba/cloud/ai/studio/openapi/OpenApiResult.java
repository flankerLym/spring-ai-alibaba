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

package com.alibaba.cloud.ai.studio.openapi;

import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * OpenAPI response wrapper using lower camel case field names.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenApiResult<T> implements Serializable {

	private String requestId;

	private Integer code;

	private String message;

	private T data;

	public static <T> OpenApiResult<T> success(String requestId, T data) {
		return OpenApiResult.<T>builder()
			.requestId(requestId)
			.code(200)
			.message("success")
			.data(data)
			.build();
	}

	public static <T> OpenApiResult<T> error(String requestId, ErrorCode errorCode) {
		return error(requestId, errorCode.toError());
	}

	public static <T> OpenApiResult<T> error(String requestId, Error error) {
		return OpenApiResult.<T>builder()
			.requestId(requestId)
			.code(error.getStatusCode())
			.message(error.getMessage())
			.build();
	}

}
