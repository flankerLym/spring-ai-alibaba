import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ApartmentOutlined, CopyOutlined, EyeOutlined, ReloadOutlined,
  RobotOutlined, SearchOutlined,
} from '@ant-design/icons';
import {
  Button, Card, Col, DatePicker, Descriptions, Drawer, Empty, Form, Input,
  message, Row, Select, Space, Spin, Statistic, Table, Tag, Tooltip, Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import {
  getWorkflowTraceDetail, getWorkflowTraceOverview, getWorkflowTraces,
  type TraceDetail, type TraceListParams, type WorkflowSpan,
  type WorkflowTrace, type WorkflowTraceOverview,
} from '@/services/workflowTrace';
import styles from './index.module.less';

const { RangePicker } = DatePicker;
const { Text, Title } = Typography;

type FilterForm = {
  appId?: string;
  keyword?: string;
  status?: string;
  invokeSource?: string;
  timeRange?: [dayjs.Dayjs, dayjs.Dayjs];
};
type SpanNode = WorkflowSpan & { children: SpanNode[]; depth: number };

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
  SUB_WORKFLOW: ['cyan', 'SUB FLOW'],
};

const num = (v?: number) => new Intl.NumberFormat('zh-CN').format(v || 0);
const duration = (v?: number) =>
  v === null || v === undefined ? '-' : v < 1000 ? `${v} ms` : `${(v / 1000).toFixed(2)} s`;
const time = (v?: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss.SSS') : '-';
const json = (v?: string) => {
  if (!v) return '-';
  try { return JSON.stringify(JSON.parse(v), null, 2); } catch { return v; }
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

  const params = useCallback((page=current, pageSize=size): TraceListParams => {
    const v = form.getFieldsValue();
    return {
      current: page, size: pageSize,
      appId: v.appId?.trim() || undefined,
      keyword: v.keyword?.trim() || undefined,
      status: v.status || undefined,
      invokeSource: v.invokeSource || undefined,
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
      appId: undefined, keyword: undefined, status: undefined, invokeSource: undefined,
      timeRange: [dayjs().subtract(24, 'hour'), dayjs()],
    });
    setTimeout(() => { loadList(1, size); loadOverview(); }, 0);
  };

  const openDetail = async (trace: WorkflowTrace) => {
    setDrawerOpen(true); setDetail(null); setSelectedSpan(null); setDetailLoading(true);
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
    { title:'来源', dataIndex:'invokeSource', width:95, render:v=><Tag>{v||'-'}</Tag> },
    { title:'耗时', dataIndex:'durationMs', width:105, render:duration },
    { title:'Token', dataIndex:'totalTokens', width:105, align:'right', render:num },
    { title:'模型调用', dataIndex:'modelCallCount', width:90, align:'center', render:v=>v||0 },
    { title:'结束原因', dataIndex:'finishReason', width:105, render:v=>v||'-' },
    { title:'开始时间', dataIndex:'startTime', width:190, render:time },
    { title:'操作', width:85, fixed:'right', render:(_,r)=>
      <Button type="link" icon={<EyeOutlined/>} onClick={()=>openDetail(r)}>详情</Button> },
  ];

  const flat = useMemo(() => flatten(spanTree(detail?.spans || [])), [detail]);
  const traceStart = detail?.trace.startTime ? dayjs(detail.trace.startTime).valueOf() : 0;
  const traceDuration = Math.max(detail?.trace.durationMs || 1, 1);

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
          <Form.Item name="status"><Select allowClear placeholder="状态" style={{width:120}} options={[
            {label:'成功',value:'success'},{label:'失败',value:'fail'},{label:'执行中',value:'executing'},
            {label:'停止',value:'stop'},{label:'暂停',value:'pause'}]}/></Form.Item>
          <Form.Item name="invokeSource"><Select allowClear placeholder="调用来源" style={{width:120}} options={[
            {label:'API',value:'api'},{label:'Console',value:'console'},{label:'Async',value:'async'}]}/></Form.Item>
          <Form.Item name="timeRange"><RangePicker showTime style={{width:350}}/></Form.Item>
          <Form.Item><Space><Button type="primary" htmlType="submit" icon={<SearchOutlined/>}>查询</Button><Button onClick={reset}>重置</Button></Space></Form.Item>
        </Form>
      </Card>

      <Card className={styles.tableCard} bodyStyle={{padding:0}}>
        <Table rowKey="traceId" loading={loading} columns={columns} dataSource={records}
          scroll={{x:1400}}
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
              <Descriptions.Item label="版本">{detail.trace.workflowVersion||'-'}</Descriptions.Item>
              <Descriptions.Item label="Conversation ID">{detail.trace.conversationId||'-'}</Descriptions.Item>
              <Descriptions.Item label="来源">{detail.trace.invokeSource||'-'}</Descriptions.Item>
              <Descriptions.Item label="总耗时">{duration(detail.trace.durationMs)}</Descriptions.Item>
              <Descriptions.Item label="Prompt Token">{num(detail.trace.promptTokens)}</Descriptions.Item>
              <Descriptions.Item label="Completion Token">{num(detail.trace.completionTokens)}</Descriptions.Item>
              <Descriptions.Item label="Total Token">{num(detail.trace.totalTokens)}</Descriptions.Item>
            </Descriptions>

            {detail.trace.errorMessage && <Card size="small" className={styles.errorCard}>
              <Text type="danger">{detail.trace.errorCode?`[${detail.trace.errorCode}] `:''}{detail.trace.errorMessage}</Text>
            </Card>}

            <div className={styles.detailGrid}>
              <Card title={`执行链路 (${detail.spans?.length||0} spans)`} className={styles.waterfallCard}>
                {!flat.length ? <Empty/> : <div className={styles.waterfall}>
                  <div className={styles.waterfallHeader}><div>Span</div><div>时间轴</div></div>
                  {flat.map(s=>{
                    const start=s.startTime?dayjs(s.startTime).valueOf():traceStart;
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
                          {s.spanKind==='MODEL_CALL'?`${s.provider||'-'} / ${s.modelName||s.modelId||'-'}`:s.nodeType||''}
                        </Text>
                      </div>
                      <div className={styles.timeline}>
                        <Tooltip title={`${duration(s.durationMs)} · ${time(s.startTime)}`}>
                          <div className={`${styles.bar} ${s.status==='fail'?styles.barFail:s.spanKind==='MODEL_CALL'?styles.barModel:styles.barNode}`}
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
                    <Descriptions.Item label="模型">{selectedSpan.spanKind==='MODEL_CALL'?`${selectedSpan.provider||'-'} / ${selectedSpan.modelName||selectedSpan.modelId||'-'}`:'-'}</Descriptions.Item>
                    <Descriptions.Item label="耗时">{duration(selectedSpan.durationMs)}</Descriptions.Item>
                    <Descriptions.Item label="Token">{selectedSpan.spanKind==='MODEL_CALL'?`${num(selectedSpan.promptTokens)} + ${num(selectedSpan.completionTokens)} = ${num(selectedSpan.totalTokens)}`:'-'}</Descriptions.Item>
                  </Descriptions>
                  {selectedSpan.errorMessage&&<div className={styles.errorText}>{selectedSpan.errorCode?`[${selectedSpan.errorCode}] `:''}{selectedSpan.errorMessage}</div>}
                  <div className={styles.jsonSection}><Text strong>Input</Text><pre>{json(selectedSpan.inputData)}</pre></div>
                  <div className={styles.jsonSection}><Text strong>Output</Text><pre>{json(selectedSpan.outputData)}</pre></div>
                  <div className={styles.jsonSection}><Text strong>Span Data</Text><pre>{json(selectedSpan.spanData)}</pre></div>
                </>}
              </Card>
            </div>
          </>}
        </Spin>
      </Drawer>
    </div>
  );
}
