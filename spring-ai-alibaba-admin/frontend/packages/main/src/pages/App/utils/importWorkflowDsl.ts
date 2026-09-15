import type { IWorkFlowConfig } from '@/types/appManage';
import { NODE_SCHEMA_MAP } from '../Workflow/nodes/nodeSchemaMap';

type Obj = Record<string, any>;
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value));
const object = (value: any): value is Obj => !!value && typeof value === 'object' && !Array.isArray(value);
const types: Record<string, string> = {
  start: 'Start', end: 'End', llm: 'LLM', answer: 'Output', code: 'Script',
  'if-else': 'Judge', 'question-classifier': 'Classifier',
  assigner: 'VariableAssign', 'variable-assigner': 'VariableAssign',
  'template-transform': 'VariableHandle', 'http-request': 'API',
  'parameter-extractor': 'ParameterExtractor',
};
const operators: Record<string, string> = {
  is: 'equals', 'is not': 'notEquals', '=': 'equals', '≠': 'notEquals',
  contains: 'contains', 'not contains': 'notContains', empty: 'isNull',
  'not empty': 'isNotNull', '>': 'greater', '>=': 'greaterAndEqual',
  '<': 'less', '<=': 'lessAndEqual',
};
const valueType = (value: string = 'string'): any => {
  const original = String(value || 'string').trim();
  if (['String', 'Number', 'Boolean', 'Object', 'File',
    'Array<String>', 'Array<Number>', 'Array<Boolean>', 'Array<Object>', 'Array<File>'].includes(original)) {
    return original;
  }
  const normalized = original.toLowerCase().replace(/\s+/g, '');
  const map: Record<string, string> = {
    string: 'String', text: 'String', 'text-input': 'String', paragraph: 'String', select: 'String',
    integer: 'Number', number: 'Number', boolean: 'Boolean', object: 'Object', json: 'Object', file: 'File',
    array: 'Array<Object>', files: 'Array<File>', 'file-list': 'Array<File>',
    'array[string]': 'Array<String>', 'array[number]': 'Array<Number>', 'array[object]': 'Array<Object>',
    'array[boolean]': 'Array<Boolean>', 'array[file]': 'Array<File>',
    'array<string>': 'Array<String>', 'array<number>': 'Array<Number>', 'array<object>': 'Array<Object>',
    'array<boolean>': 'Array<Boolean>', 'array<file>': 'Array<File>',
  };
  if (!map[normalized]) throw new Error(`暂不支持变量类型：${value}`);
  return map[normalized];
};
const asString = (value: any) => value == null ? '' : typeof value === 'string' ? value : JSON.stringify(value);
const toKey = (value: any) => {
  const source = String(value || '');
  if (/^[a-zA-Z0-9_]+$/.test(source)) return source;
  const key = source.replace(/[^a-zA-Z0-9_]+/g, '_').replace(/^_+|_+$/g, '');
  if (!key) throw new Error(`变量名无法转换：${value}`);
  return key;
};
const replaceCodeKeys = (code: string, renames: Record<string, string>) => {
  let result = code;
  Object.entries(renames).filter(([from, to]) => from !== to).forEach(([from, to]) => {
    const escaped = from.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    result = result
      .replace(new RegExp(`(['\"])${escaped}\\1`, 'g'), (_, quote) => `${quote}${to}${quote}`)
      .replace(new RegExp(`\\b${escaped}\\b`, 'g'), to);
  });
  return result;
};

const schemaRawType = (schema: any): string => {
  if (!object(schema)) return 'object';
  const type = Array.isArray(schema.type)
    ? schema.type.find((item: string) => item !== 'null')
    : schema.type;
  return String(type || (object(schema.properties) ? 'object' : 'object')).toLowerCase();
};

const schemaType = (schema: any): any => {
  const rawType = schemaRawType(schema);
  const typeMap: Record<string, string> = {
    string: 'String', integer: 'Number', number: 'Number',
    boolean: 'Boolean', object: 'Object', file: 'File',
  };
  if (rawType !== 'array') return typeMap[rawType] || 'Object';
  const itemType = schemaType(object(schema?.items) ? schema.items : {});
  return itemType.startsWith('Array<') ? 'Array<Object>' : `Array<${itemType}>`;
};

const schemaAtPath = (schema: any, path: string[]): Obj | undefined => {
  let current: any = schema;
  for (const part of path) {
    if (!object(current)) return undefined;
    if (schemaRawType(current) === 'array') {
      current = object(current.items) ? current.items : undefined;
      if (!object(current)) return undefined;
      if (/^\d+$/.test(part)) continue;
    }
    if (!object(current.properties) || !object(current.properties[part])) return undefined;
    current = current.properties[part];
  }
  return object(current) ? current : undefined;
};

const schemaOutput = (key: string, schema: Obj): Obj => {
  const rawType = schemaRawType(schema);
  const itemSchema = rawType === 'array' && object(schema.items) ? schema.items : undefined;
  const objectSchema = rawType === 'object'
    ? schema
    : itemSchema && schemaRawType(itemSchema) === 'object' ? itemSchema : undefined;
  return {
    key,
    type: schemaType(schema),
    desc: schema?.description || '',
    properties: objectSchema
      ? Object.entries(objectSchema.properties || {}).map(([name, value]) => ({
        ...schemaOutput(name, value as Obj),
        required: (objectSchema.required || []).includes(name),
      }))
      : undefined,
  };
};

/** Converts supported Dify nodes to existing Studio editor schemas, without executing them. */
export function importWorkflowDsl(document: unknown, filename: string, availableModels: Obj[] = []) {
  if (!object(document)) throw new Error('DSL 顶层必须是对象');
  const root = document;
  const dify = object(root.workflow?.graph);
  const source = dify ? root.workflow.graph : (root.config ?? root);
  if (!object(source) || !Array.isArray(source.nodes) || !Array.isArray(source.edges) || !source.nodes.length) {
    throw new Error('未找到工作流 nodes / edges；请选择 Dify 工作流或 Studio 工作流配置');
  }
  if (source.nodes.length > 1000 || source.edges.length > 5000) throw new Error('工作流规模超过导入限制');
  const conversationVariables: Obj[] = dify && Array.isArray(root.workflow?.conversation_variables)
    ? root.workflow.conversation_variables : [];
  const environmentVariables: Obj[] = dify && Array.isArray(root.workflow?.environment_variables)
    ? root.workflow.environment_variables : [];
  const conversationVariableNames = new Set(conversationVariables.map((item: Obj) => String(item.name || '')));
  const environmentVariableNames = new Set(environmentVariables.map((item: Obj) => String(item.name || '')));
  for (const name of environmentVariableNames) {
    if (!name || conversationVariableNames.has(name)) throw new Error(`环境变量与会话变量重名：${name}`);
  }
  const ids = new Set<string>();
  const original: Record<string, Obj> = Object.create(null);
  const outputKeys: Record<string, Record<string, string>> = Object.create(null);
  const outputTypes: Record<string, Record<string, string>> = Object.create(null);
  const outputSchemas: Record<string, Obj> = Object.create(null);
  for (const node of source.nodes) {
    if (!object(node) || typeof node.id !== 'string' || !node.id || ids.has(node.id)) throw new Error('节点 ID 为空或重复');
    ids.add(node.id);
    original[node.id] = node;
    if (dify) {
      const data = node.data || {};
      const names = data.type === 'start' ? (data.variables || []).map((v: Obj) => v.variable)
        : data.type === 'code' ? Object.keys(data.outputs || {})
          : data.type === 'parameter-extractor' ? (data.parameters || []).map((v: Obj) => v.name) : [];
      const map: Record<string, string> = {};
      names.forEach((name: string) => {
        const converted = toKey(name);
        if (Object.values(map).includes(converted)) throw new Error(`节点 ${node.id} 的变量名转换后重复：${converted}`);
        map[name] = converted;
      });
      if (data.type === 'llm') {
        map.text = 'output';
        map.output = 'output';
        map.reasoning_content = 'reasoning_content';
        map.structured_output = 'structured_output';
      }
      if (data.type === 'question-classifier') {
        map.class_name = 'subject';
        map.subject = 'subject';
      }
      if (data.type === 'http-request') {
        map.body = 'output';
        map.output = 'output';
      }
      if (data.type === 'parameter-extractor') {
        map._is_completed = '_is_completed';
        map._reason = '_reason';
      }
      outputKeys[node.id] = map;
      const typeMap: Record<string, string> = Object.create(null);
      if (data.type === 'start') {
        (data.variables || []).forEach((item: Obj) => { typeMap[item.variable] = valueType(item.type); });
      }
      else if (data.type === 'code') {
        Object.entries(data.outputs || {}).forEach(([key, item]: [string, any]) => { typeMap[key] = valueType(item.type); });
      }
      else if (data.type === 'llm') {
        typeMap.text = 'String';
        typeMap.output = 'String';
        typeMap.reasoning_content = 'String';
        typeMap.structured_output = 'Object';
        const structuredSchema = data.structured_output?.schema || data.structured_output_schema;
        if (object(structuredSchema)) outputSchemas[node.id] = structuredSchema;
      }
      else if (data.type === 'question-classifier') {
        typeMap.class_name = 'String';
        typeMap.subject = 'String';
        typeMap.usage = 'Object';
        typeMap.thought = 'String';
      }
      else if (data.type === 'http-request') {
        typeMap.body = 'String';
        typeMap.output = 'String';
      }
      else if (data.type === 'parameter-extractor') {
        (data.parameters || []).forEach((item: Obj) => { typeMap[item.name] = valueType(item.type); });
        typeMap._is_completed = 'Boolean';
        typeMap._reason = 'String';
      }
      outputTypes[node.id] = typeMap;
    }
    const type = dify ? types[node.data?.type] : node.type;
    if (!type || !Object.prototype.hasOwnProperty.call(NODE_SCHEMA_MAP, type)) {
      throw new Error(`暂不支持节点：${node.data?.title || node.name || node.id} (${node.data?.type || node.type})`);
    }
    if (node.parentId || node.parent_id || node.data?.isInIteration || node.data?.isInLoop || NODE_SCHEMA_MAP[type].isGroup) {
      throw new Error('本次导入暂不支持嵌套/循环分组节点');
    }
  }
  const start = source.nodes.find((n: Obj) => (dify ? n.data?.type === 'start' : n.type === 'Start'));
  if (!start || source.nodes.filter((n: Obj) => dify ? n.data?.type === 'start' : n.type === 'Start').length !== 1) {
    throw new Error('工作流必须包含且仅包含一个开始节点');
  }
  const ref = (selector: any): string => {
    if (Array.isArray(selector) && selector.length === 1 && String(selector[0]) === 'context') {
      return '${sys.history_list}';
    }
    if (!Array.isArray(selector) || selector.length < 2) throw new Error('变量引用格式不正确');
    const [id, ...parts] = selector.map(String);
    if (id === 'sys') return '${sys.' + parts.join('.') + '}';
    if (id === 'env') {
      if (!environmentVariableNames.has(parts[0])) throw new Error(`引用了不存在的环境变量：${parts[0]}`);
      return '${conversation.' + parts.join('.') + '}';
    }
    if (id !== 'conversation' && !ids.has(id)) throw new Error(`变量引用了不存在的节点：${id}`);
    if (id === start.id && parts[0] === 'sys') return '${sys.' + parts.slice(1).join('.') + '}';
    if (id === start.id && parts[0]?.startsWith('sys.')) return '${' + parts.join('.') + '}';
    if (outputKeys[id]?.[parts[0]]) parts[0] = outputKeys[id][parts[0]];
    return '${' + [id, ...parts].join('.') + '}';
  };

  const selectorType = (selector: any): string => {
    if (Array.isArray(selector) && selector.length === 1 && String(selector[0]) === 'context') return 'Array<Object>';
    if (!Array.isArray(selector) || selector.length < 2) return 'String';
    const [id, key, ...nested] = selector.map(String);
    if (id === 'sys' || key === 'sys' || key.startsWith('sys.')) {
      const sysKey = id === 'sys' ? key : key === 'sys' ? nested[0] : key.slice(4);
      return ({
        files: 'Array<File>', dialogue_count: 'Number', history_list: 'Array<Object>',
        query: 'String', conversation_id: 'String', user_id: 'String',
      } as Obj)[sysKey] || 'String';
    }
    if (id === 'conversation') {
      const variable = conversationVariables.find((item: Obj) => item.name === key);
      return variable ? valueType(variable.value_type) : 'String';
    }
    if (id === 'env') {
      const variable = environmentVariables.find((item: Obj) => item.name === key);
      return variable ? valueType(variable.value_type) : 'String';
    }

    const mappedKey = outputKeys[id]?.[key] || key;
    if (mappedKey === 'structured_output' && outputSchemas[id]) {
      if (!nested.length) return 'Object';
      const nestedSchema = schemaAtPath(outputSchemas[id], nested);
      return nestedSchema ? schemaType(nestedSchema) : 'Object';
    }
    return outputTypes[id]?.[key] || outputTypes[id]?.[mappedKey] || 'String';
  };

  const template = (text: any) => asString(text).replace(/\{\{#([^#]+)#\}\}/g, (_, path) =>
    ref(String(path).trim().split('.').map((item) => item.trim())));

  const memoryTemplate = (text: any) => template(text).replace(
    /\{\{([a-zA-Z_][a-zA-Z0-9_]*)\}\}/g,
    (raw, name) => {
      if (conversationVariableNames.has(name)) return '${conversation.' + name + '}';
      const startKey = outputKeys[start.id]?.[name];
      if (startKey) return '${' + start.id + '.' + startKey + '}';
      return raw;
    },
  );

  const input = (key: string, selector: any, type = selectorType(selector)) => ({
    key, type, value_from: 'refer', value: ref(selector),
  });

  const textInput = (key: string, value: any, type = 'String'): Obj => {
    const rendered = template(value).trim();
    return { key, type, value_from: /^\$\{[^}]+\}$/.test(rendered) ? 'refer' : 'input', value: rendered };
  };

  const keyValueLines = (raw: any): Array<[string, string]> => {
    if (Array.isArray(raw)) {
      return raw.filter(object).map((item: Obj) => [String(item.key || ''), asString(item.value)]);
    }
    return asString(raw).split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line) => {
      const colon = line.indexOf(':');
      const equal = line.indexOf('=');
      const index = colon >= 0 ? colon : equal;
      return index < 0 ? [line, ''] : [line.slice(0, index).trim(), line.slice(index + 1).trim()];
    });
  };

  const httpBody = (body: any): Obj => {
    if (!object(body) || body.type === 'none') return { type: 'none', data: '' };
    const bodyType = String(body.type || 'raw').toLowerCase();
    if (bodyType === 'form-data') {
      return { type: 'form-data', data: keyValueLines(body.data).map(([key, value]) => textInput(key, value)) };
    }
    const values = Array.isArray(body.data)
      ? body.data.filter(object).map((item: Obj) => asString(item.value)).filter(Boolean)
      : [asString(body.data)];
    return { type: bodyType === 'json' ? 'json' : 'raw', data: template(values.join('\n')) };
  };

  const providerTexts = (item: Obj): string[] => {
    const values = [
      item.provider, item.provider_id, item.provider_name, item.provider_code, item.provider_label,
      item.provider?.id, item.provider?.name, item.provider?.code,
    ];
    return values
      .filter((value) => value != null && (typeof value === 'string' || typeof value === 'number'))
      .map((value) => String(value).toLowerCase());
  };

  const model = (data: Obj, defaults: Obj) => {
    const wanted = String(data.model?.name || '');
    const sourceProvider = String(data.model?.provider || '').toLowerCase();
    const sourceProviderParts = sourceProvider.split('/').filter(Boolean);
    const providerTail = sourceProviderParts[sourceProviderParts.length - 1] || sourceProvider;
    const candidates = availableModels.filter((item) =>
      [item.model_id, item.name].some((name) => String(name || '').toLowerCase() === wanted.toLowerCase()));
    const selected = candidates.find((item) => providerTexts(item).some((provider) =>
        provider === sourceProvider
        || provider === providerTail
        || sourceProviderParts.includes(provider)
        || provider.endsWith('/' + providerTail)))
      || candidates[0];
    if (!selected) throw new Error(`本地未配置 DSL 使用的模型：${wanted || '未知模型'}`);

    const vision = object(data.vision) ? data.vision : {};
    const visionEnabled = vision.enabled === true;
    const visionSelector = vision.configs?.variable_selector || vision.variable_selector;
    const defaultVision = clone(defaults.vision_config || { enable: false, params: [] });
    const visionParams = visionEnabled && Array.isArray(visionSelector) && visionSelector.length >= 2
      ? [input('imageContent', visionSelector, selectorType(visionSelector))]
      : clone(defaultVision.params || []);

    return {
      config: {
        ...defaults,
        model_id: selected.model_id,
        model_name: selected.name || wanted,
        provider: selected.provider,
        mode: data.model?.mode || 'chat',
        params: Object.entries(data.model?.completion_params || {}).map(([key, value]) => ({
          key, value, enable: true,
          type: typeof value === 'number' ? 'Number' : typeof value === 'boolean' ? 'Boolean' : 'String',
        })),
        vision_config: {
          ...defaultVision,
          enable: visionEnabled,
          params: visionParams,
        },
      },
      reasoning: Array.isArray(selected.tags) && selected.tags.includes('reasoning'),
    };
  };

  const normalizeDefaultValues = (raw: any, outputs: Obj[]): Obj[] | undefined => {
    if (raw == null) return undefined;
    const byKey = new Map(outputs.map((item: Obj) => [String(item.key), item]));
    const normalize = (key: string, value: any, declaredType?: any): Obj => {
      const base = byKey.get(key) || {};
      let type = base.type || 'String';
      if (declaredType) {
        try { type = valueType(String(declaredType)); } catch { type = base.type || 'String'; }
      }
      return { key, type, desc: base.desc || '', value };
    };
    if (Array.isArray(raw)) {
      return raw
        .filter((item) => object(item) && item.key != null)
        .map((item: Obj) => normalize(
          String(item.key),
          Object.prototype.hasOwnProperty.call(item, 'value') ? item.value : item.default_value,
          item.type,
        ));
    }
    if (object(raw)) {
      return Object.entries(raw).map(([key, value]) => {
        if (object(value) && (Object.prototype.hasOwnProperty.call(value, 'value')
          || Object.prototype.hasOwnProperty.call(value, 'default_value'))) {
          return normalize(
            key,
            Object.prototype.hasOwnProperty.call(value, 'value') ? value.value : value.default_value,
            value.type,
          );
        }
        return normalize(key, value);
      });
    }
    return undefined;
  };

  const applyExecutionPolicy = (data: Obj, p: Obj, outputs: Obj[]) => {
    if (object(data.retry_config) && object(p.retry_config)) {
      const maxRetries = Number(data.retry_config.max_retries);
      const retryInterval = Number(data.retry_config.retry_interval);
      p.retry_config = {
        ...p.retry_config,
        retry_enabled: data.retry_config.retry_enabled === true,
        max_retries: Number.isFinite(maxRetries) ? maxRetries : p.retry_config.max_retries,
        retry_interval: Number.isFinite(retryInterval) ? retryInterval : p.retry_config.retry_interval,
      };
    }

    const rawStrategy = data.error_strategy ?? data.errorStrategy;
    if (rawStrategy == null || !object(p.try_catch_config)) return;
    const strategyKey = String(rawStrategy).trim();
    const strategies: Record<string, string> = {
      noop: 'noop', none: 'noop',
      'default-value': 'defaultValue', default_value: 'defaultValue', defaultValue: 'defaultValue',
      'fail-branch': 'failBranch', fail_branch: 'failBranch', failBranch: 'failBranch',
    };
    const strategy = strategies[strategyKey];
    if (!strategy) throw new Error(`暂不支持异常处理策略：${rawStrategy}`);
    p.try_catch_config.strategy = strategy;
    if (strategy === 'defaultValue') {
      const values = normalizeDefaultValues(data.default_value ?? data.default_values, outputs);
      if (values) p.try_catch_config.default_values = values;
    }
  };

  const nodes = source.nodes.map((node: Obj) => {
    const data = node.data || {};
    const type = dify ? types[data.type] : node.type;
    const config = clone(NODE_SCHEMA_MAP[type].defaultParams) as Obj;
    if (!dify) {
      if (!object(node.config) || !object(node.config.node_param)) throw new Error(`节点 ${node.id} 缺少配置`);
      Object.assign(config, clone(node.config));
    } else {
      const p = config.node_param;
      // Retain source settings for inspection; imported drafts are not auto-published.
      p.imported_dify = clone(data);
      switch (data.type) {
        case 'start':
          config.output_params = (data.variables || []).map((v: Obj) => ({
            key: outputKeys[node.id][v.variable], type: valueType(v.type), desc: v.label || '', required: !!v.required,
            default_value: v.default,
          }));
          break;
        case 'llm': {
          if (data.context?.enabled === true) {
            throw new Error(`LLM 节点 ${node.id} 启用了 Dify Context，当前 Studio 无等价运行时配置，已阻止有损导入`);
          }
          if (data.prompt_template && !Array.isArray(data.prompt_template)) throw new Error('暂不支持此 LLM 提示词格式');
          const prompts = data.prompt_template || [];
          if (prompts.some((v: Obj) => !['system', 'user'].includes(v.role))) throw new Error('暂不支持包含 assistant/tool 消息的 LLM 导入');
          p.sys_prompt_content = prompts.filter((v: Obj) => v.role === 'system').map((v: Obj) => template(v.text)).join('\n\n');
          p.prompt_content = prompts.filter((v: Obj) => v.role === 'user').map((v: Obj) => template(v.text)).join('\n\n');

          const memoryEnabled = object(data.memory);
          if (memoryEnabled) {
            if (prompts.some((v: Obj) => v.role === 'user')) {
              throw new Error(`LLM 节点 ${node.id} 同时配置了 Dify Memory 和显式 user prompt，当前 Studio 无法保持原消息顺序，已阻止有损导入`);
            }
            p.prompt_content = data.memory?.query_prompt_template
              ? memoryTemplate(data.memory.query_prompt_template)
              : '${sys.query}';
          }

          const selectedModel = model(data, p.model_config);
          p.model_config = selectedModel.config;
          if (selectedModel.reasoning && !config.output_params.some((item: Obj) => item.key === 'reasoning_content')) {
            config.output_params.push({ key: 'reasoning_content', type: 'String', desc: '深度思考内容' });
          }

          const structuredSchema = data.structured_output?.schema || data.structured_output_schema;
          const explicitStructuredFlag = typeof data.structured_output_enabled === 'boolean'
            ? data.structured_output_enabled
            : typeof data.structured_output_switch_on === 'boolean'
              ? data.structured_output_switch_on
              : typeof data.structured_output?.enabled === 'boolean'
                ? data.structured_output.enabled
                : undefined;

          if (object(structuredSchema)) {
            p.structured_output_schema = clone(structuredSchema);
          }
          if (explicitStructuredFlag === true && !object(structuredSchema)) {
            throw new Error(`LLM 节点 ${node.id} 已开启结构化输出，但 DSL 中缺少有效 schema`);
          }

          p.structured_output_enabled = explicitStructuredFlag === undefined
            ? object(structuredSchema)
            : explicitStructuredFlag === true && object(structuredSchema);

          if (p.structured_output_enabled && object(structuredSchema)) {
            if (schemaRawType(structuredSchema) !== 'object') {
              throw new Error(`LLM 节点 ${node.id} 的结构化输出必须是 object`);
            }
            config.output_params = [
              ...config.output_params.filter((item: Obj) => item.key !== 'structured_output'),
              schemaOutput('structured_output', structuredSchema),
            ];
          }
          else {
            config.output_params = config.output_params.filter((item: Obj) => item.key !== 'structured_output');
          }

          if (data.memory) {
            p.short_memory.enabled = memoryEnabled;
            p.short_memory.type = 'custom';
            p.short_memory.round = Number(data.memory.window?.size) || 50;
            p.short_memory.param = {
              key: 'historyList', type: 'Array<Object>', value_from: 'refer', value: '${sys.history_list}',
            };
            p.short_memory.user_prefix = data.memory.role_prefix?.user || '';
            p.short_memory.assistant_prefix = data.memory.role_prefix?.assistant || '';
          }

          p.try_catch_config.default_values = clone(config.output_params);
          applyExecutionPolicy(data, p, config.output_params);
          break;
        }
        case 'answer': p.output = template(data.answer ?? data.content ?? data.text ?? ''); break;
        case 'end':
          p.output_type = 'json';
          p.json_params = (data.outputs || []).map((v: Obj) =>
            input(v.variable, v.value_selector, selectorType(v.value_selector)));
          break;
        case 'code':
          if (!['python3', 'javascript'].includes(data.code_language)) throw new Error(`不支持代码语言：${data.code_language}`);
          p.script_type = data.code_language === 'python3' ? 'python' : 'javascript';
          const inputRenames: Record<string, string> = {};
          (data.variables || []).forEach((v: Obj) => { inputRenames[v.variable] = toKey(v.variable); });
          const allRenames = { ...inputRenames, ...outputKeys[node.id] };
          if (new Set(Object.values(allRenames)).size !== Object.keys(allRenames).length) throw new Error(`节点 ${node.id} 的输入输出变量名转换后重复`);
          p.script_content = replaceCodeKeys(data.code || '', allRenames);
          config.input_params = (data.variables || []).map((v: Obj) =>
            input(inputRenames[v.variable], v.value_selector, selectorType(v.value_selector)));
          config.output_params = Object.entries(data.outputs || {}).map(([key, v]: [string, any]) => ({
            key: outputKeys[node.id][key], type: valueType(v.type), desc: v.description || v.desc || '',
          }));
          applyExecutionPolicy(data, p, config.output_params);
          break;
        case 'http-request': {
          const headerPairs = keyValueLines(data.headers);
          const authorizationIndex = headerPairs.findIndex(([key, value]) =>
            key.toLowerCase() === 'authorization' && /^bearer\s+/i.test(value));
          if (authorizationIndex >= 0) {
            const token = headerPairs[authorizationIndex][1].replace(/^bearer\s+/i, '');
            p.authorization = {
              auth_type: 'BearerAuth',
              auth_config: { key: 'token', value: template(token) },
            };
            headerPairs.splice(authorizationIndex, 1);
          }
          else {
            p.authorization = { auth_type: 'NoAuth' };
          }
          p.method = String(data.method || 'get').toLowerCase();
          p.url = template(data.url || '');
          p.headers = headerPairs.map(([key, value]) => textInput(key, value));
          p.params = keyValueLines(data.params).map(([key, value]) => textInput(key, value));
          p.body = httpBody(data.body);
          p.output_type = 'primitive';
          const readTimeout = Number(data.timeout?.max_read_timeout);
          p.timeout = { read: Number.isFinite(readTimeout) && readTimeout > 0 ? readTimeout : 3 };
          config.output_params = [{ key: 'output', type: 'String', desc: 'HTTP 响应体' }];
          p.try_catch_config.default_values = clone(config.output_params);
          const policy = clone(data);
          if (Array.isArray(policy.default_value)) {
            policy.default_value = policy.default_value
              .filter((item: Obj) => item?.key === 'body')
              .map((item: Obj) => ({ ...item, key: 'output' }));
          }
          applyExecutionPolicy(policy, p, config.output_params);
          break;
        }
        case 'if-else':
          p.branches = (data.cases || []).map((c: Obj) => ({
            id: c.case_id || c.id, label: c.case_id || c.id, logic: c.logical_operator || 'and',
            conditions: (c.conditions || []).map((v: Obj) => {
              const operator = operators[v.comparison_operator];
              if (!operator) throw new Error(`暂不支持条件运算符：${v.comparison_operator}`);
              // Dify may describe a nested structured-output selector as `object`
              // even when the selected JSON Schema property is a scalar. Infer the
              // actual leaf type from the selector/schema so comparisons such as
              // structured_output.safety_risk2 == "危机" remain string comparisons.
              const conditionType = selectorType(v.variable_selector);
              return { operator, left: input('', v.variable_selector, conditionType),
                right: { type: conditionType, value_from: 'input', value: v.value } };
            }),
          }));
          p.branches.push({ id: 'default', label: 'ELSE' });
        {
          const connectedBranches = new Set(source.edges
            .filter((edge: Obj) => edge.source === node.id)
            .map((edge: Obj) => {
              const handle = edge.sourceHandle || edge.source_handle;
              return handle === 'false' ? 'default' : handle;
            }));
          // In Dify, a branch without an outgoing edge terminates successfully.
          // Keep the same graph and record that runtime semantic explicitly.
          p.terminal_branch_ids = p.branches
            .map((branch: Obj) => branch.id)
            .filter((id: string) => !connectedBranches.has(id));
        }
          break;
        case 'question-classifier':
          p.model_config = model(data, p.model_config).config;
          p.instruction = template(data.instructions || data.instruction || '');
          p.conditions = (data.classes || []).map((v: Obj) => ({ id: v.id, subject: v.name }));
          p.conditions.push({ id: 'default', subject: '其他' });
          config.input_params = [input('input', data.query_variable_selector)];
          break;
        case 'parameter-extractor': {
          p.model_config = model(data, p.model_config).config;
          p.instruction = template(data.instruction || '');
          p.extract_params = (data.parameters || []).map((v: Obj) => ({
            key: outputKeys[node.id][v.name],
            type: valueType(v.type),
            required: v.required === true,
            desc: v.description || v.desc || '',
          }));
          config.input_params = [input('input', data.query, selectorType(data.query))];
          config.output_params = [
            ...p.extract_params.map((v: Obj) => ({ key: v.key, type: v.type, desc: v.desc })),
            { key: '_is_completed', type: 'Boolean', desc: '是否完整解析' },
            { key: '_reason', type: 'String', desc: '未成功解析的原因' },
          ];
          break;
        }
        case 'assigner':
        case 'variable-assigner':
          p.inputs = (data.items || []).map((v: Obj, i: number) => {
            if (!['over-write', 'set', 'clear'].includes(v.operation)) throw new Error(`暂不支持赋值操作：${v.operation}`);
            const leftType = selectorType(v.variable_selector);
            return { id: String(i), left: input('', v.variable_selector, leftType), right: {
                type: v.input_type === 'variable' ? selectorType(v.value) : leftType,
                value_from: v.operation === 'clear' ? 'clear' : v.input_type === 'variable' ? 'refer' : 'input',
                value: v.operation === 'clear' ? '' : v.input_type === 'variable' ? ref(v.value) : v.value,
              } };
          });
          break;
        case 'template-transform':
          throw new Error('Dify Jinja 模板与当前画布模板语法不同，暂不支持无损导入');
      }
    }
    if (!Array.isArray(config.input_params) || !Array.isArray(config.output_params)) throw new Error(`节点 ${node.id} 参数列表无效`);
    return {
      id: node.id, type, name: dify ? data.title || node.id : node.name || node.id,
      desc: dify ? data.desc || '' : node.desc || '',
      position: { x: Number.isFinite(node.position?.x) ? node.position.x : 0, y: Number.isFinite(node.position?.y) ? node.position.y : 0 },
      width: Number.isFinite(node.width) ? node.width : 320, config,
    };
  });
  const edgeIds = new Set<string>();
  const edges = source.edges.map((e: Obj, i: number) => {
    if (!object(e) || !ids.has(e.source) || !ids.has(e.target)) throw new Error('连线引用了不存在的节点');
    const id = e.id || `import_edge_${i}`;
    if (edgeIds.has(id)) throw new Error(`连线 ID 重复：${id}`);
    edgeIds.add(id);
    let sourceHandle = e.source_handle || e.sourceHandle || e.source;
    if (dify) {
      const sourceNode = nodes.find((n: Obj) => n.id === e.source)!;
      if (['Judge', 'Classifier'].includes(sourceNode.type)) {
        const branch = sourceHandle === 'false' ? 'default' : sourceHandle;
        const branches = sourceNode.config.node_param.branches || sourceNode.config.node_param.conditions;
        if (!branches.some((b: Obj) => b.id === branch)) throw new Error(`连线分支不存在：${sourceHandle}`);
        sourceHandle = `${e.source}_${branch}`;
      }
      else if (['fail-branch', 'fail_branch', 'fail'].includes(String(sourceHandle))) {
        sourceHandle = `${e.source}_fail`;
      }
      else {
        sourceHandle = e.source;
      }
    }
    return { id, source: e.source, target: e.target, source_handle: sourceHandle,
      target_handle: dify ? e.target : e.target_handle || e.targetHandle || e.target };
  });
  const global = dify ? {} : clone(source.global_config || {});
  const memoryWindows = dify ? source.nodes
    .filter((node: Obj) => node.data?.type === 'llm' && object(node.data?.memory))
    .map((node: Obj) => Number(node.data.memory?.window?.size) || 50) : [];
  const config = { nodes, edges, global_config: {
      ...global,
      history_config: global.history_config || {
        history_switch: memoryWindows.length > 0,
        history_max_round: memoryWindows.length ? Math.max(...memoryWindows) : 5,
      },
      variable_config: global.variable_config || { conversation_params: [
          ...conversationVariables.map((v: Obj) => ({
            key: v.name, type: valueType(v.value_type), desc: v.description || '', default_value: v.value,
          })),
          ...environmentVariables.map((v: Obj) => ({
            key: v.name, type: valueType(v.value_type),
            desc: `[由 Dify 环境变量导入] ${v.description || ''}`.trim(), default_value: v.value,
          })),
        ] },
    } } as IWorkFlowConfig;
  return { name: String(root.app?.name || root.name || filename.replace(/\.(ya?ml|json)$/i, '')).slice(0, 100), config, dify };
}
