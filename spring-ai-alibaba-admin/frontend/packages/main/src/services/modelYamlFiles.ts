import { request } from '@/request';
import { IApiResponse } from '@/types/common';

export interface YamlFileInfo {
  filename: string;
  models: string[];
  parameter_count: number;
  version: string;
  modified: number;
  error?: string;
}
export interface YamlFileContent { filename: string; content: string; version: string; }
const url = (provider: string) => `/console/v1/providers/${encodeURIComponent(provider)}/parameter-yamls`;
export async function listYamlFiles(provider: string): Promise<IApiResponse<YamlFileInfo[]>> {
  return (await request({ url: url(provider), method: 'GET' })).data;
}
export async function readYamlFile(provider: string, filename: string): Promise<IApiResponse<YamlFileContent>> {
  return (await request({ url: `${url(provider)}/content`, method: 'GET', params: { filename } })).data;
}
export async function saveYamlFile(provider: string, file: YamlFileContent, create: boolean): Promise<IApiResponse<YamlFileContent>> {
  return (await request({ url: url(provider), method: create ? 'POST' : 'PUT', data: file })).data;
}
export async function deleteYamlFile(provider: string, filename: string, version: string): Promise<IApiResponse<boolean>> {
  return (await request({ url: url(provider), method: 'DELETE', params: { filename, version } })).data;
}
