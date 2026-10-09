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

package com.alibaba.cloud.ai.studio.core.base.service.impl;

import com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants;
import com.alibaba.cloud.ai.studio.core.base.entity.ApiKeyEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ApiKeyResourcePermissionEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.AppEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ProjectArchiveAppEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ProjectArchiveFolderEntity;
import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.base.mapper.ApiKeyMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.ApiKeyResourcePermissionMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.AppMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.ProjectArchiveAppMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.ProjectArchiveFolderMapper;
import com.alibaba.cloud.ai.studio.core.base.service.ApiKeyService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.utils.common.BeanCopierUtils;
import com.alibaba.cloud.ai.studio.core.utils.common.IdGenerator;
import com.alibaba.cloud.ai.studio.core.utils.security.AESCryptUtils;
import com.alibaba.cloud.ai.studio.core.utils.security.CryptoUtils;
import com.alibaba.cloud.ai.studio.runtime.domain.BaseQuery;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.account.ApiKey;
import com.alibaba.cloud.ai.studio.runtime.enums.AppStatus;
import com.alibaba.cloud.ai.studio.runtime.enums.CommonStatus;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants.CACHE_API_KEY_ID_UID_PREFIX;
import static com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants.CACHE_API_KEY_PREFIX;
import static com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants.CACHE_EMPTY_ID;

/** API Key service with account isolation, encryption, cache and app-level access scope. */
@Service
public class ApiKeyServiceImpl extends ServiceImpl<ApiKeyMapper, ApiKeyEntity> implements ApiKeyService {

    @Autowired
    private ApiKeyPermissionCacheService permissionCache;

    private static final int MAX_API_KEY_PER_ACCOUNT = 20;

    private static final String SCOPE_ALL = "ALL";

    private static final String SCOPE_CUSTOM = "CUSTOM";

    private static final String RESOURCE_APP = "APP";

    private static final String RESOURCE_FOLDER = "FOLDER";

    private final ApiKeyMapper apiKeyMapper;

    private final ApiKeyResourcePermissionMapper permissionMapper;

    private final ProjectArchiveFolderMapper folderMapper;

    private final ProjectArchiveAppMapper archiveAppMapper;

    private final AppMapper appMapper;

    private final RedisManager redisManager;

    public ApiKeyServiceImpl(ApiKeyMapper apiKeyMapper,
            ApiKeyResourcePermissionMapper permissionMapper,
            ProjectArchiveFolderMapper folderMapper,
            ProjectArchiveAppMapper archiveAppMapper,
            AppMapper appMapper,
            RedisManager redisManager) {
        this.apiKeyMapper = apiKeyMapper;
        this.permissionMapper = permissionMapper;
        this.folderMapper = folderMapper;
        this.archiveAppMapper = archiveAppMapper;
        this.appMapper = appMapper;
        this.redisManager = redisManager;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createApiKey(ApiKey apiKey) {
        RequestContext context = RequestContextHolder.getRequestContext();
        long apiKeyCount = getApiKeyCount(context.getAccountId());
        if (apiKeyCount >= MAX_API_KEY_PER_ACCOUNT) {
            throw new BizException(
                    ErrorCode.INVALID_REQUEST.toError("api key can not be more than " + MAX_API_KEY_PER_ACCOUNT + "."));
        }

        ApiKeyEntity entity = BeanCopierUtils.copy(apiKey, ApiKeyEntity.class);
        if (entity.getCompanyName() != null) {
            entity.setCompanyName(entity.getCompanyName().trim());
        }
        entity.setScopeType(normalizeScopeType(apiKey.getScopeType(), SCOPE_ALL));

        String apiKeyString = IdGenerator.genApiKey();
        entity.setApiKey(AESCryptUtils.encrypt(apiKeyString));
        entity.setAccountId(context.getAccountId());
        entity.setStatus(CommonStatus.NORMAL);
        entity.setGmtCreate(new Date());
        entity.setGmtModified(new Date());
        entity.setCreator(context.getAccountId());
        entity.setModifier(context.getAccountId());
        this.save(entity);

        replacePermissions(entity.getId(), entity.getScopeType(), apiKey.getResources(), context);
        afterCommit(() -> {
            refreshCaches(entity, apiKeyString, context.getAccountId());
            permissionCache.refresh(context.getWorkspaceId(), entity.getId());
        });
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateApiKey(ApiKey apiKey) {
        RequestContext context = RequestContextHolder.getRequestContext();
        ApiKeyEntity entity = getApiKeyById(context.getAccountId(), apiKey.getId());
        if (entity == null) {
            throw new BizException(ErrorCode.API_KEY_NOT_FOUND.toError());
        }

        entity.setDescription(apiKey.getDescription());
        if (apiKey.getCompanyName() != null) {
            entity.setCompanyName(apiKey.getCompanyName().trim());
        }

        // Older callers that do not send scopeType keep the existing scope and rows.
        if (apiKey.getScopeType() != null) {
            String scopeType = normalizeScopeType(apiKey.getScopeType(), SCOPE_ALL);
            entity.setScopeType(scopeType);
            replacePermissions(entity.getId(), scopeType, apiKey.getResources(), context);
        }

        entity.setModifier(context.getAccountId());
        entity.setGmtModified(new Date());
        this.updateById(entity);

        String originalKey = AESCryptUtils.decrypt(entity.getApiKey());
        afterCommit(() -> {
            refreshCaches(entity, originalKey, context.getAccountId());
            permissionCache.refresh(context.getWorkspaceId(), entity.getId());
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteApiKey(Long id) {
        RequestContext context = RequestContextHolder.getRequestContext();
        ApiKeyEntity entity = getApiKeyById(context.getAccountId(), id);
        if (entity == null) {
            return;
        }

        entity.setStatus(CommonStatus.DELETED);
        entity.setGmtModified(new Date());
        entity.setModifier(context.getAccountId());
        this.updateById(entity);

        permissionMapper.delete(new LambdaQueryWrapper<ApiKeyResourcePermissionEntity>()
                .eq(ApiKeyResourcePermissionEntity::getApiKeyId, id));

        String originalKey = AESCryptUtils.decrypt(entity.getApiKey());
        afterCommit(() -> {
            redisManager.delete(getApiKeyCacheKey(originalKey));
            redisManager.delete(getApiKeyCacheKey(context.getAccountId(), entity.getId()));
            permissionCache.evict(context.getWorkspaceId(), id);
        });
    }

    @Override
    public PagingList<ApiKey> listApiKeys(BaseQuery query) {
        RequestContext context = RequestContextHolder.getRequestContext();
        LambdaQueryWrapper<ApiKeyEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ApiKeyEntity::getAccountId, context.getAccountId());
        queryWrapper.ne(ApiKeyEntity::getStatus, CommonStatus.DELETED.getStatus());
        queryWrapper.orderByDesc(ApiKeyEntity::getId);

        Page<ApiKeyEntity> page = new Page<>(query.getCurrent(), query.getSize());
        IPage<ApiKeyEntity> pageResult = this.page(page, queryWrapper);

        List<ApiKey> apiKeys;
        if (CollectionUtils.isEmpty(pageResult.getRecords())) {
            apiKeys = new ArrayList<>();
        }
        else {
            apiKeys = pageResult.getRecords().stream().map(this::toApiKeyDO).toList();
        }
        return new PagingList<>(query.getCurrent(), query.getSize(), pageResult.getTotal(), apiKeys);
    }

    @Override
    public ApiKey getApiKey(Long id) {
        RequestContext context = RequestContextHolder.getRequestContext();
        ApiKeyEntity entity = getApiKeyById(context.getAccountId(), id);
        if (entity == null) {
            throw new BizException(ErrorCode.API_KEY_NOT_FOUND.toError());
        }

        ApiKey apiKey = toApiKeyDO(entity, false);
        apiKey.setResources(loadResources(id, context.getWorkspaceId()));
        return apiKey;
    }

    @Override
    public ApiKey getApiKey(String apiKey) {
        ApiKeyEntity entity = getApiKeyEntity(apiKey);
        if (entity == null) {
            throw new BizException(ErrorCode.API_KEY_NOT_FOUND.toError());
        }
        return toApiKeyDO(entity);
    }

    @Override
    public Set<String> getAccessibleAppIds(Long apiKeyId, String workspaceId) {
        if (apiKeyId == null) {
            return null;
        }

        RequestContext context = RequestContextHolder.getRequestContext();
        ApiKeyEntity apiKey = getApiKeyById(context.getAccountId(), apiKeyId);
        if (apiKey == null) {
            throw new BizException(ErrorCode.INVALID_API_KEY.toError());
        }

        // Null/blank scope is treated as ALL so existing keys keep their old behavior.
        if (!SCOPE_CUSTOM.equalsIgnoreCase(StringUtils.defaultIfBlank(apiKey.getScopeType(), SCOPE_ALL))) {
            return null;
        }

        List<ApiKeyResourcePermissionEntity> permissions =
                permissionCache.get(workspaceId, apiKeyId);

        Set<String> appIds = new LinkedHashSet<>();
        Set<String> folderIds = new LinkedHashSet<>();
        for (ApiKeyResourcePermissionEntity permission : permissions) {
            if (RESOURCE_APP.equals(permission.getResourceType())) {
                appIds.add(permission.getResourceId());
            }
            else if (RESOURCE_FOLDER.equals(permission.getResourceType())) {
                folderIds.add(permission.getResourceId());
            }
        }

        if (!folderIds.isEmpty()) {
            List<ProjectArchiveAppEntity> folderApps = archiveAppMapper.selectList(
                    new LambdaQueryWrapper<ProjectArchiveAppEntity>()
                            .eq(ProjectArchiveAppEntity::getWorkspaceId, workspaceId)
                            .in(ProjectArchiveAppEntity::getFolderId, folderIds));
            folderApps.forEach(item -> appIds.add(item.getAppId()));
        }
        return appIds;
    }

    @Override
    public void checkAppAccess(Long apiKeyId, String workspaceId, String appId) {
        if (apiKeyId == null || StringUtils.isBlank(appId)) {
            return;
        }
        Set<String> appIds = getAccessibleAppIds(apiKeyId, workspaceId);
        if (appIds != null && !appIds.contains(appId)) {
            throw new BizException(ErrorCode.PERMISSION_DENIED.toError());
        }
    }

    private void replacePermissions(Long apiKeyId, String scopeType,
            List<ApiKey.ResourcePermission> resources, RequestContext context) {
        permissionMapper.delete(new LambdaQueryWrapper<ApiKeyResourcePermissionEntity>()
                .eq(ApiKeyResourcePermissionEntity::getApiKeyId, apiKeyId));

        if (!SCOPE_CUSTOM.equals(scopeType) || CollectionUtils.isEmpty(resources)) {
            return;
        }

        Map<String, ApiKey.ResourcePermission> unique = new LinkedHashMap<>();
        for (ApiKey.ResourcePermission resource : resources) {
            if (resource == null || StringUtils.isBlank(resource.getType()) || StringUtils.isBlank(resource.getId())) {
                continue;
            }
            String type = resource.getType().trim().toUpperCase(Locale.ROOT);
            String resourceId = resource.getId().trim();
            if (!RESOURCE_APP.equals(type) && !RESOURCE_FOLDER.equals(type)) {
                throw new BizException(ErrorCode.INVALID_PARAMS
                        .toError("resources.type", "supported values: APP, FOLDER"));
            }
            ApiKey.ResourcePermission normalized = new ApiKey.ResourcePermission();
            normalized.setType(type);
            normalized.setId(resourceId);
            unique.put(type + ":" + resourceId, normalized);
        }

        for (ApiKey.ResourcePermission resource : unique.values()) {
            validateResource(resource, context.getWorkspaceId());
            ApiKeyResourcePermissionEntity entity = new ApiKeyResourcePermissionEntity();
            entity.setApiKeyId(apiKeyId);
            entity.setWorkspaceId(context.getWorkspaceId());
            entity.setResourceType(resource.getType());
            entity.setResourceId(resource.getId());
            entity.setGmtCreate(new Date());
            entity.setCreator(context.getAccountId());
            permissionMapper.insert(entity);
        }
    }

    private void validateResource(ApiKey.ResourcePermission resource, String workspaceId) {
        if (RESOURCE_FOLDER.equals(resource.getType())) {
            Long count = folderMapper.selectCount(new LambdaQueryWrapper<ProjectArchiveFolderEntity>()
                    .eq(ProjectArchiveFolderEntity::getWorkspaceId, workspaceId)
                    .eq(ProjectArchiveFolderEntity::getFolderId, resource.getId()));
            if (count == null || count == 0) {
                throw new BizException(ErrorCode.INVALID_PARAMS
                        .toError("folderId", "folder does not exist in current workspace"));
            }
            return;
        }

        Long count = appMapper.selectCount(new LambdaQueryWrapper<AppEntity>()
                .eq(AppEntity::getWorkspaceId, workspaceId)
                .eq(AppEntity::getAppId, resource.getId())
                .ne(AppEntity::getStatus, AppStatus.DELETED.getStatus()));
        if (count == null || count == 0) {
            throw new BizException(ErrorCode.INVALID_PARAMS
                    .toError("appId", "app does not exist in current workspace"));
        }
    }

    private List<ApiKey.ResourcePermission> loadResources(Long apiKeyId, String workspaceId) {
        List<ApiKeyResourcePermissionEntity> entities = permissionMapper.selectList(
                new LambdaQueryWrapper<ApiKeyResourcePermissionEntity>()
                        .eq(ApiKeyResourcePermissionEntity::getApiKeyId, apiKeyId)
                        .eq(ApiKeyResourcePermissionEntity::getWorkspaceId, workspaceId)
                        .orderByAsc(ApiKeyResourcePermissionEntity::getResourceType,
                                ApiKeyResourcePermissionEntity::getResourceId));
        List<ApiKey.ResourcePermission> result = new ArrayList<>();
        for (ApiKeyResourcePermissionEntity entity : entities) {
            ApiKey.ResourcePermission resource = new ApiKey.ResourcePermission();
            resource.setType(entity.getResourceType());
            resource.setId(entity.getResourceId());
            result.add(resource);
        }
        return result;
    }

    private String normalizeScopeType(String scopeType, String defaultValue) {
        String normalized = StringUtils.defaultIfBlank(scopeType, defaultValue).trim().toUpperCase(Locale.ROOT);
        if (!SCOPE_ALL.equals(normalized) && !SCOPE_CUSTOM.equals(normalized)) {
            throw new BizException(ErrorCode.INVALID_PARAMS
                    .toError("scopeType", "supported values: ALL, CUSTOM"));
        }
        return normalized;
    }

    private void refreshCaches(ApiKeyEntity entity, String originalKey, String accountId) {
        redisManager.put(getApiKeyCacheKey(originalKey), entity);
        redisManager.put(getApiKeyCacheKey(accountId, entity.getId()), entity);
    }

    private long getApiKeyCount(String uid) {
        LambdaQueryWrapper<ApiKeyEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ApiKeyEntity::getAccountId, uid)
                .ne(ApiKeyEntity::getStatus, CommonStatus.DELETED.getStatus());
        return apiKeyMapper.selectCount(queryWrapper);
    }

    public static String getApiKeyCacheKey(String apiKey) {
        return String.format(CACHE_API_KEY_PREFIX, apiKey);
    }

    public static String getApiKeyCacheKey(String uid, Long id) {
        return String.format(CACHE_API_KEY_ID_UID_PREFIX, uid, id);
    }

    private ApiKeyEntity getApiKeyById(String uid, Long id) {
        String key = getApiKeyCacheKey(uid, id);
        ApiKeyEntity entity = redisManager.get(key);
        if (entity != null) {
            if (CACHE_EMPTY_ID.equals(entity.getId())) {
                return null;
            }
            return entity;
        }

        LambdaQueryWrapper<ApiKeyEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ApiKeyEntity::getId, id)
                .eq(ApiKeyEntity::getAccountId, uid)
                .ne(ApiKeyEntity::getStatus, CommonStatus.DELETED.getStatus());

        Optional<ApiKeyEntity> entityOptional = this.getOneOpt(queryWrapper);
        entity = entityOptional.orElse(null);
        if (entity == null) {
            entity = new ApiKeyEntity();
            entity.setId(CACHE_EMPTY_ID);
            redisManager.put(key, entity, CacheConstants.CACHE_EMPTY_TTL);
            return null;
        }
        redisManager.put(key, entity);
        return entity;
    }

    private ApiKeyEntity getApiKeyEntity(String apiKey) {
        String key = getApiKeyCacheKey(apiKey);
        ApiKeyEntity entity = redisManager.get(key);
        if (entity != null) {
            if (CACHE_EMPTY_ID.equals(entity.getId())) {
                return null;
            }
            return entity;
        }

        String encrypted = AESCryptUtils.encrypt(apiKey);
        LambdaQueryWrapper<ApiKeyEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ApiKeyEntity::getApiKey, encrypted)
                .ne(ApiKeyEntity::getStatus, CommonStatus.DELETED.getStatus());
        Optional<ApiKeyEntity> entityOptional = this.getOneOpt(queryWrapper);
        entity = entityOptional.orElse(null);
        if (entity == null) {
            entity = new ApiKeyEntity();
            entity.setId(CACHE_EMPTY_ID);
            redisManager.put(key, entity, CacheConstants.CACHE_EMPTY_TTL);
            return null;
        }
        redisManager.put(key, entity);
        return entity;
    }

    private ApiKey toApiKeyDO(ApiKeyEntity entity) {
        return toApiKeyDO(entity, true);
    }

    private ApiKey toApiKeyDO(ApiKeyEntity entity, boolean withMask) {
        if (entity == null) {
            return null;
        }
        ApiKey apiKey = BeanCopierUtils.copy(entity, ApiKey.class);
        apiKey.setScopeType(StringUtils.defaultIfBlank(entity.getScopeType(), SCOPE_ALL));
        apiKey.setResources(new ArrayList<>());
        String originApiKey = AESCryptUtils.decrypt(apiKey.getApiKey());
        if (withMask) {
            apiKey.setApiKey(CryptoUtils.mask(originApiKey));
        }
        else {
            apiKey.setApiKey(originApiKey);
        }
        return apiKey;
    }
    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                }
        );
    }
}
