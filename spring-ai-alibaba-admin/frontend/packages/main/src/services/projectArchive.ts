import { request } from '@/request';
import { IAppCard } from '@/types/appManage';
import { ICreateAppParams, IGetAppListParams } from './appManage';

export interface IProjectArchiveFolder {
  folder_id: string;
  name: string;
  app_count: number;
  gmt_create: string;
  gmt_modified: string;
}

export interface IProjectArchiveAppPage {
  current: number;
  size: number;
  total: number;
  records: IAppCard[];
}

export const getProjectArchiveFolders = () => {
  return request({
    url: '/console/v1/app-folders',
    method: 'GET',
  }).then((res) => res.data.data as IProjectArchiveFolder[]);
};

export const createProjectArchiveFolder = (name: string) => {
  return request({
    url: '/console/v1/app-folders',
    method: 'POST',
    data: { name },
  }).then((res) => res.data.data as IProjectArchiveFolder);
};

export const renameProjectArchiveFolder = (folderId: string, name: string) => {
  return request({
    url: `/console/v1/app-folders/${folderId}`,
    method: 'PUT',
    data: { name },
  }).then((res) => res.data.data as IProjectArchiveFolder);
};

export const deleteProjectArchiveFolder = (folderId: string) => {
  return request({
    url: `/console/v1/app-folders/${folderId}`,
    method: 'DELETE',
  });
};

export const getProjectArchiveFolderApps = (
  folderId: string,
  params: IGetAppListParams,
) => {
  return request({
    url: `/console/v1/app-folders/${folderId}/apps`,
    method: 'GET',
    params,
  }).then((res) => res.data.data as IProjectArchiveAppPage);
};

export const addAppsToProjectArchiveFolder = (
  folderId: string,
  appIds: string[],
) => {
  return request({
    url: `/console/v1/app-folders/${folderId}/apps`,
    method: 'POST',
    data: { app_ids: appIds },
  });
};

export const removeAppFromProjectArchiveFolder = (
  folderId: string,
  appId: string,
) => {
  return request({
    url: `/console/v1/app-folders/${folderId}/apps/${appId}`,
    method: 'DELETE',
  });
};

/**
 * Create an app and archive it into a folder in one backend transaction.
 * Used by DSL import inside Project Archive.
 */
export const createAppInProjectArchiveFolder = (
  folderId: string,
  params: ICreateAppParams,
) => {
  return request({
    url: `/console/v1/app-folders/${folderId}/apps/create`,
    method: 'POST',
    data: params,
  }).then((res) => res.data.data as string);
};
