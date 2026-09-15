import type { IWorkFlowConfig } from '@/types/appManage';
import { NODE_SCHEMA_MAP } from '../Workflow/nodes/nodeSchemaMap';

type Obj = Record<string, any>;
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value));
const object = (value: any): value is Obj => !!value && typeof value === 'object' && !Array.isArray(value);
const types: Record<string, string> = {
  start: 'Start', end: 'End', llm: 'LLM', answer: 'Output', code: 'Script',
  'if-else': 'Judge', 'question-classifier': 'Classifier',
  assigner: 'VariableAssign', 'variable-assigner': 'VariableAssign',
  'template-transform': 'VariableHandle',
};
const operators: Record<string, string> = {
  is: 'equals', 'is not': 'notEquals', '=': 'equals', '≠': 'notEquals',
  contains: 'contains', 'not contains': 'notContains', empty: 'isNull',
  'not empty': 'isNotNull', '>': 'greater', '>=': 'greaterAndEqual',
  '<': 'less', '<=': 'lessAndEqual',
};
const valueType = (value: string = 'string'): any => {
  const map: Record<string, string> = {
    string: 'String', text: 'String', 'text-input': 'String', paragraph: 'String', select: 'String',
    number: 'Number', boolean: 'Boolean', object: 'Object', file: 'File',
    'file-list': 'Array<File>', 'array[string]': 'Array<String>', 'array[number]': 'Array<Number>',
    'array[object]': 'Array<Object>', 'array[boolean]': 'Array<Boolean>', 'array[file]': 'Array<File>',
  };
  if (!map[value]) throw new Error(`暂不支持变量类型：${value}`);
  return map[value];
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

const schemaOutput = (key: string, schema: Obj): Obj => {
  const rawType = Array.isArray(schema?.type)
    ? schema.type.find((item: string) => item !== 'null')
    : schema?.type;
  const typeMap: Record<string, string> = {
    string: 'String', integer: 'Number', number: 'Number',
    boolean: 'Boolean', object: 'Object', file: 'File',
  };
  if (rawType === 'array') {
    const itemSchema = object(schema.items) ? schema.items : {};
    const itemType = Array.isArray(itemSchema.type)
      ? itemSchema.type.find((item: string) => item !== 'null')
      : itemSchema.type;
    return {
      key,
      type: `Array<${typeMap[itemType] || 'Object'}>`,
      desc: schema?.description || '',
      properties: itemType === 'object'
        ? Object.entries(itemSchema.properties || {}).map(([name, value]) => ({
          ...schemaOutput(name, value as Obj),
          required: (itemSchema.required || []).includes(name),
        }))
        : undefined,
    };
  }
  return {
    key,
    type: typeMap[rawType] || 'Object',
    desc: schema?.description || '',
    properties: rawType === 'object'
      ? Object.entries(schema.properties || {}).map(([name, value]) => ({
        ...schemaOutput(name, value as Obj),
        required: (schema.required || []).includes(name),
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
  const ids = new Set<string>();
  const original: Record<string, Obj> = Object.create(null);
  const outputKeys: Record<string, Record<string, string>> = Object.create(null);
  const outputTypes: Record<string, Record<string, string>> = Object.create(null);
  for (const node of source.nodes) {
    if (!object(node) || typeof node.id !== 'string' || !node.id || ids.has(node.id)) throw new Error('节点 ID 为空或重复');
    ids.add(node.id);
    original[node.id] = node;
    if (dify) {
      const data = node.data || {};
      const names = data.type === 'start' ? (data.variables || []).map((v: Obj) => v.variable)
        : data.type === 'code' ? Object.keys(data.outputs || {}) : [];
      const map: Record<string, string> = {};
      names.forEach((name: string) => {
        const converted = toKey(name);
        if (Object.values(map).includes(converted)) throw new Error(`节点 ${node.id} 的变量名转换后重复：${converted}`);
        map[name] = converted;
      });
      if (data.type === 'llm') map.text = 'output';
      if (data.type === 'question-classifier') map.class_name = 'subject';
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
        typeMap.reasoning_content = 'String';
        typeMap.structured_output = 'Object';
      }
      else if (data.type === 'question-classifier') {
        typeMap.class_name = 'String';
        typeMap.usage = 'Object';
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
    if (!Array.isArray(selector) || selector.length < 2) throw new Error('变量引用格式不正确');
    const [id, ...parts] = selector.map(String);
    if (id === 'sys') return '${sys.' + parts.join('.') + '}';
    if (id !== 'conversation' && !ids.has(id)) throw new Error(`变量引用了不存在的节点：${id}`);
    if (id === start.id && parts[0] === 'sys') return '${sys.' + parts.slice(1).join('.') + '}';
    if (id === start.id && parts[0]?.startsWith('sys.')) return '${' + parts.join('.') + '}';
    if (outputKeys[id]?.[parts[0]]) parts[0] = outputKeys[id][parts[0]];
    return '${' + [id, ...parts].join('.') + '}';
  };
  const selectorType = (selector: any): string => {
    if (!Array.isArray(selector) || selector.length < 2) return 'String';
    const [id, key, nestedKey] = selector.map(String);
    if (id === 'sys' || key === 'sys' || key.startsWith('sys.')) {
      const sysKey = id === 'sys' ? key : key === 'sys' ? nestedKey : key.slice(4);
      return ({ files: 'Array<File>', dialogue_count: 'Number', history_list: 'Array<Object>' } as Obj)[sysKey] || 'String';
    }
    if (id === 'conversation') {
      const variable = (root.workflow?.conversation_variables || []).find((item: Obj) => item.name === key);
      return variable ? valueType(variable.value_type) : 'String';
    }
    return outputTypes[id]?.[key] || 'String';
  };
  const template = (text: any) => asString(text).replace(/\{\{#([^#]+)#\}\}/g, (_, path) => ref(path.split('.')));
  const input = (key: string, selector: any, type = selectorType(selector)) => ({ key, type, value_from: 'refer', value: ref(selector) });
  const model = (data: Obj, defaults: Obj) => {
    const wanted = String(data.model?.name || '');
    const wantedProvider = String(data.model?.provider || '').split('/').pop();
    const candidates = availableModels.filter((item) =>
      [item.model_id, item.name].some((name) => String(name || '').toLowerCase() === wanted.toLowerCase()));
    const selected = candidates.find((item) => item.provider === wantedProvider) || candidates[0];
    if (!selected) throw new Error(`本地未配置 DSL 使用的模型：${wanted || '未知模型'}`);
    return {
      config: {
        ...defaults, model_id: selected.model_id, model_name: selected.name || wanted,
        provider: selected.provider, mode: data.model?.mode || 'chat',
        params: Object.entries(data.model?.completion_params || {}).map(([key, value]) => ({
          key, value, enable: true, type: typeof value === 'number' ? 'Number' : typeof value === 'boolean' ? 'Boolean' : 'String',
        })),
      },
      reasoning: Array.isArray(selected.tags) && selected.tags.includes('reasoning'),
    };
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
          if (data.prompt_template && !Array.isArray(data.prompt_template)) throw new Error('暂不支持此 LLM 提示词格式');
          const prompts = data.prompt_template || [];
          if (prompts.some((v: Obj) => !['system', 'user'].includes(v.role))) throw new Error('暂不支持包含 assistant/tool 消息的 LLM 导入');
          p.sys_prompt_content = prompts.filter((v: Obj) => v.role === 'system').map((v: Obj) => template(v.text)).join('\n\n');
          p.prompt_content = prompts.filter((v: Obj) => v.role === 'user').map((v: Obj) => template(v.text)).join('\n\n');
          const memoryEnabled = data.memory?.window?.enabled === true;
          if (!p.prompt_content && memoryEnabled) p.prompt_content = data.memory?.query_prompt_template
            ? template(data.memory.query_prompt_template) : '${sys.query}';
          const selectedModel = model(data, p.model_config);
          p.model_config = selectedModel.config;
          if (selectedModel.reasoning && !config.output_params.some((item: Obj) => item.key === 'reasoning_content')) {
            config.output_params.push({ key: 'reasoning_content', type: 'String', desc: '深度思考内容' });
          }
          const structuredSchema = data.structured_output?.schema;
          p.structured_output_enabled = data.structured_output_enabled === true && object(structuredSchema);
          if (p.structured_output_enabled) {
            if (structuredSchema.type !== 'object') {
              throw new Error(`LLM 节点 ${node.id} 的结构化输出必须是 object`);
            }
            p.structured_output_schema = clone(structuredSchema);
            config.output_params = [
              ...config.output_params.filter((item: Obj) => item.key !== 'structured_output'),
              schemaOutput('structured_output', structuredSchema),
            ];
          }
          if (data.memory) {
            p.short_memory.enabled = memoryEnabled;
            p.short_memory.type = 'custom';
            p.short_memory.round = data.memory.window?.size || 3;
            p.short_memory.param = { key: 'historyList', type: 'Array<Object>', value_from: 'refer', value: '${sys.history_list}' };
            p.short_memory.user_prefix = data.memory.role_prefix?.user || '';
            p.short_memory.assistant_prefix = data.memory.role_prefix?.assistant || '';
          }
          p.try_catch_config.default_values = clone(config.output_params);
          break;
        }
        case 'answer': p.output = template(data.answer); break;
        case 'end':
          p.output_type = 'json';
          p.json_params = (data.outputs || []).map((v: Obj) => input(v.variable, v.value_selector, valueType(v.value_type || 'string')));
          break;
        case 'code':
          if (!['python3', 'javascript'].includes(data.code_language)) throw new Error(`不支持代码语言：${data.code_language}`);
          p.script_type = data.code_language === 'python3' ? 'python' : 'javascript';
          const inputRenames: Record<string, string> = {};
          (data.variables || []).forEach((v: Obj) => { inputRenames[v.variable] = toKey(v.variable); });
          const allRenames = { ...inputRenames, ...outputKeys[node.id] };
          if (new Set(Object.values(allRenames)).size !== Object.keys(allRenames).length) throw new Error(`节点 ${node.id} 的输入输出变量名转换后重复`);
          p.script_content = replaceCodeKeys(data.code || '', allRenames);
          config.input_params = (data.variables || []).map((v: Obj) => input(inputRenames[v.variable], v.value_selector, valueType(v.value_type || 'string')));
          config.output_params = Object.entries(data.outputs || {}).map(([key, v]: [string, any]) => ({ key: outputKeys[node.id][key], type: valueType(v.type) }));
          break;
        case 'if-else':
          p.branches = (data.cases || []).map((c: Obj) => ({
            id: c.case_id || c.id, label: c.case_id || c.id, logic: c.logical_operator || 'and',
            conditions: (c.conditions || []).map((v: Obj) => {
              const operator = operators[v.comparison_operator];
              if (!operator) throw new Error(`暂不支持条件运算符：${v.comparison_operator}`);
              return { operator, left: input('', v.variable_selector, valueType(v.varType || 'string')),
                right: { type: valueType(v.varType || 'string'), value_from: 'input', value: v.value } };
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
      } else sourceHandle = e.source;
    }
    return { id, source: e.source, target: e.target, source_handle: sourceHandle,
      target_handle: dify ? e.target : e.target_handle || e.targetHandle || e.target };
  });
  const global = dify ? {} : clone(source.global_config || {});
  const memoryWindows = dify ? source.nodes
    .filter((node: Obj) => node.data?.type === 'llm' && node.data?.memory?.window?.enabled === true)
    .map((node: Obj) => Number(node.data.memory.window.size) || 3) : [];
  const config = { nodes, edges, global_config: {
    ...global,
    history_config: global.history_config || {
      history_switch: memoryWindows.length > 0,
      history_max_round: memoryWindows.length ? Math.max(...memoryWindows) : 5,
    },
    variable_config: global.variable_config || { conversation_params: (root.workflow?.conversation_variables || []).map((v: Obj) => ({
      key: v.name, type: valueType(v.value_type), desc: v.description || '', default_value: v.value,
    })) },
  } } as IWorkFlowConfig;
  return { name: String(root.app?.name || root.name || filename.replace(/\.(ya?ml|json)$/i, '')).slice(0, 100), config, dify };
}
