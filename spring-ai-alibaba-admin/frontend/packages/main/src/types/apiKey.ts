export type ApiKeyScopeType = 'ALL' | 'CUSTOM';
export type ApiKeyResourceType = 'FOLDER' | 'APP';

export interface IApiKeyResource {
  type: ApiKeyResourceType;
  id: string;
}

// API Key response from the console API.
export interface IApiKey {
  id?: number;
  api_key?: string;
  companyName?: string;
  description?: string;
  scopeType?: ApiKeyScopeType;
  resources?: IApiKeyResource[];
  account_id?: string;
  status?: 'normal' | 'deleted';
  gmt_create?: string;
  gmt_modified?: string;
  creator?: string;
  modifier?: string;
}

export interface ICreateApiKeyParams {
  description: string;
  companyName?: string;
  scopeType: ApiKeyScopeType;
  resources: IApiKeyResource[];
}

export interface IUpdateApiKeyParams {
  description: string;
  companyName?: string;
  scopeType: ApiKeyScopeType;
  resources: IApiKeyResource[];
}

export interface IPagingList<T> {
  current: number;
  size: number;
  total: number;
  records: T[];
}
