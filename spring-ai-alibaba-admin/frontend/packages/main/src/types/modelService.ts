import $i18n from '@/i18n';

export interface IProvider {
  name: string;
  provider: string;
  description?: string;
  icon?: string;
  supported_model_types?: string[];
  protocol?: string;
  credential_config?: Record<string, any>;
  enable?: boolean;
  model_count?: number;
  source?: 'preset' | 'custom';
  gmt_modified?: string;
}

export interface ICreateProviderParams {
  name: string;
  description?: string;
  icon?: string;
  supported_model_types?: string;
  protocol?: string;
  credential_config?: Record<string, any>;
}

export interface IListProvidersParams {
  name?: string;
}

export interface IProviderConfigInfo {
  name: string;
  provider: string;
  description?: string;
  icon?: string;
  supported_model_types?: string[];
  protocol?: string;
  enable?: boolean;
  credential?: Record<string, any>;
  source?: 'preset' | 'custom';
}

export interface IModel {
  model_id: string;
  name: string;
  provider: string;
  icon?: string;
  tags?: string[];
  type: string;
  mode?: string;
  enable?: boolean;
  source?: 'preset' | 'custom';
  endpoint_id?: string;
}

export interface IAvailableModel {
  model_id: string;
  tags: string[];
}

export interface IModelConfigInfo {
  model_id: string;
  tags?: string[];
  name?: string;
  provider: string;
  icon?: string;
  type: string;
  mode?: string;
  endpoint_id?: string;
}

export interface ICreateModelParams {
  model_id?: string;
  name: string;
  provider?: string;
  icon?: string;
  tags?: string[];
  type?: string;
  enable?: boolean;
  endpoint_id?: string;
}

export interface IUpdateModelParams {
  name: string;
  icon?: string;
  tags?: string[];
  enable?: boolean;
  endpoint_id?: string;
}

export type TValueType =
  | 'Array<Object>'
  | 'Array<File>'
  | 'Array<String>'
  | 'Array<Number>'
  | 'Array<Boolean>'
  | 'Object'
  | 'File'
  | 'String'
  | 'Number'
  | 'Boolean';

export interface IModelParameterRule {
  code: string;
  name: string;
  description?: string;
  type: TValueType;
  min?: number;
  max?: number;
  precision?: number;
  required?: boolean;
  help?: Record<string, string>;
  default_value?: any;
  options?: any[];
}

export interface IModelConfigParamItem {
  key: string;
  type: TValueType;
  value: any;
  enable: boolean;
}

export interface ISelectedModelParams {
  model_id: string;
  provider: string;
  params: Array<IModelConfigParamItem>;
}

export interface IModelSelectorItem {
  provider: IProviderConfigInfo;
  models: IModelConfigInfo[];
}

export const MODEL_TAGS = {
  vision: $i18n.get({ id: 'main.types.modelService.visual', dm: '视觉' }),
  web_search: $i18n.get({ id: 'main.types.modelService.connected', dm: '联网' }),
  embedding: $i18n.get({ id: 'main.types.modelService.embedded', dm: '嵌入' }),
  reasoning: $i18n.get({ id: 'main.types.modelService.inference', dm: '推理' }),
  function_call: $i18n.get({ id: 'main.types.modelService.tool', dm: '工具' }),
};
