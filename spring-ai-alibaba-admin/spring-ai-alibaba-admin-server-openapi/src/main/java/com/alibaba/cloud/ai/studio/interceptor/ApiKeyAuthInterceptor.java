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

package com.alibaba.cloud.ai.studio.interceptor;

import com.alibaba.cloud.ai.studio.runtime.constants.ApiConstants;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.account.Account;
import com.alibaba.cloud.ai.studio.runtime.domain.account.ApiKey;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import com.alibaba.cloud.ai.studio.core.base.service.AccountService;
import com.alibaba.cloud.ai.studio.core.base.service.ApiKeyService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.utils.common.IdGenerator;
import com.alibaba.cloud.ai.studio.core.utils.LogUtils;
import com.alibaba.cloud.ai.studio.openapi.OpenApiResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Interceptor for API key authentication in OpenAPI requests.
 */
@Component
@RequiredArgsConstructor
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

	private final AccountService accountService;

	private final ApiKeyService apiKeyService;

	@Override
	public boolean preHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
			@NotNull Object handler) {
		long start = System.currentTimeMillis();

		if (RequestMethod.OPTIONS.name().equals(request.getMethod().toUpperCase())) {
			return true;
		}

		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null || !authorization.startsWith(ApiConstants.TOKEN_PREFIX)) {
			returnAuthError(start, response, ErrorCode.INVALID_API_KEY);
			return false;
		}

		String token = authorization.replace(ApiConstants.TOKEN_PREFIX + " ", "");
		ApiKey apiKey = apiKeyService.getApiKey(token);
		if (apiKey == null) {
			returnAuthError(start, response, ErrorCode.INVALID_API_KEY);
			return false;
		}

		Account account = accountService.getAccount(apiKey.getAccountId());
		if (account == null) {
			returnAuthError(start, response, ErrorCode.INVALID_API_KEY);
			return false;
		}

		RequestContext context = new RequestContext();
		context.setRequestId(IdGenerator.uuid());
		context.setAccountId(account.getAccountId());
		context.setUsername(account.getUsername());
		context.setWorkspaceId(account.getDefaultWorkspaceId());
		context.setAccountType(account.getType());
		context.setCallerIp(request.getRemoteAddr());
		context.setStartTime(System.currentTimeMillis());

		RequestContextHolder.setRequestContext(context);
		return true;
	}

	public void returnAuthError(long start, HttpServletResponse response, ErrorCode errorCode) {
		response.setContentType("application/json;charset=UTF-8");
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		OpenApiResult<String> result = OpenApiResult.error(IdGenerator.uuid(), errorCode);

		LogUtils.monitor("ApiAuthInterceptor", "apiKeyAuth", start, "unauthorized", "", result);

		try {
			response.getWriter().write(JsonUtils.toJson(result));
		}
		catch (IOException e) {
			LogUtils.error("failed to unauthorized api key: {}", result, e);
		}
	}

}
