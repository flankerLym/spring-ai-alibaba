import type { INodeDataOutputParamItem } from '@spark-ai/flow';

export function checkInputDefaults(params: INodeDataOutputParamItem[] = []) {
  const errors: { label: string; error: string }[] = [];
  for (const param of params) {
    if (param.required || param.default_value == null) continue;
    let value = param.default_value;
    try {
      if (param.type !== 'String' && typeof value === 'string') value = JSON.parse(value);
      const matches = (type: string | undefined, item: unknown): boolean => {
        switch (type) {
          case 'String': return typeof item === 'string';
          case 'Number': return typeof item === 'number' && Number.isFinite(item);
          case 'Boolean': return typeof item === 'boolean';
          case 'Object':
          case 'File': return item !== null && typeof item === 'object' && !Array.isArray(item);
          default: return true;
        }
      };
      const valid = param.type?.startsWith('Array<')
        ? Array.isArray(value) && value.every((item) => item === null || matches(param.type?.slice(6, -1), item))
        : matches(param.type, value);
      if (!valid) throw new Error();
    } catch {
      errors.push({ label: param.key, error: `默认值必须符合 ${param.type} 类型` });
    }
  }
  return errors;
}
