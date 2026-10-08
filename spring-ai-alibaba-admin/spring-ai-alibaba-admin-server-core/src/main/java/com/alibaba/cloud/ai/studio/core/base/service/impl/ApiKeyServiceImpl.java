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

import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.alibaba.cloud.ai.studio.runtime.enums.CommonStatus;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.domain.BaseQuery;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.account.ApiKey;
import com.alibaba.cloud.ai.studio.core.base.service.ApiKeyService;
import com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.base.entity.ApiKeyEntity;
import com.alibaba.cloud.ai.studio.core.base.manager.RedisManager;
import com.alibaba.cloud.ai.studio.core.base.mapper.ApiKeyMapper;
import com.alibaba.cloud.ai.studio.core.utils.security.AESCryptUtils;
import com.alibaba.cloud.ai.studio.core.utils.common.BeanCopierUtils;
import com.alibaba.cloud.ai.studio.core.utils.security.CryptoUtils;
import com.alibaba.cloud.ai.studio.core.utils.common.IdGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static com.alibaba.cloud.ai.studio.core.base.constants.CacheConstants.*;

/** API Key service with account isolation, encryption and cache support. */
@Service
public class ApiKeyServiceImpl extends ServiceImpl<ApiKeyMapper, ApiKeyEntity> implements ApiKeyService {

    private static final int MAX_API_KEY_PER_ACCOUNT = 20;

    private final ApiKeyMapper apiKeyMapper;

    private final RedisManager redisManager;

    public ApiKeyServiceImpl(ApiKeyMapper apiKeyMapper, RedisManager redisManager) {
        this.apiKeyMapper = apiKeyMapper;
        this.redisManager = redisManager;
    }

    @Override
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
        String apiKeyString = IdGenerator.genApiKey();
        entity.setApiKey(AESCryptUtils.encrypt(apiKeyString));
        entity.setAccountId(context.getAccountId());
        entity.setStatus(CommonStatus.NORMAL);
        entity.setGmtCreate(new Date());
        entity.setGmtModified(new Date());
        entity.setCreator(context.getAccountId());
        entity.setModifier(context.getAccountId());
        this.save(entity);

        String key = getApiKeyCacheKey(apiKeyString);
        redisManager.put(key, entity);

        String idKey = getApiKeyCacheKey(context.getAccountId(), entity.getId());
        redisManager.put(idKey, entity);
        return entity.getId();
    }

    @Override
    public void updateApiKey(ApiKey apiKey) {
        RequestContext context = RequestContextHolder.getRequestContext();
        ApiKeyEntity entity = getApiKeyById(context.getAccountId(), apiKey.getId());
        if (entity == null) {
            throw new BizException(ErrorCode.API_KEY_NOT_FOUND.toError());
        }

        // The token itself, account ownership and status are intentionally immutable here.
        entity.setDescription(apiKey.getDescription());
        // Preserve the previous company name when older clients omit this field.
        // An explicit empty string from the new form clears the company name.
        if (apiKey.getCompanyName() != null) {
            entity.setCompanyName(apiKey.getCompanyName().trim());
        }
        entity.setModifier(context.getAccountId());
        entity.setGmtModified(new Date());
        this.updateById(entity);

        // Refresh both caches so a subsequent read does not return old company details.
        String originalKey = AESCryptUtils.decrypt(entity.getApiKey());
        redisManager.put(getApiKeyCacheKey(originalKey), entity);
        redisManager.put(getApiKeyCacheKey(context.getAccountId(), entity.getId()), entity);
    }

    @Override
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

        String originalKey = AESCryptUtils.decrypt(entity.getApiKey());
        redisManager.delete(getApiKeyCacheKey(originalKey));
        redisManager.delete(getApiKeyCacheKey(context.getAccountId(), entity.getId()));
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

        List<ApiKey> accounts;
        if (CollectionUtils.isEmpty(pageResult.getRecords())) {
            accounts = new ArrayList<>();
        }
        else {
            accounts = pageResult.getRecords().stream().map(this::toApiKeyDO).toList();
        }
        return new PagingList<>(query.getCurrent(), query.getSize(), pageResult.getTotal(), accounts);
    }

    @Override
    public ApiKey getApiKey(Long id) {
        RequestContext context = RequestContextHolder.getRequestContext();
        ApiKeyEntity entity = getApiKeyById(context.getAccountId(), id);
        if (entity == null) {
            throw new BizException(ErrorCode.API_KEY_NOT_FOUND.toError());
        }
        return toApiKeyDO(entity, false);
    }

    @Override
    public ApiKey getApiKey(String apiKey) {
        ApiKeyEntity entity = getApiKeyEntity(apiKey);
        if (entity == null) {
            throw new BizException(ErrorCode.API_KEY_NOT_FOUND.toError());
        }
        return toApiKeyDO(entity);
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
        String originApiKey = AESCryptUtils.decrypt(apiKey.getApiKey());
        if (withMask) {
            apiKey.setApiKey(CryptoUtils.mask(originApiKey));
        }
        else {
            apiKey.setApiKey(originApiKey);
        }
        return apiKey;
    }
}
