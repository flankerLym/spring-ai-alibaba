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

/** Converts supported Dify nodes to existing Studio editor schemas, without executing them. */
export function importWorkflowDsl(document: unknown, filename: string) {
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
  for (const node of source.nodes) {
    if (!object(node) || typeof node.id !== 'string' || !node.id || ids.has(node.id)) throw new Error('节点 ID 为空或重复');
    ids.add(node.id);
    original[node.id] = node;
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
    if (id === 'sys') return '${' + start.id + '.sys_' + parts.join('.') + '}';
    if (id !== 'conversation' && !ids.has(id)) throw new Error(`变量引用了不存在的节点：${id}`);
    if (original[id]?.data?.type === 'llm' && parts[0] === 'text') parts[0] = 'output';
    return '${' + [id, ...parts].join('.') + '}';
  };
  const template = (text: any) => asString(text).replace(/\{\{#([^#]+)#\}\}/g, (_, path) => ref(path.split('.')));
  const input = (key: string, selector: any, type = 'String') => ({ key, type, value_from: 'refer', value: ref(selector) });
  const model = (data: Obj, defaults: Obj) => ({
    ...defaults, model_id: '', model_name: data.model?.name || '',
    provider: data.model?.provider || '', mode: data.model?.mode || 'chat',
    params: Object.entries(data.model?.completion_params || {}).map(([key, value]) => ({
      key, value, enable: true, type: typeof value === 'number' ? 'Number' : 'String',
    })),
  });
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
            key: v.variable, type: valueType(v.type), desc: v.label || '', required: !!v.required,
          }));
          for (const [key, t] of [['sys_query', 'String'], ['sys_files', 'Array<File>'], ['sys_conversation_id', 'String'], ['sys_user_id', 'String'], ['sys_dialogue_count', 'Number']]) {
            if (!config.output_params.some((v: Obj) => v.key === key)) config.output_params.push({ key, type: t });
          }
          break;
        case 'llm': {
          if (data.prompt_template && !Array.isArray(data.prompt_template)) throw new Error('暂不支持此 LLM 提示词格式');
          const prompts = data.prompt_template || [];
          if (prompts.some((v: Obj) => !['system', 'user'].includes(v.role))) throw new Error('暂不支持包含 assistant/tool 消息的 LLM 导入');
          p.sys_prompt_content = prompts.filter((v: Obj) => v.role === 'system').map((v: Obj) => template(v.text)).join('\n\n');
          p.prompt_content = prompts.filter((v: Obj) => v.role === 'user').map((v: Obj) => template(v.text)).join('\n\n');
          p.model_config = model(data, p.model_config);
          if (data.memory) {
            p.short_memory.enabled = true;
            p.short_memory.round = data.memory.window?.size || 3;
            if (data.memory.query_prompt_template) p.prompt_content += '\n' + template(data.memory.query_prompt_template);
          }
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
          p.script_content = data.code || '';
          config.input_params = (data.variables || []).map((v: Obj) => input(v.variable, v.value_selector));
          config.output_params = Object.entries(data.outputs || {}).map(([key, v]: [string, any]) => ({ key, type: valueType(v.type) }));
          break;
        case 'if-else':
          p.branches = (data.cases || []).map((c: Obj) => ({
            id: c.case_id || c.id, label: c.case_id || c.id, logic: c.logical_operator || 'and',
            conditions: (c.conditions || []).map((v: Obj) => {
              const operator = operators[v.comparison_operator];
              if (!operator) throw new Error(`暂不支持条件运算符：${v.comparison_operator}`);
              return { operator, left: input('', v.variable_selector, valueType(v.varType || 'string')),
                right: { type: valueType(v.varType || 'string'), value_from: 'input', value: asString(v.value) } };
            }),
          }));
          p.branches.push({ id: 'default', label: 'ELSE' });
          break;
        case 'question-classifier':
          p.model_config = model(data, p.model_config);
          p.instruction = template(data.instructions || data.instruction || '');
          p.conditions = (data.classes || []).map((v: Obj) => ({ id: v.id, subject: v.name }));
          p.conditions.push({ id: 'default', subject: '其他' });
          config.input_params = [input('input', data.query_variable_selector)];
          break;
        case 'assigner':
        case 'variable-assigner':
          p.inputs = (data.items || []).map((v: Obj, i: number) => {
            if (!['over-write', 'set', 'clear'].includes(v.operation)) throw new Error(`暂不支持赋值操作：${v.operation}`);
            return { id: String(i), left: input('', v.variable_selector), right: {
              type: 'String', value_from: v.operation === 'clear' ? 'clear' : v.input_type === 'variable' ? 'refer' : 'input',
              value: v.operation === 'clear' ? '' : v.input_type === 'variable' ? ref(v.value) : asString(v.value),
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
  const config = { nodes, edges, global_config: {
    ...global,
    history_config: global.history_config || { history_switch: false, history_max_round: 5 },
    variable_config: global.variable_config || { conversation_params: (root.workflow?.conversation_variables || []).map((v: Obj) => ({
      key: v.name, type: valueType(v.value_type), desc: v.description || '', default_value: asString(v.value),
    })) },
  } } as IWorkFlowConfig;
  return { name: String(root.app?.name || root.name || filename.replace(/\.(ya?ml|json)$/i, '')).slice(0, 100), config, dify };
}
