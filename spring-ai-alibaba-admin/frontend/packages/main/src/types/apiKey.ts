// API Key response from the existing console API.
export interface IApiKey {
  id?: number;
  api_key?: string;
  companyName?: string;
  description?: string;
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
}

export interface IUpdateApiKeyParams {
  description: string;
  companyName?: string;
}

export interface IPagingList<T> {
  current: number;
  size: number;
  total: number;
  records: T[];
}
