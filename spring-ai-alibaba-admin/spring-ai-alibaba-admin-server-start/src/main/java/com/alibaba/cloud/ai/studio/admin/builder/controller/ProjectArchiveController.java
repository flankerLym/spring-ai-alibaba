/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.cloud.ai.studio.admin.builder.controller;

import com.alibaba.cloud.ai.studio.admin.builder.annotation.ApiModelAttribute;
import com.alibaba.cloud.ai.studio.core.base.service.ProjectArchiveService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import com.alibaba.cloud.ai.studio.runtime.domain.app.AppQuery;
import com.alibaba.cloud.ai.studio.runtime.domain.app.Application;
import com.alibaba.cloud.ai.studio.runtime.domain.app.ProjectArchiveFolder;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@RequiredArgsConstructor
@Tag(name = "project-archive")
@RequestMapping("/console/v1/app-folders")
public class ProjectArchiveController {

	private final ProjectArchiveService projectArchiveService;

	@GetMapping
	public Result<List<ProjectArchiveFolder>> listFolders() {
		RequestContext context = RequestContextHolder.getRequestContext();
		return Result.success(context.getRequestId(), projectArchiveService.listFolders());
	}

	@PostMapping
	public Result<ProjectArchiveFolder> createFolder(@RequestBody FolderRequest request) {
		RequestContext context = RequestContextHolder.getRequestContext();
		if (request == null || StringUtils.isBlank(request.getName())) {
			throw new BizException(ErrorCode.MISSING_PARAMS.toError("name"));
		}
		return Result.success(context.getRequestId(), projectArchiveService.createFolder(request.getName()));
	}

	@PutMapping("/{folderId}")
	public Result<ProjectArchiveFolder> renameFolder(@PathVariable("folderId") String folderId,
			@RequestBody FolderRequest request) {
		RequestContext context = RequestContextHolder.getRequestContext();
		if (request == null || StringUtils.isBlank(request.getName())) {
			throw new BizException(ErrorCode.MISSING_PARAMS.toError("name"));
		}
		return Result.success(context.getRequestId(), projectArchiveService.renameFolder(folderId, request.getName()));
	}

	@DeleteMapping("/{folderId}")
	public Result<Void> deleteFolder(@PathVariable("folderId") String folderId) {
		RequestContext context = RequestContextHolder.getRequestContext();
		projectArchiveService.deleteFolder(folderId);
		return Result.success(context.getRequestId(), null);
	}

	@GetMapping("/{folderId}/apps")
	public Result<PagingList<Application>> listFolderApps(@PathVariable("folderId") String folderId,
			@ApiModelAttribute AppQuery query) {
		RequestContext context = RequestContextHolder.getRequestContext();
		return Result.success(context.getRequestId(), projectArchiveService.listFolderApps(folderId, query));
	}

	@PostMapping("/{folderId}/apps")
	public Result<Void> addApps(@PathVariable("folderId") String folderId, @RequestBody AppIdsRequest request) {
		RequestContext context = RequestContextHolder.getRequestContext();
		if (request == null || request.getAppIds() == null) {
			throw new BizException(ErrorCode.MISSING_PARAMS.toError("app_ids"));
		}
		projectArchiveService.addApps(folderId, request.getAppIds());
		return Result.success(context.getRequestId(), null);
	}

	@DeleteMapping("/{folderId}/apps/{appId}")
	public Result<Void> removeApp(@PathVariable("folderId") String folderId, @PathVariable("appId") String appId) {
		RequestContext context = RequestContextHolder.getRequestContext();
		projectArchiveService.removeApp(folderId, appId);
		return Result.success(context.getRequestId(), null);
	}

	/**
	 * Used by the DSL import button inside a folder. The application creation and folder
	 * assignment participate in the same transaction.
	 */
	@PostMapping("/{folderId}/apps/create")
	public Result<String> createAppInFolder(@PathVariable("folderId") String folderId,
			@RequestBody Application app) {
		RequestContext context = RequestContextHolder.getRequestContext();
		if (Objects.isNull(app)) {
			throw new BizException(ErrorCode.MISSING_PARAMS.toError("app"));
		}
		if (StringUtils.isBlank(app.getName())) {
			throw new BizException(ErrorCode.MISSING_PARAMS.toError("name"));
		}
		if (Objects.isNull(app.getConfig())) {
			throw new BizException(ErrorCode.MISSING_PARAMS.toError("config"));
		}
		return Result.success(context.getRequestId(), projectArchiveService.createAppInFolder(folderId, app));
	}

	@Data
	public static class FolderRequest {
		private String name;
	}

	@Data
	public static class AppIdsRequest {
		private List<String> appIds;
	}

}
