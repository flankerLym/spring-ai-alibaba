/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.cloud.ai.studio.core.base.service.impl;

import com.alibaba.cloud.ai.studio.core.base.entity.AppEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ProjectArchiveAppEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ProjectArchiveFolderEntity;
import com.alibaba.cloud.ai.studio.core.base.mapper.AppMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.ProjectArchiveAppMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.ProjectArchiveFolderMapper;
import com.alibaba.cloud.ai.studio.core.base.service.AppService;
import com.alibaba.cloud.ai.studio.core.base.service.ProjectArchiveService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.utils.common.BeanCopierUtils;
import com.alibaba.cloud.ai.studio.core.utils.common.IdGenerator;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.app.AppQuery;
import com.alibaba.cloud.ai.studio.runtime.domain.app.Application;
import com.alibaba.cloud.ai.studio.runtime.domain.app.ProjectArchiveFolder;
import com.alibaba.cloud.ai.studio.runtime.enums.AppStatus;
import com.alibaba.cloud.ai.studio.runtime.enums.CommonStatus;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ProjectArchiveServiceImpl implements ProjectArchiveService {

	private static final int MAX_FOLDER_NAME_LENGTH = 100;

	private final ProjectArchiveFolderMapper folderMapper;

	private final ProjectArchiveAppMapper archiveAppMapper;

	private final AppMapper appMapper;

	private final AppService appService;

	public ProjectArchiveServiceImpl(ProjectArchiveFolderMapper folderMapper,
			ProjectArchiveAppMapper archiveAppMapper, AppMapper appMapper, AppService appService) {
		this.folderMapper = folderMapper;
		this.archiveAppMapper = archiveAppMapper;
		this.appMapper = appMapper;
		this.appService = appService;
	}

	@Override
	public List<ProjectArchiveFolder> listFolders() {
		RequestContext context = RequestContextHolder.getRequestContext();
		LambdaQueryWrapper<ProjectArchiveFolderEntity> wrapper = new LambdaQueryWrapper<>();
		wrapper.eq(ProjectArchiveFolderEntity::getWorkspaceId, context.getWorkspaceId())
			.orderByDesc(ProjectArchiveFolderEntity::getGmtModified)
			.orderByDesc(ProjectArchiveFolderEntity::getId);

		List<ProjectArchiveFolderEntity> entities = folderMapper.selectList(wrapper);
		List<ProjectArchiveFolder> result = new ArrayList<>(entities.size());
		for (ProjectArchiveFolderEntity entity : entities) {
			ProjectArchiveFolder folder = BeanCopierUtils.copy(entity, ProjectArchiveFolder.class);
			folder.setAppCount(countValidApps(context.getWorkspaceId(), entity.getFolderId()));
			result.add(folder);
		}
		return result;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public ProjectArchiveFolder createFolder(String name) {
		RequestContext context = RequestContextHolder.getRequestContext();
		String normalizedName = normalizeFolderName(name);
		ensureFolderNameAvailable(context.getWorkspaceId(), normalizedName, null);

		Date now = new Date();
		ProjectArchiveFolderEntity entity = new ProjectArchiveFolderEntity();
		entity.setWorkspaceId(context.getWorkspaceId());
		entity.setFolderId(IdGenerator.idStr());
		entity.setName(normalizedName);
		entity.setGmtCreate(now);
		entity.setGmtModified(now);
		entity.setCreator(context.getAccountId());
		entity.setModifier(context.getAccountId());
		folderMapper.insert(entity);

		ProjectArchiveFolder folder = BeanCopierUtils.copy(entity, ProjectArchiveFolder.class);
		folder.setAppCount(0L);
		return folder;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public ProjectArchiveFolder renameFolder(String folderId, String name) {
		RequestContext context = RequestContextHolder.getRequestContext();
		ProjectArchiveFolderEntity entity = requireFolder(context.getWorkspaceId(), folderId);
		String normalizedName = normalizeFolderName(name);
		ensureFolderNameAvailable(context.getWorkspaceId(), normalizedName, folderId);

		entity.setName(normalizedName);
		entity.setGmtModified(new Date());
		entity.setModifier(context.getAccountId());
		folderMapper.updateById(entity);

		ProjectArchiveFolder folder = BeanCopierUtils.copy(entity, ProjectArchiveFolder.class);
		folder.setAppCount(countValidApps(context.getWorkspaceId(), folderId));
		return folder;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void deleteFolder(String folderId) {
		RequestContext context = RequestContextHolder.getRequestContext();
		ProjectArchiveFolderEntity folder = requireFolder(context.getWorkspaceId(), folderId);

		LambdaQueryWrapper<ProjectArchiveAppEntity> relationWrapper = new LambdaQueryWrapper<>();
		relationWrapper.eq(ProjectArchiveAppEntity::getWorkspaceId, context.getWorkspaceId())
			.eq(ProjectArchiveAppEntity::getFolderId, folderId);
		archiveAppMapper.delete(relationWrapper);
		folderMapper.deleteById(folder.getId());
	}

	@Override
	public PagingList<Application> listFolderApps(String folderId, AppQuery query) {
		RequestContext context = RequestContextHolder.getRequestContext();
		requireFolder(context.getWorkspaceId(), folderId);
		AppQuery safeQuery = query == null ? new AppQuery() : query;

		List<String> appIds = listFolderAppIds(context.getWorkspaceId(), folderId);
		if (CollectionUtils.isEmpty(appIds)) {
			return new PagingList<>(safeQuery.getCurrent(), safeQuery.getSize(), 0L, new ArrayList<>());
		}

		Page<AppEntity> page = new Page<>(safeQuery.getCurrent(), safeQuery.getSize());
		LambdaQueryWrapper<AppEntity> wrapper = new LambdaQueryWrapper<>();
		wrapper.eq(AppEntity::getWorkspaceId, context.getWorkspaceId())
			.in(AppEntity::getAppId, appIds);
		if (StringUtils.isNotBlank(safeQuery.getName())) {
			wrapper.like(AppEntity::getName, safeQuery.getName());
		}
		if (StringUtils.isNotBlank(safeQuery.getType())) {
			wrapper.eq(AppEntity::getType, safeQuery.getType());
		}
		if (safeQuery.getStatus() == null || safeQuery.getStatus() == AppStatus.DELETED) {
			wrapper.ne(AppEntity::getStatus, CommonStatus.DELETED.getStatus());
		}
		else {
			wrapper.eq(AppEntity::getStatus, safeQuery.getStatus().getStatus());
		}
		wrapper.orderByDesc(AppEntity::getGmtModified).orderByDesc(AppEntity::getId);

		IPage<AppEntity> pageResult = appMapper.selectPage(page, wrapper);
		List<Application> applications = pageResult.getRecords()
			.stream()
			.map(entity -> BeanCopierUtils.copy(entity, Application.class))
			.toList();
		return new PagingList<>(safeQuery.getCurrent(), safeQuery.getSize(), pageResult.getTotal(), applications);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void addApps(String folderId, List<String> appIds) {
		RequestContext context = RequestContextHolder.getRequestContext();
		requireFolder(context.getWorkspaceId(), folderId);
		if (CollectionUtils.isEmpty(appIds)) {
			return;
		}

		Set<String> normalizedIds = new LinkedHashSet<>();
		for (String appId : appIds) {
			if (StringUtils.isNotBlank(appId)) {
				normalizedIds.add(appId.trim());
			}
		}

		for (String appId : normalizedIds) {
			requireApp(context.getWorkspaceId(), appId);

			LambdaQueryWrapper<ProjectArchiveAppEntity> existingWrapper = new LambdaQueryWrapper<>();
			existingWrapper.eq(ProjectArchiveAppEntity::getWorkspaceId, context.getWorkspaceId())
				.eq(ProjectArchiveAppEntity::getAppId, appId);
			ProjectArchiveAppEntity existing = archiveAppMapper.selectOne(existingWrapper);
			if (existing != null && folderId.equals(existing.getFolderId())) {
				continue;
			}
			if (existing != null) {
				String oldFolderId = existing.getFolderId();
				archiveAppMapper.deleteById(existing.getId());
				touchFolder(context.getWorkspaceId(), oldFolderId, context.getAccountId());
			}

			ProjectArchiveAppEntity relation = new ProjectArchiveAppEntity();
			relation.setWorkspaceId(context.getWorkspaceId());
			relation.setFolderId(folderId);
			relation.setAppId(appId);
			relation.setGmtCreate(new Date());
			relation.setCreator(context.getAccountId());
			archiveAppMapper.insert(relation);
		}
		touchFolder(context.getWorkspaceId(), folderId, context.getAccountId());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void removeApp(String folderId, String appId) {
		RequestContext context = RequestContextHolder.getRequestContext();
		requireFolder(context.getWorkspaceId(), folderId);
		LambdaQueryWrapper<ProjectArchiveAppEntity> wrapper = new LambdaQueryWrapper<>();
		wrapper.eq(ProjectArchiveAppEntity::getWorkspaceId, context.getWorkspaceId())
			.eq(ProjectArchiveAppEntity::getFolderId, folderId)
			.eq(ProjectArchiveAppEntity::getAppId, appId);
		archiveAppMapper.delete(wrapper);
		touchFolder(context.getWorkspaceId(), folderId, context.getAccountId());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public String createAppInFolder(String folderId, Application application) {
		RequestContext context = RequestContextHolder.getRequestContext();
		requireFolder(context.getWorkspaceId(), folderId);
		String appId = appService.createApp(application);
		addApps(folderId, List.of(appId));
		return appId;
	}

	private String normalizeFolderName(String name) {
		if (StringUtils.isBlank(name)) {
			throw new BizException(ErrorCode.MISSING_PARAMS.toError("name"));
		}
		String normalized = name.trim();
		if (normalized.length() > MAX_FOLDER_NAME_LENGTH) {
			throw new BizException(ErrorCode.INVALID_PARAMS.toError("name", "folder name length must be <= 100"));
		}
		return normalized;
	}

	private void ensureFolderNameAvailable(String workspaceId, String name, String exceptFolderId) {
		LambdaQueryWrapper<ProjectArchiveFolderEntity> wrapper = new LambdaQueryWrapper<>();
		wrapper.eq(ProjectArchiveFolderEntity::getWorkspaceId, workspaceId)
			.eq(ProjectArchiveFolderEntity::getName, name);
		if (StringUtils.isNotBlank(exceptFolderId)) {
			wrapper.ne(ProjectArchiveFolderEntity::getFolderId, exceptFolderId);
		}
		if (folderMapper.selectCount(wrapper) > 0) {
			throw new BizException(ErrorCode.INVALID_PARAMS.toError("name", "folder name already exists"));
		}
	}

	private ProjectArchiveFolderEntity requireFolder(String workspaceId, String folderId) {
		if (StringUtils.isBlank(folderId)) {
			throw new BizException(ErrorCode.MISSING_PARAMS.toError("folderId"));
		}
		LambdaQueryWrapper<ProjectArchiveFolderEntity> wrapper = new LambdaQueryWrapper<>();
		wrapper.eq(ProjectArchiveFolderEntity::getWorkspaceId, workspaceId)
			.eq(ProjectArchiveFolderEntity::getFolderId, folderId);
		ProjectArchiveFolderEntity entity = folderMapper.selectOne(wrapper);
		if (entity == null) {
			throw new BizException(ErrorCode.INVALID_PARAMS.toError("folderId", "project archive folder not found"));
		}
		return entity;
	}

	private void requireApp(String workspaceId, String appId) {
		LambdaQueryWrapper<AppEntity> wrapper = new LambdaQueryWrapper<>();
		wrapper.eq(AppEntity::getWorkspaceId, workspaceId)
			.eq(AppEntity::getAppId, appId)
			.ne(AppEntity::getStatus, CommonStatus.DELETED.getStatus());
		if (appMapper.selectCount(wrapper) == 0) {
			throw new BizException(ErrorCode.APP_NOT_FOUND.toError());
		}
	}

	private List<String> listFolderAppIds(String workspaceId, String folderId) {
		LambdaQueryWrapper<ProjectArchiveAppEntity> wrapper = new LambdaQueryWrapper<>();
		wrapper.eq(ProjectArchiveAppEntity::getWorkspaceId, workspaceId)
			.eq(ProjectArchiveAppEntity::getFolderId, folderId)
			.orderByDesc(ProjectArchiveAppEntity::getId);
		return archiveAppMapper.selectList(wrapper).stream().map(ProjectArchiveAppEntity::getAppId).toList();
	}

	private long countValidApps(String workspaceId, String folderId) {
		List<String> appIds = listFolderAppIds(workspaceId, folderId);
		if (CollectionUtils.isEmpty(appIds)) {
			return 0L;
		}
		LambdaQueryWrapper<AppEntity> wrapper = new LambdaQueryWrapper<>();
		wrapper.eq(AppEntity::getWorkspaceId, workspaceId)
			.in(AppEntity::getAppId, appIds)
			.ne(AppEntity::getStatus, CommonStatus.DELETED.getStatus());
		return appMapper.selectCount(wrapper);
	}

	private void touchFolder(String workspaceId, String folderId, String modifier) {
		if (StringUtils.isBlank(folderId)) {
			return;
		}
		LambdaUpdateWrapper<ProjectArchiveFolderEntity> wrapper = new LambdaUpdateWrapper<>();
		wrapper.eq(ProjectArchiveFolderEntity::getWorkspaceId, workspaceId)
			.eq(ProjectArchiveFolderEntity::getFolderId, folderId)
			.set(ProjectArchiveFolderEntity::getGmtModified, new Date())
			.set(ProjectArchiveFolderEntity::getModifier, modifier);
		folderMapper.update(null, wrapper);
	}

}
