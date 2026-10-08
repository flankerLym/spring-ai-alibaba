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

package com.alibaba.cloud.ai.studio.core.base.service;

import com.alibaba.cloud.ai.studio.runtime.domain.BaseQuery;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.account.ApiKey;

import java.util.Set;

/** Service interface for API key management and app-level authorization. */
public interface ApiKeyService {

    Long createApiKey(ApiKey apiKey);

    void updateApiKey(ApiKey apiKey);

    void deleteApiKey(Long id);

    PagingList<ApiKey> listApiKeys(BaseQuery query);

    ApiKey getApiKey(Long id);

    ApiKey getApiKey(String apiKey);

    /**
     * Returns accessible application ids for a CUSTOM key. Returns null for ALL scope.
     * The returned set is already de-duplicated across direct APP permissions and all
     * selected FOLDER permissions.
     */
    Set<String> getAccessibleAppIds(Long apiKeyId, String workspaceId);

    /** Throws a 403 business exception when this API key cannot access the app. */
    void checkAppAccess(Long apiKeyId, String workspaceId, String appId);
}
