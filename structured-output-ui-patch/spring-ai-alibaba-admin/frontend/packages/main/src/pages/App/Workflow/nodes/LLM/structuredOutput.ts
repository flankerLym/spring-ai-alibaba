import { INodeDataOutputParamItem, IValueType } from '@spark-ai/flow';

export type JsonSchema = Record<string, any>;

const schemaTypeToValueType = (schema: JsonSchema): IValueType => {
  if (Array.isArray(schema?.enum)) return 'String';

  switch (schema?.type) {
    case 'object':
      return 'Object';
    case 'array': {
      const itemType = schema?.items?.type;
      if (itemType === 'object') return 'Array<Object>';
      if (itemType === 'number' || itemType === 'integer') return 'Array<Number>';
      if (itemType === 'boolean') return 'Array<Boolean>';
      return 'Array<String>';
    }
    case 'number':
    case 'integer':
      return 'Number';
    case 'boolean':
      return 'Boolean';
    default:
      return 'String';
  }
};

const schemaPropertyToOutputParam = (
  key: string,
  schema: JsonSchema,
  required: boolean,
): INodeDataOutputParamItem => {
  const type = schemaTypeToValueType(schema);
  const childSchema =
    type === 'Array<Object>' ? schema?.items || {} : schema || {};
  const requiredSet = new Set<string>(childSchema?.required || []);
  const properties = Object.entries(childSchema?.properties || {}).map(
    ([childKey, childValue]) =>
      schemaPropertyToOutputParam(
        childKey,
        childValue as JsonSchema,
        requiredSet.has(childKey),
      ),
  );

  return {
    key,
    type,
    desc: schema?.description || '',
    required,
    ...(properties.length ? { properties } : {}),
  };
};

export const structuredSchemaToOutputParam = (
  schema?: JsonSchema,
): INodeDataOutputParamItem => {
  const normalized = schema || {
    type: 'object',
    properties: {},
    required: [],
  };
  const requiredSet = new Set<string>(normalized.required || []);
  const properties = Object.entries(normalized.properties || {}).map(
    ([key, value]) =>
      schemaPropertyToOutputParam(
        key,
        value as JsonSchema,
        requiredSet.has(key),
      ),
  );

  return {
    key: 'structured_output',
    type: 'Object',
    desc: '结构化输出',
    properties,
  };
};

export const mergeStructuredOutputParam = (
  outputParams: INodeDataOutputParamItem[] = [],
  enabled?: boolean,
  schema?: JsonSchema,
): INodeDataOutputParamItem[] => {
  const baseParams = outputParams.filter(
    (item) => item.key !== 'structured_output',
  );
  if (!enabled) return baseParams;
  return [...baseParams, structuredSchemaToOutputParam(schema)];
};

export const DEFAULT_STRUCTURED_OUTPUT_SCHEMA: JsonSchema = {
  type: 'object',
  properties: {},
  required: [],
  additionalProperties: false,
};

export const getStructuredSchemaTypeLabel = (schema?: JsonSchema): string => {
  if (Array.isArray(schema?.enum)) return 'enum';
  if (schema?.type !== 'array') return schema?.type || 'string';
  const itemType = schema?.items?.type || 'string';
  return `array[${itemType}]`;
};
