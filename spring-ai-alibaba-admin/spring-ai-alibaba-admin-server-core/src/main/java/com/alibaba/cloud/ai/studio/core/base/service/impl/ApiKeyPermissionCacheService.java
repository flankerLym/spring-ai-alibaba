package com.alibaba.cloud.ai.studio.core.base.service.impl;

import com.alibaba.cloud.ai.studio.core.base.entity.ApiKeyResourcePermissionEntity;
import com.alibaba.cloud.ai.studio.core.base.mapper.ApiKeyResourcePermissionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ApiKeyPermissionCacheService {

    private final ApiKeyResourcePermissionMapper mapper;

    @Cacheable(cacheNames = "apiKeyPermissions", key = "#p0 + ':' + #p1")
    public List<ApiKeyResourcePermissionEntity> get(String workspaceId, Long id) {
        return load(workspaceId, id);
    }

    @CachePut(cacheNames = "apiKeyPermissions", key = "#p0 + ':' + #p1")
    public List<ApiKeyResourcePermissionEntity> refresh(String workspaceId, Long id) {
        return load(workspaceId, id);
    }

    @CacheEvict(cacheNames = "apiKeyPermissions", key = "#p0 + ':' + #p1")
    public void evict(String workspaceId, Long id) {}

    private List<ApiKeyResourcePermissionEntity> load(String workspaceId, Long id) {
        return mapper.selectList(
                Wrappers.<ApiKeyResourcePermissionEntity>lambdaQuery()
                        .eq(ApiKeyResourcePermissionEntity::getWorkspaceId, workspaceId)
                        .eq(ApiKeyResourcePermissionEntity::getApiKeyId, id)
        );
    }
}