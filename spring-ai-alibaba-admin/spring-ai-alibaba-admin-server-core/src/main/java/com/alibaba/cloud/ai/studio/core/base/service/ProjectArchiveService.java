/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.cloud.ai.studio.core.base.service;

import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.domain.app.AppQuery;
import com.alibaba.cloud.ai.studio.runtime.domain.app.Application;
import com.alibaba.cloud.ai.studio.runtime.domain.app.ProjectArchiveFolder;

import java.util.List;

/**
 * Project archive service. A single application belongs to at most one folder in a
 * workspace. Moving an existing archived application to another folder is supported.
 */
public interface ProjectArchiveService {

	List<ProjectArchiveFolder> listFolders();

	ProjectArchiveFolder createFolder(String name);

	ProjectArchiveFolder renameFolder(String folderId, String name);

	void deleteFolder(String folderId);

	PagingList<Application> listFolderApps(String folderId, AppQuery query);

	void addApps(String folderId, List<String> appIds);

	void removeApp(String folderId, String appId);

	String createAppInFolder(String folderId, Application application);

}
