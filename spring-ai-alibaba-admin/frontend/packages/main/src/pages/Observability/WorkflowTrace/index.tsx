import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ApartmentOutlined, CopyOutlined, DownOutlined, EyeOutlined, ReloadOutlined,
  RobotOutlined, SearchOutlined, UpOutlined,
} from '@ant-design/icons';
import {
  Button, Card, Col, DatePicker, Descriptions, Drawer, Empty, Form, Input,
  message, Row, Select, Space, Spin, Statistic, Table, Tag, Tooltip, Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import {
  getWorkflowTraceDetail, getWorkflowTraceOverview, getWorkflowTraces,
  type TraceDetail, type TraceListParams, type TraceTime, type WorkflowSpan,
  type WorkflowTrace, type WorkflowTraceOverview,
} from '@/services/workflowTrace';
import styles from './index.module.less';

const { RangePicker } = DatePicker;
const { Text, Title } = Typography;

type FilterForm = {
  appId?: string;
  keyword?: string;
  userId?: string;
  status?: string;
  invokeSource?: string;
  timeRange?: [dayjs.Dayjs, dayjs.Dayjs];
};
type SpanNode = WorkflowSpan & { children: SpanNode[]; depth: number };
type PayloadDirection = 'input' | 'output';
type SessionVariableChange = {
  key: string;
  before: unknown;
  after: unknown;
  beforeExists: boolean;
  afterExists: boolean;
  change: '新增' | '修改' | '删除' | '未变化';
};

const STATUS: Record<string, [string, string]> = {
  success: ['success', '成功'],
  fail: ['error', '失败'],
  executing: ['processing', '执行中'],
  stop: ['default', '停止'],
  pause: ['warning', '暂停'],
};
const KIND: Record<string, [string, string]> = {
  NODE: ['blue', 'NODE'],
  MODEL_CALL: ['purple', 'MODEL'],
  MODEL_PHASE: ['gold', 'MODEL PHASE'],
  SUB_WORKFLOW: ['cyan', 'SUB FLOW'],
};

const num = (v?: number) => new Intl.NumberFormat('zh-CN').format(v || 0);
const duration = (v?: number) =>
  v === null || v === undefined ? '-' : v < 1000 ? `${v} ms` : `${(v / 1000).toFixed(2)} s`;

const isModelSpan = (kind?: string) => kind === 'MODEL_CALL' || kind === 'MODEL_PHASE';

const parseTraceTime = (v?: TraceTime | null) => {
  if (v === undefined || v === null || v === '') return null;
  if (typeof v === 'number') {
    return dayjs(v < 1e12 ? v * 1000 : v);
  }

  const value = v.trim();
  if (/^\d+(?:\.\d+)?$/.test(value)) {
    const timestamp = Number(value);
    return dayjs(timestamp < 1e12 ? timestamp * 1000 : timestamp);
  }

  const parsed = dayjs(value);
  return parsed.isValid() ? parsed : null;
};

const time = (v?: TraceTime | null) => {
  const parsed = parseTraceTime(v);
  return parsed ? parsed.format('YYYY-MM-DD HH:mm:ss.SSS') : '-';
};

const json = (v?: string) => {
  if (!v) return '-';
  try { return JSON.stringify(JSON.parse(v), null, 2); } catch { return v; }
};

const NOISE_KEY = /^(media|metadata|headers|requestheaders|responseheaders|rawrequest|rawresponse|stacktrace|traceid|spanid|parentspanid)$/i;
const INPUT_KEYS = /^(sys|query|input|inputs|prompt|messages?|history_list|history|context|question|text|content|variables?|params?|arguments?|condition|expression|value|source|user_input|userinput|input_data|inputdata|request|data|provider|modelid|modelname|model)$/i;
const OUTPUT_KEYS = /^(sys|output|outputs|result|results|answer|response|content|text|message|messages|value|data|variables?|assigned|selected|selectedbranch|selected_branch|branch|case|label|intent|classification|class|route|nextnode|next_node|success|error|status|taskresult|task_result|query|provider|modelid|modelname|model)$/i;
const CLASSIFIER_INPUT_KEYS = /^(sys|query|input|text|question|context|variables?|condition|expression|value|intent|class|label|data)$/i;
const CLASSIFIER_OUTPUT_KEYS = /^(sys|branch|case|selected|selectedbranch|selected_branch|label|intent|class|classification|route|nextnode|next_node|result|output|value|variables?|status|error|data)$/i;
const ASSIGN_INPUT_KEYS = /^(sys|input|inputs|variables?|value|expression|source|context|query|data|params?)$/i;
const ASSIGN_OUTPUT_KEYS = /^(sys|variables?|value|output|outputs|result|data|assigned|status|error)$/i;
const LLM_INPUT_KEYS = /^(sys|query|input|prompt|messages?|history_list|history|context|variables?|user_input|userinput|request|data|provider|modelid|modelname|model)$/i;
const LLM_OUTPUT_KEYS = /^(sys|answer|response|content|text|message|messages|result|output|outputs|data|status|error)$/i;
const START_INPUT_KEYS = /^(sys|query|input|inputs|messages?|history_list|history|text|content|variables?|data|request)$/i;
const START_OUTPUT_KEYS = /^(sys|query|input|variables?|output|result|data|context|status|error)$/i;
const END_INPUT_KEYS = /^(sys|query|input|inputs|variables?|context|output|result|answer|content|text|data)$/i;
const END_OUTPUT_KEYS = /^(sys|output|outputs|result|answer|response|content|text|data|taskresult|task_result|status|error)$/i;

function compactValue(value: unknown, depth = 0, parentKey = ''): unknown {
  if (value === null || typeof value === 'number' || typeof value === 'boolean') return value;
  if (typeof value === 'string') {
    return value.length > 1800
      ? `${value.slice(0, 1800)}…（内容过长，已截断；可切换查看原始 JSON）`
      : value;
  }
  if (Array.isArray(value)) {
    const isHistory = /history|messages/i.test(parentKey);
    const visible = isHistory ? value.slice(-3) : value.slice(0, 6);
    const result = visible.map(item => compactValue(item, depth + 1, parentKey));
    if (value.length > visible.length) {
      result.unshift(isHistory
        ? `…前 ${value.length - visible.length} 项历史消息已省略`
        : `…其余 ${value.length - visible.length} 项已省略`);
    }
    return result;
  }
  if (typeof value === 'object') {
    if (depth >= 4) return '[对象已折叠]';
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([key]) => !NOISE_KEY.test(key));
    const result: Record<string, unknown> = {};
    entries.slice(0, 16).forEach(([key, item]) => {
      result[key] = compactValue(item, depth + 1, key);
    });
    if (entries.length > 16) result['…'] = `其余 ${entries.length - 16} 个字段已省略`;
    return result;
  }
  return String(value);
}

function compactPayload(raw: string | undefined, span: WorkflowSpan, direction: PayloadDirection) {
  if (!raw) return '-';
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
    if (typeof parsed === 'string') {
      try { parsed = JSON.parse(parsed); } catch { /* Keep a plain string payload. */ }
    }
  } catch {
    return raw.length > 1800 ? `${raw.slice(0, 1800)}…（内容过长，已截断）` : raw;
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return JSON.stringify(compactValue(parsed), null, 2) || '-';
  }

  const hint = `${span.nodeType || ''} ${span.nodeName || ''} ${span.spanName || ''}`.toLowerCase();
  let pattern = direction === 'input' ? INPUT_KEYS : OUTPUT_KEYS;
  if (/classifier|judge|condition|意图分类|问题分类|条件分支/.test(hint)) {
    pattern = direction === 'input' ? CLASSIFIER_INPUT_KEYS : CLASSIFIER_OUTPUT_KEYS;
  } else if (/variable.?assign|assign|变量赋值/.test(hint)) {
    pattern = direction === 'input' ? ASSIGN_INPUT_KEYS : ASSIGN_OUTPUT_KEYS;
  } else if (/llm|model|chat|agent|大模型|模型/.test(hint)) {
    pattern = direction === 'input' ? LLM_INPUT_KEYS : LLM_OUTPUT_KEYS;
  } else if (/start|user.?input|用户输入|开始/.test(hint)) {
    pattern = direction === 'input' ? START_INPUT_KEYS : START_OUTPUT_KEYS;
  } else if (/output|end|直接回复|结束/.test(hint)) {
    pattern = direction === 'input' ? END_INPUT_KEYS : END_OUTPUT_KEYS;
  }

  const object = parsed as Record<string, unknown>;
  const matchingKeys = Object.keys(object)
    .filter(key => !NOISE_KEY.test(key) && pattern.test(key));
  // If a node uses custom variable names, preserve its compacted payload rather than showing nothing.
  const selected = matchingKeys.length
    ? Object.fromEntries(matchingKeys.map(key => [key, object[key]]))
    : object;
  return JSON.stringify(compactValue(selected), null, 2) || '-';
}

const copyPayload = (raw?: string) => {
  if (!raw) {
    message.info('暂无可复制内容');
    return;
  }
  if (!navigator.clipboard?.writeText) {
    message.warning('当前浏览器不支持复制');
    return;
  }
  navigator.clipboard.writeText(json(raw))
    .then(() => message.success('已复制'))
    .catch(() => message.error('复制失败'));
};

const sameVariableValue = (left: unknown, right: unknown) => {
  try { return JSON.stringify(left) === JSON.stringify(right); }
  catch { return Object.is(left, right); }
};

const variableText = (value: unknown) => {
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value, null, 2) ?? String(value); }
  catch { return String(value); }
};

const statusTag = (v?: string) => {
  const m = STATUS[v || ''] || ['default', v || '-'];
  return <Tag color={m[0]}>{m[1]}</Tag>;
};
const kindTag = (v?: string) => {
  const m = KIND[v || ''] || ['default', v || '-'];
  return <Tag color={m[0]}>{m[1]}</Tag>;
};

function spanTree(spans: WorkflowSpan[]): SpanNode[] {
  const map = new Map<string, SpanNode>();
  spans.forEach(s => map.set(s.spanId, { ...s, children: [], depth: 0 }));
  const roots: SpanNode[] = [];
  map.forEach(n => {
    if (n.parentSpanId && map.has(n.parentSpanId)) map.get(n.parentSpanId)!.children.push(n);
    else roots.push(n);
  });
  const visit = (nodes: SpanNode[], depth: number) => {
    nodes.sort((a,b) => (a.sequenceNo ?? 999999) - (b.sequenceNo ?? 999999));
    nodes.forEach(n => { n.depth = depth; visit(n.children, depth + 1); });
  };
  visit(roots, 0);
  return roots;
}
function flatten(nodes: SpanNode[]) {
  const out: SpanNode[] = [];
  const walk = (xs: SpanNode[]) => xs.forEach(x => { out.push(x); walk(x.children); });
  walk(nodes);
  return out;
}

export default function WorkflowTracePage() {
  const [form] = Form.useForm<FilterForm>();
  const [records, setRecords] = useState<WorkflowTrace[]>([]);
  const [overview, setOverview] = useState<WorkflowTraceOverview>({});
  const [loading, setLoading] = useState(false);
  const [overviewLoading, setOverviewLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [size, setSize] = useState(20);
  const [total, setTotal] = useState(0);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [detail, setDetail] = useState<TraceDetail | null>(null);
  const [selectedSpan, setSelectedSpan] = useState<WorkflowSpan | null>(null);
  const [rawPayloadView, setRawPayloadView] = useState<Record<string, boolean>>({});
  const [showUnchangedSessionVariables, setShowUnchangedSessionVariables] = useState(false);
  const [workflowIOExpanded, setWorkflowIOExpanded] = useState(true);
  const [sessionVariablesExpanded, setSessionVariablesExpanded] = useState(true);

  const params = useCallback((page=current, pageSize=size): TraceListParams => {
    const v = form.getFieldsValue();
    return {
      current: page, size: pageSize,
      appId: v.appId?.trim() || undefined,
      keyword: v.keyword?.trim() || undefined,
      userId: v.userId?.trim() || undefined,
      status: v.status || undefined,
      invokeSource: v.invokeSource?.trim() || undefined,
      startTime: v.timeRange?.[0]?.toISOString(),
      endTime: v.timeRange?.[1]?.toISOString(),
    };
  }, [current, size, form]);

  const loadList = useCallback(async (page=current, pageSize=size) => {
    setLoading(true);
    try {
      const d = await getWorkflowTraces(params(page, pageSize));
      setRecords(d.records || []);
      setTotal(d.total || 0);
      setCurrent(d.current || page);
      setSize(d.size || pageSize);
    } catch (e) {
      console.error(e);
      message.error('获取 Workflow Trace 列表失败');
    } finally { setLoading(false); }
  }, [current, size, params]);

  const loadOverview = useCallback(async () => {
    setOverviewLoading(true);
    try {
      const { current: _c, size: _s, ...p } = params(1, 20);
      setOverview(await getWorkflowTraceOverview(p));
    } catch (e) {
      console.error(e);
      message.error('获取 Workflow Trace 概览失败');
    } finally { setOverviewLoading(false); }
  }, [params]);

  const search = useCallback(async () => {
    setCurrent(1);
    await Promise.all([loadList(1, size), loadOverview()]);
  }, [loadList, loadOverview, size]);

  const reset = () => {
    form.setFieldsValue({
      appId: undefined, keyword: undefined, userId: undefined, status: undefined,
      invokeSource: undefined, timeRange: [dayjs().subtract(24, 'hour'), dayjs()],
    });
    setTimeout(() => { loadList(1, size); loadOverview(); }, 0);
  };

  const openDetail = async (trace: WorkflowTrace) => {
    setDrawerOpen(true); setDetail(null); setSelectedSpan(null); setDetailLoading(true);
    setShowUnchangedSessionVariables(false);
    setWorkflowIOExpanded(true);
    setSessionVariablesExpanded(true);
    try {
      const d = await getWorkflowTraceDetail(trace.traceId);
      setDetail(d);
      setSelectedSpan([...(d.spans || [])].sort((a,b)=>(a.sequenceNo||0)-(b.sequenceNo||0))[0] || null);
    } catch (e) {
      console.error(e); message.error('获取 Trace 详情失败');
    } finally { setDetailLoading(false); }
  };

  useEffect(() => {
    form.setFieldsValue({ timeRange: [dayjs().subtract(24, 'hour'), dayjs()] });
    loadList(1, 20); loadOverview();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const successRate = overview.traceCount
    ? ((overview.successCount || 0) / overview.traceCount) * 100 : 0;

  const columns: ColumnsType<WorkflowTrace> = [
    { title:'状态', dataIndex:'status', width:90, render:statusTag },
    {
      title:'Trace ID', dataIndex:'traceId', width:220, ellipsis:true,
      render:(v:string)=>(
        <Space size={4}>
          <Text code ellipsis={{tooltip:v}} style={{maxWidth:165}}>{v}</Text>
          <Button type="text" size="small" icon={<CopyOutlined/>}
            onClick={(e)=>{e.stopPropagation(); navigator.clipboard?.writeText(v); message.success('已复制');}}/>
        </Space>
      ),
    },
    {
      title:'应用 / 版本', width:190,
      render:(_,r)=><div><div>{r.appId||'-'}</div><Text type="secondary">v{r.workflowVersion||'-'}</Text></div>,
    },
    { title:'User ID', dataIndex:'userId', width:150, ellipsis:true, render:(v:string)=><Text ellipsis={{tooltip:v}}>{v||'-'}</Text> },
    { title:'invoke_source', dataIndex:'invokeSource', width:180, ellipsis:true, render:v=><Tag>{v||'-'}</Tag> },
    { title:'耗时', dataIndex:'durationMs', width:105, render:duration },
    { title:'Token', dataIndex:'totalTokens', width:105, align:'right', render:num },
    { title:'模型调用', dataIndex:'modelCallCount', width:90, align:'center', render:v=>v||0 },
    { title:'结束原因', dataIndex:'finishReason', width:105, render:v=>v||'-' },
    { title:'开始时间', dataIndex:'startTime', width:190, render:time },
    { title:'操作', width:85, fixed:'right', render:(_,r)=>
      <Button type="link" icon={<EyeOutlined/>} onClick={()=>openDetail(r)}>详情</Button> },
  ];

  const flat = useMemo(() => flatten(spanTree(detail?.spans || [])), [detail]);
  const sessionVariableSnapshot = useMemo<{
    before: Record<string, unknown> | null;
    after: Record<string, unknown> | null;
  }>(() => {
    try {
      const data = JSON.parse(detail?.trace.traceData || '{}') as Record<string, unknown>;
      const isRecord = (value: unknown): value is Record<string, unknown> =>
        value !== null && typeof value === 'object' && !Array.isArray(value);
      return {
        before: isRecord(data.sessionVariablesBefore) ? data.sessionVariablesBefore : null,
        after: isRecord(data.sessionVariablesAfter) ? data.sessionVariablesAfter : null,
      };
    } catch {
      return { before: null, after: null };
    }
  }, [detail]);
  const sessionVariablesBefore = sessionVariableSnapshot.before;
  const sessionVariablesAfter = sessionVariableSnapshot.after;
  const allSessionVariableChanges = useMemo<SessionVariableChange[]>(() => {
    if (!sessionVariablesBefore || !sessionVariablesAfter) return [];
    const keys = Array.from(new Set([
      ...Object.keys(sessionVariablesBefore),
      ...Object.keys(sessionVariablesAfter),
    ])).sort();
    return keys.map(key => {
      const beforeExists = Object.prototype.hasOwnProperty.call(sessionVariablesBefore, key);
      const afterExists = Object.prototype.hasOwnProperty.call(sessionVariablesAfter, key);
      const change: SessionVariableChange['change'] = !beforeExists
        ? '新增'
        : !afterExists
          ? '删除'
          : sameVariableValue(sessionVariablesBefore[key], sessionVariablesAfter[key])
            ? '未变化'
            : '修改';
      return {
        key,
        before: sessionVariablesBefore[key],
        after: sessionVariablesAfter[key],
        beforeExists,
        afterExists,
        change,
      };
    });
  }, [sessionVariablesBefore, sessionVariablesAfter]);
  const sessionVariableChanges = useMemo(
    () => showUnchangedSessionVariables
      ? allSessionVariableChanges
      : allSessionVariableChanges.filter(item => item.change !== '未变化'),
    [allSessionVariableChanges, showUnchangedSessionVariables],
  );
  const traceStart = parseTraceTime(detail?.trace.startTime)?.valueOf()
    ?? flat.map(s => parseTraceTime(s.startTime)?.valueOf())
      .filter((v): v is number => v !== undefined)
      .sort((a,b) => a-b)[0]
    ?? 0;
  const traceDuration = Math.max(detail?.trace.durationMs || 1, 1);
  const sessionVariableColumns: ColumnsType<SessionVariableChange> = [
    {
      title: '变量',
      dataIndex: 'key',
      width: 150,
      render: (value: string) => <Text code>{value}</Text>,
    },
    {
      title: '执行前',
      key: 'before',
      render: (_: unknown, record: SessionVariableChange) => (
        <pre className={styles.sessionVariableValue}>
          {record.beforeExists ? variableText(record.before) : '（不存在）'}
        </pre>
      ),
    },
    {
      title: '执行后',
      key: 'after',
      render: (_: unknown, record: SessionVariableChange) => (
        <pre className={styles.sessionVariableValue}>
          {record.afterExists ? variableText(record.after) : '（不存在）'}
        </pre>
      ),
    },
    {
      title: '变化',
      dataIndex: 'change',
      width: 90,
      render: (value: SessionVariableChange['change']) => (
        <Tag color={value === '新增' ? 'green' : value === '删除' ? 'red' : value === '修改' ? 'blue' : 'default'}>
          {value}
        </Tag>
      ),
    },
  ];

  const spanPayloads: Array<{ key: PayloadDirection; label: string; value?: string }> = selectedSpan ? [
    { key: 'input', label: 'Input（节点关键信息）', value: selectedSpan.inputData },
    { key: 'output', label: 'Output（节点关键信息）', value: selectedSpan.outputData },
  ] : [];

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <Title level={3} className={styles.title}>Workflow Trace</Title>
          <Text type="secondary">工作流执行链路、节点耗时、模型调用与 Token 消耗</Text>
        </div>
        <Button icon={<ReloadOutlined/>} onClick={search}>刷新</Button>
      </div>

      <Spin spinning={overviewLoading}>
        <Row gutter={16} className={styles.metrics}>
          <Col xs={24} sm={12} lg={4}><Card><Statistic title="Trace 数" value={overview.traceCount||0}/></Card></Col>
          <Col xs={24} sm={12} lg={5}><Card><Statistic title="成功率" value={successRate} precision={1} suffix="%"/></Card></Col>
          <Col xs={24} sm={12} lg={5}><Card><Statistic title="平均耗时" value={duration(overview.avgDurationMs)}/></Card></Col>
          <Col xs={24} sm={12} lg={5}><Card><Statistic title="总 Token" value={overview.totalTokens||0} formatter={v=>num(Number(v))}/></Card></Col>
          <Col xs={24} sm={12} lg={5}><Card><Statistic title="模型调用" value={overview.modelCallCount||0} prefix={<RobotOutlined/>}/></Card></Col>
        </Row>
      </Spin>

      <Card className={styles.filterCard}>
        <Form form={form} layout="inline" onFinish={search}>
          <Form.Item name="keyword"><Input allowClear prefix={<SearchOutlined/>} placeholder="Trace / Task / Request / Conversation ID" style={{width:290}}/></Form.Item>
          <Form.Item name="appId"><Input allowClear placeholder="App ID" style={{width:180}}/></Form.Item>
          <Form.Item name="userId"><Input allowClear placeholder="User ID（精确匹配）" style={{width:170}}/></Form.Item>
          <Form.Item name="status"><Select allowClear placeholder="状态" style={{width:120}} options={[
            {label:'成功',value:'success'},{label:'失败',value:'fail'},{label:'执行中',value:'executing'},
            {label:'停止',value:'stop'},{label:'暂停',value:'pause'}]}/></Form.Item>
          <Form.Item name="invokeSource"><Input allowClear placeholder="invoke_source / 调用来源" style={{width:210}}/></Form.Item>
          <Form.Item name="timeRange"><RangePicker showTime style={{width:350}}/></Form.Item>
          <Form.Item><Space><Button type="primary" htmlType="submit" icon={<SearchOutlined/>}>查询</Button><Button onClick={reset}>重置</Button></Space></Form.Item>
        </Form>
      </Card>

      <Card className={styles.tableCard} bodyStyle={{padding:0}}>
        <Table rowKey="traceId" loading={loading} columns={columns} dataSource={records}
          scroll={{x:1540}}
          onRow={r=>({onDoubleClick:()=>openDetail(r)})}
          pagination={{
            current,pageSize:size,total,showSizeChanger:true,showQuickJumper:true,
            showTotal:n=>`共 ${n} 条`,
            onChange:(p,s)=>{setCurrent(p);setSize(s);loadList(p,s);},
          }}/>
      </Card>

      <Drawer title={<Space><ApartmentOutlined/>Workflow Trace 详情 {detail?.trace.status&&statusTag(detail.trace.status)}</Space>}
        width="88vw" open={drawerOpen} onClose={()=>setDrawerOpen(false)} destroyOnClose>
        <Spin spinning={detailLoading}>
          {!detail ? (detailLoading ? null : <Empty/>) : <>
            <Descriptions bordered size="small" column={4} className={styles.description}>
              <Descriptions.Item label="Trace ID" span={2}><Text copyable code>{detail.trace.traceId}</Text></Descriptions.Item>
              <Descriptions.Item label="Task ID"><Text copyable>{detail.trace.taskId||'-'}</Text></Descriptions.Item>
              <Descriptions.Item label="结束原因">{detail.trace.finishReason||'-'}</Descriptions.Item>
              <Descriptions.Item label="App ID">{detail.trace.appId||'-'}</Descriptions.Item>
              <Descriptions.Item label="User ID"><Text copyable>{detail.trace.userId||'-'}</Text></Descriptions.Item>
              <Descriptions.Item label="版本">{detail.trace.workflowVersion||'-'}</Descriptions.Item>
              <Descriptions.Item label="Conversation ID">{detail.trace.conversationId||'-'}</Descriptions.Item>
              <Descriptions.Item label="invoke_source">{detail.trace.invokeSource||'-'}</Descriptions.Item>
              <Descriptions.Item label="总耗时">{duration(detail.trace.durationMs)}</Descriptions.Item>
              <Descriptions.Item label="Prompt Token">{num(detail.trace.promptTokens)}</Descriptions.Item>
              <Descriptions.Item label="Completion Token">{num(detail.trace.completionTokens)}</Descriptions.Item>
              <Descriptions.Item label="Total Token">{num(detail.trace.totalTokens)}</Descriptions.Item>
            </Descriptions>

            <Card
              title="整个流程输入 / 输出"
              size="small"
              className={styles.workflowIOCard}
              extra={<Button type="text" size="small" icon={workflowIOExpanded ? <UpOutlined/> : <DownOutlined/>} onClick={() => setWorkflowIOExpanded(value => !value)}>{workflowIOExpanded ? "收起" : "展开"}</Button>}
            >
              {workflowIOExpanded && (
              <Row gutter={[16, 16]}>
                <Col xs={24} xl={12}>
                  <div className={styles.workflowPayload}>
                    <div className={styles.payloadHeading}>
                      <Text strong>流程输入</Text>
                      <Button type="link" size="small" icon={<CopyOutlined/>}
                        disabled={!detail.trace.inputData} onClick={() => copyPayload(detail.trace.inputData)}>复制</Button>
                    </div>
                    <pre>{json(detail.trace.inputData)}</pre>
                  </div>
                </Col>
                <Col xs={24} xl={12}>
                  <div className={styles.workflowPayload}>
                    <div className={styles.payloadHeading}>
                      <Text strong>流程输出</Text>
                      <Button type="link" size="small" icon={<CopyOutlined/>}
                        disabled={!detail.trace.outputData} onClick={() => copyPayload(detail.trace.outputData)}>复制</Button>
                    </div>
                    <pre>{json(detail.trace.outputData)}</pre>
                  </div>
                </Col>
              </Row>
              )}
            </Card>

            <Card
              title="会话变量变化"
              size="small"
              className={styles.sessionVariablesCard}
              extra={<Button type="text" size="small" icon={sessionVariablesExpanded ? <UpOutlined/> : <DownOutlined/>} onClick={() => setSessionVariablesExpanded(value => !value)}>{sessionVariablesExpanded ? "收起" : "展开"}</Button>}
            >
              {sessionVariablesExpanded && (
              !sessionVariablesBefore || !sessionVariablesAfter ? (
                <Empty description="当前 Trace 未记录完整会话变量快照（旧记录或 Start 节点未执行）" />
              ) : (
                <>
                  <div className={styles.sessionVariablesSummary}>
                    <Text type="secondary">
                      执行前 {Object.keys(sessionVariablesBefore).length} 项 · 执行后 {Object.keys(sessionVariablesAfter).length} 项 ·
                      变化 {allSessionVariableChanges.filter(item => item.change !== '未变化').length} 项
                    </Text>
                    <Button type="link" size="small"
                      onClick={() => setShowUnchangedSessionVariables(value => !value)}>
                      {showUnchangedSessionVariables ? '隐藏未变化' : '显示未变化'}
                    </Button>
                  </div>
                  {sessionVariableChanges.length ? (
                    <Table<SessionVariableChange>
                      rowKey="key"
                      size="small"
                      pagination={false}
                      columns={sessionVariableColumns}
                      dataSource={sessionVariableChanges}
                      scroll={{ x: 720 }}
                    />
                  ) : (
                    <Empty description="本次执行没有会话变量变化" />
                  )}
                  <Row gutter={[12, 12]} className={styles.sessionVariableSnapshots}>
                    <Col xs={24} xl={12}>
                      <details className={styles.rawDetails}>
                        <summary>执行前完整会话变量</summary>
                        <pre>{JSON.stringify(sessionVariablesBefore, null, 2)}</pre>
                      </details>
                    </Col>
                    <Col xs={24} xl={12}>
                      <details className={styles.rawDetails}>
                        <summary>执行后完整会话变量</summary>
                        <pre>{JSON.stringify(sessionVariablesAfter, null, 2)}</pre>
                      </details>
                    </Col>
                  </Row>
                  <Text type="secondary" className={styles.sessionVariablesNote}>
                    结束快照记录的是本次运行结束时的变量状态；失败、停止或暂停时，不代表这些变量已写入会话记录。
                  </Text>
                </>
              )
              )}
            </Card>

            {detail.trace.errorMessage && <Card size="small" className={styles.errorCard}>
              <Text type="danger">{detail.trace.errorCode?`[${detail.trace.errorCode}] `:''}{detail.trace.errorMessage}</Text>
            </Card>}

            <div className={styles.detailGrid}>
              <Card title={`执行链路 (${detail.spans?.length||0} spans)`} className={styles.waterfallCard}>
                {!flat.length ? <Empty/> : <div className={styles.waterfall}>
                  <div className={styles.waterfallHeader}><div>Span</div><div>时间轴</div></div>
                  {flat.map(s=>{
                    const start=parseTraceTime(s.startTime)?.valueOf() ?? traceStart;
                    const left=Math.max(0,Math.min(98,((start-traceStart)/traceDuration)*100));
                    const width=Math.max(1.5,Math.min(100-left,((s.durationMs||0)/traceDuration)*100));
                    return <div key={s.spanId}
                      className={`${styles.spanRow} ${selectedSpan?.spanId===s.spanId?styles.spanRowActive:''}`}
                      onClick={()=>setSelectedSpan(s)}>
                      <div className={styles.spanLabel} style={{paddingLeft:12+s.depth*22}}>
                        <Space size={6}>{kindTag(s.spanKind)}
                          <Tooltip title={s.spanName||s.nodeName}><Text ellipsis style={{maxWidth:220}}>{s.spanName||s.nodeName||s.nodeId}</Text></Tooltip>
                        </Space>
                        <Text type="secondary" className={styles.spanMeta}>
                          {isModelSpan(s.spanKind)?`${s.provider||'-'} / ${s.modelName||s.modelId||'-'}`:s.nodeType||''}
                        </Text>
                      </div>
                      <div className={styles.timeline}>
                        <Tooltip title={`${duration(s.durationMs)} · ${time(s.startTime)}`}>
                          <div className={`${styles.bar} ${s.status==='fail'?styles.barFail:isModelSpan(s.spanKind)?styles.barModel:styles.barNode}`}
                            style={{left:`${left}%`,width:`${width}%`}}><span>{duration(s.durationMs)}</span></div>
                        </Tooltip>
                      </div>
                    </div>;
                  })}
                </div>}
              </Card>

              <Card title="Span 详情" className={styles.spanDetailCard}>
                {!selectedSpan ? <Empty description="请选择一个 Span"/> : <>
                  <Descriptions size="small" column={1} bordered>
                    <Descriptions.Item label="Span ID"><Text copyable>{selectedSpan.spanId}</Text></Descriptions.Item>
                    <Descriptions.Item label="类型">{kindTag(selectedSpan.spanKind)}</Descriptions.Item>
                    <Descriptions.Item label="状态">{statusTag(selectedSpan.status)}</Descriptions.Item>
                    <Descriptions.Item label="节点">{selectedSpan.nodeName||selectedSpan.nodeId||'-'}</Descriptions.Item>
                    <Descriptions.Item label="节点类型">{selectedSpan.nodeType||'-'}</Descriptions.Item>
                    <Descriptions.Item label="模型">{isModelSpan(selectedSpan.spanKind)?`${selectedSpan.provider||'-'} / ${selectedSpan.modelName||selectedSpan.modelId||'-'}`:'-'}</Descriptions.Item>
                    <Descriptions.Item label="耗时">{duration(selectedSpan.durationMs)}</Descriptions.Item>
                    <Descriptions.Item label="Token">{selectedSpan.spanKind==='MODEL_CALL'?`${num(selectedSpan.promptTokens)} + ${num(selectedSpan.completionTokens)} = ${num(selectedSpan.totalTokens)}`:'-'}</Descriptions.Item>
                  </Descriptions>
                  {selectedSpan.errorMessage&&<div className={styles.errorText}>{selectedSpan.errorCode?`[${selectedSpan.errorCode}] `:''}{selectedSpan.errorMessage}</div>}
                  {spanPayloads.map(item => {
                    const viewKey = `${selectedSpan.spanId}:${item.key}`;
                    const showRaw = !!rawPayloadView[viewKey];
                    return <div className={styles.payloadSection} key={item.key}>
                      <div className={styles.payloadHeading}>
                        <Text strong>{item.label}</Text>
                        <Button type="link" size="small"
                          onClick={() => setRawPayloadView(previous => ({...previous, [viewKey]: !showRaw}))}>
                          {showRaw ? '显示关键信息' : '查看原始 JSON'}
                        </Button>
                      </div>
                      <pre>{showRaw ? json(item.value) : compactPayload(item.value, selectedSpan, item.key)}</pre>
                    </div>;
                  })}
                  {selectedSpan.spanData && <details className={styles.rawDetails}>
                    <summary>原始 Span Data（调试用）</summary>
                    <pre>{json(selectedSpan.spanData)}</pre>
                  </details>}
                </>}
              </Card>
            </div>
          </>}
        </Spin>
      </Drawer>
    </div>
  );
}
