import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ApartmentOutlined,
  ClockCircleOutlined,
  EyeOutlined,
  KeyOutlined,
  MessageOutlined,
  ReloadOutlined,
  SearchOutlined,
  UserOutlined,
  DownOutlined,
  UpOutlined,
} from '@ant-design/icons';
import {
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Divider,
  Drawer,
  Empty,
  Form,
  Input,
  message,
  Pagination,
  Row,
  Select,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { getApiKey, listApiKeys } from '@/services/apiKey';
import { getWorkflowTraceDetail, type TraceDetail, type WorkflowSpan } from '@/services/workflowTrace';
import type { IApiKey } from '@/types/apiKey';
import {
  queryConversationMessages,
  queryConversations,
  type ConversationMessage,
  type ConversationQuery,
  type ConversationRecord,
  type ConversationTime,
  type MessageQuery,
} from '@/services/conversationHistory';
import styles from './index.module.less';

const { RangePicker } = DatePicker;
const { Text, Title, Paragraph } = Typography;
const API_KEY_STORAGE = 'saa-conversation-history-api-key';

type FilterForm = {
  conversationId?: string;
  appId?: string;
  userId?: string;
  name?: string;
  invokeSource?: string;
  timeRange?: [dayjs.Dayjs, dayjs.Dayjs];
};

type ApiKeyOption = IApiKey & { api_key: string };
type TraceVariableChange = {
  key: string;
  before: unknown;
  after: unknown;
  beforeExists: boolean;
  afterExists: boolean;
  change: '新增' | '修改' | '删除' | '未变化';
};

const readApiKey = () => {
  if (typeof window === 'undefined') return '';
  return window.sessionStorage.getItem(API_KEY_STORAGE) || '';
};

const formatTime = (value?: ConversationTime) => {
  if (value === undefined || value === null || value === '') return '-';
  let parsed: dayjs.Dayjs;
  if (typeof value === 'number') {
    parsed = dayjs(value < 1e12 ? value * 1000 : value);
  } else if (/^\d+(?:\.\d+)?$/.test(value)) {
    const timestamp = Number(value);
    parsed = dayjs(timestamp < 1e12 ? timestamp * 1000 : timestamp);
  } else {
    parsed = dayjs(value);
  }
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : '-';
};

const statusTag = (value?: string) => {
  const status = (value || '').toLowerCase();
  const colorMap: Record<string, string> = {
    normal: 'success',
    success: 'success',
    completed: 'success',
    fail: 'error',
    failed: 'error',
    error: 'error',
    running: 'processing',
    executing: 'processing',
    in_progress: 'processing',
    pause: 'warning',
    paused: 'warning',
  };
  const labelMap: Record<string, string> = {
    normal: '正常',
    success: '成功',
    completed: '已完成',
    fail: '失败',
    failed: '失败',
    error: '异常',
    running: '运行中',
    executing: '执行中',
    in_progress: '执行中',
    pause: '暂停',
    paused: '已暂停',
  };
  return <Tag color={colorMap[status] || 'default'}>{labelMap[status] || value || '-'}</Tag>;
};

const getErrorMessage = (error: unknown) =>
  error instanceof Error ? error.message : '请求失败，请检查 API Key 和后端服务';

const traceJson = (value?: string) => {
  if (!value) return '-';
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
};

const traceDuration = (value?: number) =>
  value === undefined || value === null ? '-' : value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(2)} s`;

const isTraceRecord = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);

const sameTraceValue = (left: unknown, right: unknown) => {
  try { return JSON.stringify(left) === JSON.stringify(right); }
  catch { return Object.is(left, right); }
};

const traceVariableText = (value: unknown) => {
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value, null, 2) ?? String(value); }
  catch { return String(value); }
};

export default function ConversationHistoryPage() {
  const [form] = Form.useForm<FilterForm>();
  const [apiKeyInput, setApiKeyInput] = useState(readApiKey);
  const [apiKey, setApiKey] = useState(readApiKey);
  const [apiKeyOptions, setApiKeyOptions] = useState<ApiKeyOption[]>([]);
  const [apiKeyOptionsLoading, setApiKeyOptionsLoading] = useState(false);
  const [records, setRecords] = useState<ConversationRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [size, setSize] = useState(20);
  const [total, setTotal] = useState(0);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [selectedConversation, setSelectedConversation] = useState<ConversationRecord | null>(null);
  const [messages, setMessages] = useState<ConversationMessage[]>([]);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [messageCurrent, setMessageCurrent] = useState(1);
  const [messageSize, setMessageSize] = useState(10);
  const [messageTotal, setMessageTotal] = useState(0);
  const [expandedMessageCards, setExpandedMessageCards] = useState<Record<string, boolean>>({});
  const [expandedMessageParts, setExpandedMessageParts] = useState<Record<string, boolean>>({});
  const [traceDrawerOpen, setTraceDrawerOpen] = useState(false);
  const [traceDetailLoading, setTraceDetailLoading] = useState(false);
  const [traceDetail, setTraceDetail] = useState<TraceDetail | null>(null);
  const [selectedTraceSpan, setSelectedTraceSpan] = useState<WorkflowSpan | null>(null);
  const [traceWorkflowIOExpanded, setTraceWorkflowIOExpanded] = useState(true);
  const [traceVariablesExpanded, setTraceVariablesExpanded] = useState(true);
  const [showUnchangedTraceVariables, setShowUnchangedTraceVariables] = useState(false);

  // The console API masks keys in the list response. Fetch each key's detail through
  // the existing authenticated endpoint so the selector can use the real key value.
  const loadApiKeyOptions = useCallback(async () => {
    setApiKeyOptionsLoading(true);
    try {
      const result = await listApiKeys({ current: 1, size: 100 });
      const list = result.data?.records || [];
      const details = await Promise.all(
        list.map(async (item): Promise<ApiKeyOption | null> => {
          if (item.id == null) return null;
          try {
            const detail = await getApiKey(item.id);
            const key = detail.data?.api_key;
            if (!key || /[*•]/.test(key)) return null;
            return { ...item, ...detail.data, api_key: key };
          } catch (error) {
            console.warn(`Unable to load API Key detail (${item.id})`, error);
            return null;
          }
        }),
      );
      setApiKeyOptions(details.filter((item): item is ApiKeyOption => item !== null));
    } catch (error) {
      console.error('Failed to load API Key options:', error);
      message.error(`加载 API Key 列表失败：${getErrorMessage(error)}`);
      setApiKeyOptions([]);
    } finally {
      setApiKeyOptionsLoading(false);
    }
  }, []);

  const buildQuery = useCallback((pageNum: number, pageSize: number): ConversationQuery => {
    const values = form.getFieldsValue();
    return {
      conversationId: values.conversationId?.trim() || undefined,
      appId: values.appId?.trim() || undefined,
      userId: values.userId?.trim() || undefined,
      name: values.name?.trim() || undefined,
      invokeSource: values.invokeSource?.trim() || undefined,
      startTime: values.timeRange?.[0]?.toISOString(),
      endTime: values.timeRange?.[1]?.toISOString(),
      pageNum,
      pageSize,
    };
  }, [form]);

  const loadConversations = useCallback(async (
    pageNum = 1,
    pageSize = 20,
    key = apiKey,
  ) => {
    if (!key) {
      setRecords([]);
      setTotal(0);
      return;
    }
    setLoading(true);
    try {
      const result = await queryConversations(key, buildQuery(pageNum, pageSize));
      setRecords(result.records || []);
      setTotal(result.total || 0);
      setCurrent(result.current || pageNum);
      setSize(result.size || pageSize);
    } catch (error) {
      console.error('Failed to load conversation history:', error);
      message.error(getErrorMessage(error));
      setRecords([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [apiKey, buildQuery]);

  const loadMessages = useCallback(async (
    conversation: ConversationRecord,
    pageNum = 1,
    pageSize = 10,
    key = apiKey,
  ) => {
    if (!key) {
      message.warning('请先选择 API Key');
      return;
    }
    setMessagesLoading(true);
    try {
      const params: MessageQuery = {
        conversationId: conversation.conversationId,
        appId: conversation.appId,
        pageNum,
        pageSize,
      };
      const result = await queryConversationMessages(key, params);
      setMessages(result.records || []);
      setMessageTotal(result.total || 0);
      setMessageCurrent(result.current || pageNum);
      setMessageSize(result.size || pageSize);
    } catch (error) {
      console.error('Failed to load conversation messages:', error);
      message.error(getErrorMessage(error));
      setMessages([]);
      setMessageTotal(0);
    } finally {
      setMessagesLoading(false);
    }
  }, [apiKey]);

  useEffect(() => {
    void loadApiKeyOptions();
  }, [loadApiKeyOptions]);

  useEffect(() => {
    if (apiKey) void loadConversations(1, 20, apiKey);
  }, [apiKey, loadConversations]);

  const activateApiKey = () => {
    const key = apiKeyInput.trim();
    if (!key) {
      message.warning('请先从下拉列表选择 API Key');
      return;
    }
    window.sessionStorage.setItem(API_KEY_STORAGE, key);
    setApiKeyInput(key);
    setCurrent(1);
    if (key === apiKey) {
      void loadConversations(1, size, key);
    } else {
      setApiKey(key);
    }
  };

  const handleApiKeyChange = (key?: string) => {
    if (!key) {
      clearApiKey();
      return;
    }
    window.sessionStorage.setItem(API_KEY_STORAGE, key);
    setApiKeyInput(key);
    setCurrent(1);
    setMessages([]);
    setMessageTotal(0);
    setDrawerOpen(false);
    setApiKey(key);
  };

  const clearApiKey = () => {
    window.sessionStorage.removeItem(API_KEY_STORAGE);
    setApiKeyInput('');
    setApiKey('');
    setRecords([]);
    setTotal(0);
    setMessages([]);
    setMessageTotal(0);
    setDrawerOpen(false);
  };

  const search = () => {
    if (!apiKey) {
      message.warning('请先选择 API Key');
      return;
    }
    void loadConversations(1, size, apiKey);
  };

  const resetFilters = () => {
    form.resetFields();
    setCurrent(1);
    window.setTimeout(() => void loadConversations(1, size, apiKey), 0);
  };

  const openMessages = (conversation: ConversationRecord) => {
    setSelectedConversation(conversation);
    setDrawerOpen(true);
    setMessages([]);
    setMessageCurrent(1);
    void loadMessages(conversation, 1, 10, apiKey);
  };

  const openTraceDetail = async (traceId?: string) => {
    if (!traceId) {
      message.info('该消息暂无关联的 Trace ID');
      return;
    }
    setTraceDrawerOpen(true);
    setTraceDetailLoading(true);
    setTraceDetail(null);
    setSelectedTraceSpan(null);
    setTraceWorkflowIOExpanded(true);
    setTraceVariablesExpanded(true);
    setShowUnchangedTraceVariables(false);
    try {
      const result = await getWorkflowTraceDetail(traceId);
      setTraceDetail(result);
      const spans = [...(result.spans || [])].sort((a, b) => (a.sequenceNo || 0) - (b.sequenceNo || 0));
      setSelectedTraceSpan(spans[0] || null);
    } catch (error) {
      console.error('Failed to load trace from conversation message:', error);
      message.error(`获取执行链路失败，请检查 Trace ID 是否有效：${traceId}`);
    } finally {
      setTraceDetailLoading(false);
    }
  };

  const traceSessionSnapshot = useMemo(() => {
    try {
      const data = JSON.parse(traceDetail?.trace.traceData || '{}') as Record<string, unknown>;
      return {
        before: isTraceRecord(data.sessionVariablesBefore) ? data.sessionVariablesBefore : null,
        after: isTraceRecord(data.sessionVariablesAfter) ? data.sessionVariablesAfter : null,
      };
    } catch {
      return { before: null, after: null };
    }
  }, [traceDetail]);

  const traceVariableChanges = useMemo<TraceVariableChange[]>(() => {
    const before = traceSessionSnapshot.before;
    const after = traceSessionSnapshot.after;
    if (!before || !after) return [];
    return Array.from(new Set([...Object.keys(before), ...Object.keys(after)])).sort().map((key) => {
      const beforeExists = Object.prototype.hasOwnProperty.call(before, key);
      const afterExists = Object.prototype.hasOwnProperty.call(after, key);
      const change: TraceVariableChange['change'] = !beforeExists ? '新增'
        : !afterExists ? '删除'
          : sameTraceValue(before[key], after[key]) ? '未变化' : '修改';
      return { key, before: before[key], after: after[key], beforeExists, afterExists, change };
    }).filter(item => showUnchangedTraceVariables || item.change !== '未变化');
  }, [traceSessionSnapshot, showUnchangedTraceVariables]);

  const traceVariableColumns: ColumnsType<TraceVariableChange> = [
    { title: '变量', dataIndex: 'key', width: 130, render: (value: string) => <Text code>{value}</Text> },
    { title: '执行前', key: 'before', render: (_: unknown, item) => <pre style={{ margin: 0, maxHeight: 100, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{item.beforeExists ? traceVariableText(item.before) : '（不存在）'}</pre> },
    { title: '执行后', key: 'after', render: (_: unknown, item) => <pre style={{ margin: 0, maxHeight: 100, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{item.afterExists ? traceVariableText(item.after) : '（不存在）'}</pre> },
    { title: '变化', dataIndex: 'change', width: 80, render: (value: TraceVariableChange['change']) => <Tag color={value === '新增' ? 'green' : value === '删除' ? 'red' : value === '修改' ? 'blue' : 'default'}>{value}</Tag> },
  ];

  const traceSpanColumns: ColumnsType<WorkflowSpan> = [
    { title: 'Span / 节点', key: 'spanName', width: 230, render: (_, span) => <Space direction="vertical" size={0}><Text strong>{span.spanName || span.nodeName || span.nodeId || '-'}</Text><Text type="secondary" style={{ fontSize: 11 }}>{span.nodeType || span.spanKind || ''}</Text></Space> },
    { title: '类型', dataIndex: 'spanKind', width: 105, render: (value?: string) => <Tag color={value === 'MODEL_CALL' ? 'purple' : value === 'MODEL_PHASE' ? 'gold' : 'blue'}>{value || 'NODE'}</Tag> },
    { title: '状态', dataIndex: 'status', width: 80, render: statusTag },
    { title: '耗时', dataIndex: 'durationMs', width: 90, render: traceDuration },
    { title: '模型 / Token', key: 'model', width: 190, render: (_, span) => <span>{span.modelName || span.modelId || span.provider || '-'}{span.totalTokens != null ? ` · ${span.totalTokens} tokens` : ''}</span> },
  ];

  const totalMessagesOnPage = useMemo(
    () => records.reduce((sum, item) => sum + (item.messageCount || 0), 0),
    [records],
  );

  const columns: ColumnsType<ConversationRecord> = [
    {
      title: '会话 ID',
      dataIndex: 'conversationId',
      width: 205,
      ellipsis: true,
      render: (value: string) => (
        <Tooltip title={value}>
          <Text code copyable>{value || '-'}</Text>
        </Tooltip>
      ),
    },
    {
      title: '会话名称',
      dataIndex: 'name',
      width: 170,
      ellipsis: true,
      render: (value?: string) => value || <Text type="secondary">未命名会话</Text>,
    },
    { title: '应用 ID', dataIndex: 'appId', width: 130, render: (value?: string) => value || '-' },
    { title: '用户 ID', dataIndex: 'userId', width: 150, ellipsis: true, render: (value?: string) => value || '-' },
    { title: '调用来源', dataIndex: 'invokeSource', width: 125, render: (value?: string) => value || '-' },
    { title: '消息数', dataIndex: 'messageCount', width: 90, align: 'right', render: (value?: number) => value || 0 },
    { title: '创建时间', dataIndex: 'createdAt', width: 175, render: formatTime },
    { title: '最近更新', dataIndex: 'updatedAt', width: 175, render: formatTime },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: statusTag,
    },
    {
      title: '操作',
      width: 115,
      fixed: 'right',
      render: (_, record) => (
        <Button type="link" icon={<EyeOutlined />} onClick={() => openMessages(record)}>
          查看消息
        </Button>
      ),
    },
  ];

  const apiKeySelectOptions = apiKeyOptions.map((item) => ({
    value: item.api_key,
    label: `${item.description || '未命名 API Key'}${item.companyName ? ` · ${item.companyName}` : ''} · ${item.api_key}`,
    description: item.description || '未命名 API Key',
    companyName: item.companyName || '',
    apiKey: item.api_key,
  }));

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <Title level={3} className={styles.title}>会话历史</Title>
          <Text type="secondary">查看工作流 API 调用产生的会话记录、用户问题和助手回答</Text>
        </div>
        <Button
          icon={<ReloadOutlined />}
          onClick={() => {
            void loadApiKeyOptions();
            if (apiKey) void loadConversations(current, size, apiKey);
            else message.info('已刷新 API Key 列表，请选择一个密钥');
          }}
        >
          刷新
        </Button>
      </div>

      <Card className={styles.authCard}>
        <Row gutter={[12, 12]} align="middle">
          <Col flex="auto">
            <Space className={styles.keyLabel}>
              <KeyOutlined />
              <Text strong>OpenAPI Key</Text>
            </Space>
            <Select
              showSearch
              allowClear
              value={apiKeyInput || undefined}
              loading={apiKeyOptionsLoading}
              placeholder="请选择 API Key（按名称或密钥搜索）"
              className={styles.keyInput}
              style={{ width: '100%', maxWidth: 760 }}
              options={apiKeySelectOptions}
              optionFilterProp="label"
              filterOption={(input, option) =>
                String(option?.label || '').toLowerCase().includes(input.toLowerCase())
              }
              optionRender={(option) => (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 2, padding: '2px 0' }}>
                  <Text strong>名称：{option.data.description || '未命名 API Key'}{option.data.companyName ? ` · ${option.data.companyName}` : ''}</Text>
                  <Text type="secondary" code>{option.data.apiKey}</Text>
                </div>
              )}
              onChange={(value) => handleApiKeyChange(value || undefined)}
              notFoundContent={apiKeyOptionsLoading ? <Spin size="small" /> : '当前账号暂无可用 API Key'}
            />
            <Text type="secondary" className={styles.keyHelp}>
              已自动读取当前账号的 API Key；选择后立即加载会话。密钥仅保存在当前浏览器标签页的会话存储中。
            </Text>
          </Col>
          <Col>
            <Space>
              <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={activateApiKey}>
                {apiKey ? '重新加载' : '加载会话'}
              </Button>
              <Button onClick={clearApiKey}>清除密钥</Button>
            </Space>
          </Col>
        </Row>
      </Card>

      <Row gutter={16} className={styles.metrics}>
        <Col xs={24} sm={12} md={8}>
          <Card><Statistic title="符合条件的会话" value={total} prefix={<MessageOutlined />} /></Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card><Statistic title="当前页消息总数" value={totalMessagesOnPage} prefix={<UserOutlined />} /></Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card><Statistic title="当前页会话" value={records.length} prefix={<ClockCircleOutlined />} /></Card>
        </Col>
      </Row>

      <Card className={styles.filterCard}>
        <Form form={form} layout="inline" onFinish={search}>
          <Form.Item name="conversationId">
            <Input allowClear placeholder="会话 ID" style={{ width: 190 }} />
          </Form.Item>
          <Form.Item name="appId">
            <Input allowClear placeholder="应用 ID" style={{ width: 145 }} />
          </Form.Item>
          <Form.Item name="userId">
            <Input allowClear placeholder="用户 ID" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="name">
            <Input allowClear placeholder="会话名称" style={{ width: 165 }} />
          </Form.Item>
          <Form.Item name="invokeSource">
            <Input allowClear placeholder="调用来源" style={{ width: 145 }} />
          </Form.Item>
          <Form.Item name="timeRange">
            <RangePicker showTime style={{ width: 345 }} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" icon={<SearchOutlined/>}>查询</Button>
              <Button onClick={resetFilters}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card className={styles.tableCard} bodyStyle={{ padding: 0 }}>
        {!apiKey ? (
          <div className={styles.emptyState}>
            <Empty description="请先选择 API Key，再加载会话历史" />
          </div>
        ) : (
          <Table
            rowKey="conversationId"
            loading={loading}
            columns={columns}
            dataSource={records}
            scroll={{ x: 1450 }}
            onRow={(record) => ({ onDoubleClick: () => openMessages(record) })}
            pagination={{
              current,
              pageSize: size,
              total,
              showSizeChanger: true,
              showQuickJumper: true,
              showTotal: (count) => `共 ${count} 条会话`,
              onChange: (page, pageSize) => {
                setCurrent(page);
                setSize(pageSize);
                void loadConversations(page, pageSize, apiKey);
              },
            }}
          />
        )}
      </Card>

      <Drawer
        title={(
          <Space>
            <MessageOutlined />
            <span>会话消息详情</span>
            {selectedConversation?.status && statusTag(selectedConversation.status)}
          </Space>
        )}
        width="88vw"
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        destroyOnClose
      >
        {!selectedConversation ? <Empty /> : (
          <Spin spinning={messagesLoading}>
            <Descriptions bordered size="small" column={3} className={styles.conversationInfo}>
              <Descriptions.Item label="会话 ID" span={2}>
                <Text copyable code>{selectedConversation.conversationId}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="应用 ID">{selectedConversation.appId || '-'}</Descriptions.Item>
              <Descriptions.Item label="会话名称">{selectedConversation.name || '未命名会话'}</Descriptions.Item>
              <Descriptions.Item label="用户 ID">{selectedConversation.userId || '-'}</Descriptions.Item>
              <Descriptions.Item label="调用来源">{selectedConversation.invokeSource || '-'}</Descriptions.Item>
              <Descriptions.Item label="消息数">{selectedConversation.messageCount ?? 0}</Descriptions.Item>
              <Descriptions.Item label="创建时间">{formatTime(selectedConversation.createdAt)}</Descriptions.Item>
              <Descriptions.Item label="最近更新">{formatTime(selectedConversation.updatedAt)}</Descriptions.Item>
            </Descriptions>

            <div className={styles.messagesHeader}>
              <Title level={5}>历史问答</Title>
              <Text type="secondary">共 {messageTotal} 条记录</Text>
            </div>

            {messages.length === 0 && !messagesLoading ? (
              <Empty description="当前会话暂无消息记录" />
            ) : (
              <div className={styles.messageList}>
                {messages.map((item, index) => {
                  const recordKey = String(item.messageId || `${item.createdAt || 'message'}-${index}`);
                  const isRecordExpanded = expandedMessageCards[recordKey] ?? true;
                  const questionKey = `${recordKey}:question`;
                  const answerKey = `${recordKey}:answer`;
                  const isQuestionExpanded = expandedMessageParts[questionKey] ?? true;
                  const isAnswerExpanded = expandedMessageParts[answerKey] ?? true;
                  return (
                  <Card
                    key={recordKey}
                    size="small"
                    className={styles.messageCard}
                    title={(
                      <Space wrap>
                        <Text strong>第 {(messageCurrent - 1) * messageSize + index + 1} 条记录</Text>
                        {statusTag(item.status)}
                        <Text type="secondary">{formatTime(item.createdAt)}</Text>
                      </Space>
                    )}
                    extra={(
                      <Space size={8} wrap>
                        {item.messageId ? <Text type="secondary" code>#{item.messageId}</Text> : null}
                        <Tooltip title={item.traceId ? '在右侧面板查看该消息对应的 Workflow Trace' : '该消息暂无关联的 Trace ID'}>
                          <Button
                            type="link"
                            size="small"
                            icon={<ApartmentOutlined />}
                            disabled={!item.traceId}
                            onClick={() => void openTraceDetail(item.traceId)}
                          >
                            查看执行链路
                          </Button>
                        </Tooltip>
                        <Button
                          type="text"
                          size="small"
                          icon={isRecordExpanded ? <UpOutlined /> : <DownOutlined />}
                          onClick={() => setExpandedMessageCards(previous => ({ ...previous, [recordKey]: !isRecordExpanded }))}
                        >
                          {isRecordExpanded ? '收起整条记录' : '展开整条记录'}
                        </Button>
                      </Space>
                    )}
                  >
                    {isRecordExpanded && <>
                    <div className={`${styles.messageBubble} ${styles.questionBubble}`}>
                      <div className={styles.messageSectionHeader}>
                        <Text strong>用户问题</Text>
                        <Button
                          type="text"
                          size="small"
                          icon={isQuestionExpanded ? <UpOutlined /> : <DownOutlined />}
                          onClick={() => setExpandedMessageParts(previous => ({ ...previous, [questionKey]: !isQuestionExpanded }))}
                        >
                          {isQuestionExpanded ? '收起' : '展开'}
                        </Button>
                      </div>
                      {isQuestionExpanded && (
                        <Paragraph className={styles.messageContent} copyable={{ text: item.question || '' }}>
                          {item.question || '（接口未返回问题内容）'}
                        </Paragraph>
                      )}
                    </div>
                    <div className={`${styles.messageBubble} ${styles.answerBubble}`}>
                      <div className={styles.messageSectionHeader}>
                        <Text strong>助手回答</Text>
                        <Button
                          type="text"
                          size="small"
                          icon={isAnswerExpanded ? <UpOutlined /> : <DownOutlined />}
                          onClick={() => setExpandedMessageParts(previous => ({ ...previous, [answerKey]: !isAnswerExpanded }))}
                        >
                          {isAnswerExpanded ? '收起' : '展开'}
                        </Button>
                      </div>
                      {isAnswerExpanded && (
                        <Paragraph className={styles.messageContent} copyable={{ text: item.answer || '' }}>
                          {item.answer || '（接口未返回回答内容）'}
                        </Paragraph>
                      )}
                    </div>
                    <Divider className={styles.messageDivider} />
                    <Row gutter={[16, 8]} className={styles.messageMeta}>
                      <Col xs={24} md={8}>
                        <Text type="secondary">Request ID：</Text>
                        {item.requestId ? <Text copyable code>{item.requestId}</Text> : '-'}
                      </Col>
                      <Col xs={24} md={8}>
                        <Text type="secondary">Trace ID：</Text>
                        {item.traceId ? <Text copyable code>{item.traceId}</Text> : '-'}
                      </Col>
                      <Col xs={24} md={8}>
                        <Text type="secondary">用户 ID：</Text>{item.userId || selectedConversation.userId || '-'}
                      </Col>
                    </Row>
                    </>}
                  </Card>
                  );
                })}
              </div>
            )}

            {messageTotal > 0 && (
              <div className={styles.messagePagination}>
                <Pagination
                  current={messageCurrent}
                  pageSize={messageSize}
                  total={messageTotal}
                  showSizeChanger
                  showQuickJumper
                  showTotal={(count) => `共 ${count} 条消息记录`}
                  onChange={(page, pageSize) => {
                    setMessageCurrent(page);
                    setMessageSize(pageSize);
                    void loadMessages(selectedConversation, page, pageSize, apiKey);
                  }}
                />
              </div>
            )}
          </Spin>
        )}
      </Drawer>

      <Drawer
        title={(
          <Space>
            <ApartmentOutlined />
            <span>Workflow Trace 详情</span>
            {traceDetail?.trace.status ? statusTag(traceDetail.trace.status) : null}
          </Space>
        )}
        placement="right"
        width="76vw"
        zIndex={1200}
        open={traceDrawerOpen}
        onClose={() => setTraceDrawerOpen(false)}
        destroyOnClose
      >
        <Spin spinning={traceDetailLoading}>
          {!traceDetail ? (traceDetailLoading ? null : <Empty description="暂无 Trace 详情" />) : <>
            <Descriptions bordered size="small" column={3} style={{ marginBottom: 16 }}>
              <Descriptions.Item label="Trace ID" span={2}><Text copyable code>{traceDetail.trace.traceId}</Text></Descriptions.Item>
              <Descriptions.Item label="Task ID"><Text copyable>{traceDetail.trace.taskId || '-'}</Text></Descriptions.Item>
              <Descriptions.Item label="App ID">{traceDetail.trace.appId || '-'}</Descriptions.Item>
              <Descriptions.Item label="User ID">{traceDetail.trace.userId || '-'}</Descriptions.Item>
              <Descriptions.Item label="版本">{traceDetail.trace.workflowVersion || '-'}</Descriptions.Item>
              <Descriptions.Item label="Conversation ID">{traceDetail.trace.conversationId || '-'}</Descriptions.Item>
              <Descriptions.Item label="调用来源">{traceDetail.trace.invokeSource || '-'}</Descriptions.Item>
              <Descriptions.Item label="结束原因">{traceDetail.trace.finishReason || '-'}</Descriptions.Item>
              <Descriptions.Item label="总耗时">{traceDuration(traceDetail.trace.durationMs)}</Descriptions.Item>
              <Descriptions.Item label="Prompt Token">{traceDetail.trace.promptTokens ?? 0}</Descriptions.Item>
              <Descriptions.Item label="Completion Token">{traceDetail.trace.completionTokens ?? 0}</Descriptions.Item>
              <Descriptions.Item label="Total Token">{traceDetail.trace.totalTokens ?? 0}</Descriptions.Item>
            </Descriptions>

            <Card size="small" title="整个流程输入 / 输出" style={{ marginBottom: 14 }}
              extra={<Button type="text" size="small" icon={traceWorkflowIOExpanded ? <UpOutlined /> : <DownOutlined />} onClick={() => setTraceWorkflowIOExpanded(value => !value)}>{traceWorkflowIOExpanded ? '收起' : '展开'}</Button>}>
              {traceWorkflowIOExpanded && <Row gutter={[12, 12]}>
                <Col xs={24} xl={12}><Card size="small" title="流程输入" extra={<Button type="link" size="small" onClick={() => { if (traceDetail.trace.inputData) navigator.clipboard?.writeText(traceJson(traceDetail.trace.inputData)).then(() => message.success('已复制')).catch(() => message.error('复制失败')); }}>复制</Button>}>
                  <pre style={{ maxHeight: 220, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0 }}>{traceJson(traceDetail.trace.inputData)}</pre>
                </Card></Col>
                <Col xs={24} xl={12}><Card size="small" title="流程输出" extra={<Button type="link" size="small" onClick={() => { if (traceDetail.trace.outputData) navigator.clipboard?.writeText(traceJson(traceDetail.trace.outputData)).then(() => message.success('已复制')).catch(() => message.error('复制失败')); }}>复制</Button>}>
                  <pre style={{ maxHeight: 220, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0 }}>{traceJson(traceDetail.trace.outputData)}</pre>
                </Card></Col>
              </Row>}
            </Card>

            <Card size="small" title="会话变量变化" style={{ marginBottom: 14 }}
              extra={<Space><Button type="link" size="small" onClick={() => setShowUnchangedTraceVariables(value => !value)}>{showUnchangedTraceVariables ? '隐藏未变化' : '显示未变化'}</Button><Button type="text" size="small" icon={traceVariablesExpanded ? <UpOutlined /> : <DownOutlined />} onClick={() => setTraceVariablesExpanded(value => !value)}>{traceVariablesExpanded ? '收起' : '展开'}</Button></Space>}>
              {traceVariablesExpanded && (!traceSessionSnapshot.before || !traceSessionSnapshot.after ? <Empty description="当前 Trace 未记录完整会话变量快照" /> : <>
                <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>执行前 {Object.keys(traceSessionSnapshot.before).length} 项 · 执行后 {Object.keys(traceSessionSnapshot.after).length} 项 · 变化 {traceVariableChanges.filter(item => item.change !== '未变化').length} 项</Text>
                <Table<TraceVariableChange> rowKey="key" size="small" pagination={false} columns={traceVariableColumns} dataSource={traceVariableChanges} scroll={{ x: 650 }} />
                <Row gutter={12} style={{ marginTop: 10 }}>
                  <Col xs={24} xl={12}><details><summary>执行前完整会话变量</summary><pre style={{ maxHeight: 200, overflow: 'auto', whiteSpace: 'pre-wrap' }}>{JSON.stringify(traceSessionSnapshot.before, null, 2)}</pre></details></Col>
                  <Col xs={24} xl={12}><details><summary>执行后完整会话变量</summary><pre style={{ maxHeight: 200, overflow: 'auto', whiteSpace: 'pre-wrap' }}>{JSON.stringify(traceSessionSnapshot.after, null, 2)}</pre></details></Col>
                </Row>
              </>)}
            </Card>

            {traceDetail.trace.errorMessage ? <Card size="small" style={{ marginBottom: 14, borderColor: '#ffccc7', background: '#fff2f0' }}><Text type="danger">{traceDetail.trace.errorCode ? `[${traceDetail.trace.errorCode}] ` : ''}{traceDetail.trace.errorMessage}</Text></Card> : null}

            <Card size="small" title={`执行链路（${traceDetail.spans?.length || 0} spans）`} style={{ marginBottom: 14 }}>
              <Table<WorkflowSpan> rowKey="spanId" size="small" columns={traceSpanColumns} dataSource={[...(traceDetail.spans || [])].sort((a, b) => (a.sequenceNo || 0) - (b.sequenceNo || 0))} pagination={false} scroll={{ x: 700, y: 260 }} onRow={span => ({ onClick: () => setSelectedTraceSpan(span), style: { cursor: 'pointer', background: selectedTraceSpan?.spanId === span.spanId ? '#e6f4ff' : undefined } })} />
            </Card>

            <Card size="small" title="Span 详情">
              {!selectedTraceSpan ? <Empty description="当前 Trace 没有可展示的 Span" /> : <>
                <Descriptions bordered size="small" column={2} style={{ marginBottom: 12 }}>
                  <Descriptions.Item label="Span ID" span={2}><Text copyable code>{selectedTraceSpan.spanId}</Text></Descriptions.Item>
                  <Descriptions.Item label="类型">{selectedTraceSpan.spanKind || '-'}</Descriptions.Item>
                  <Descriptions.Item label="状态">{statusTag(selectedTraceSpan.status)}</Descriptions.Item>
                  <Descriptions.Item label="节点">{selectedTraceSpan.nodeName || selectedTraceSpan.nodeId || '-'}</Descriptions.Item>
                  <Descriptions.Item label="节点类型">{selectedTraceSpan.nodeType || '-'}</Descriptions.Item>
                  <Descriptions.Item label="模型">{selectedTraceSpan.modelName || selectedTraceSpan.modelId || selectedTraceSpan.provider || '-'}</Descriptions.Item>
                  <Descriptions.Item label="耗时">{traceDuration(selectedTraceSpan.durationMs)}</Descriptions.Item>
                  <Descriptions.Item label="Token" span={2}>{selectedTraceSpan.totalTokens == null ? '-' : `${selectedTraceSpan.promptTokens || 0} + ${selectedTraceSpan.completionTokens || 0} = ${selectedTraceSpan.totalTokens}`}</Descriptions.Item>
                </Descriptions>
                {selectedTraceSpan.errorMessage ? <Text type="danger">{selectedTraceSpan.errorCode ? `[${selectedTraceSpan.errorCode}] ` : ''}{selectedTraceSpan.errorMessage}</Text> : null}
                <Row gutter={[12, 12]} style={{ marginTop: 10 }}>
                  <Col xs={24} xl={12}><Card size="small" title="Input（节点输入）"><pre style={{ maxHeight: 240, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0 }}>{traceJson(selectedTraceSpan.inputData)}</pre></Card></Col>
                  <Col xs={24} xl={12}><Card size="small" title="Output（节点输出）"><pre style={{ maxHeight: 240, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0 }}>{traceJson(selectedTraceSpan.outputData)}</pre></Card></Col>
                </Row>
                {selectedTraceSpan.spanData ? <details style={{ marginTop: 12 }}><summary>原始 Span Data（调试用）</summary><pre style={{ maxHeight: 200, overflow: 'auto', whiteSpace: 'pre-wrap' }}>{traceJson(selectedTraceSpan.spanData)}</pre></details> : null}
              </>}
            </Card>
          </>}
        </Spin>
      </Drawer>
    </div>
  );
}
