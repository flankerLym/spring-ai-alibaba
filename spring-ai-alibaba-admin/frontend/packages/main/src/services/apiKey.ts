import { request } from '@/request';
import type {
  IApiKey,
  ICreateApiKeyParams,
  IUpdateApiKeyParams,
  IPagingList,
} from '@/types/apiKey';
import type { IApiResponse } from '@/types/common';

export async function createApiKey(
  params: ICreateApiKeyParams,
): Promise<IApiResponse<string>> {
  const response = await request({
    url: '/console/v1/api-keys',
    method: 'POST',
    data: params,
  });
  return response.data;
}

export async function updateApiKey(
  id: string | number,
  params: IUpdateApiKeyParams,
): Promise<IApiResponse<null>> {
  const response = await request({
    url: `/console/v1/api-keys/${id}`,
    method: 'PUT',
    data: params,
  });
  return response.data;
}

export async function deleteApiKey(
  id: string | number,
): Promise<IApiResponse<null>> {
  const response = await request({
    url: `/console/v1/api-keys/${id}`,
    method: 'DELETE',
  });
  return response.data;
}

export async function getApiKey(
  id: string | number,
): Promise<IApiResponse<IApiKey>> {
  const response = await request({
    url: `/console/v1/api-keys/${id}`,
    method: 'GET',
  });
  return response.data;
}

export async function listApiKeys(params?: {
  size?: number;
  current?: number;
}): Promise<IApiResponse<IPagingList<IApiKey>>> {
  const response = await request({
    url: '/console/v1/api-keys',
    method: 'GET',
    params,
  });
  return response.data;
}
