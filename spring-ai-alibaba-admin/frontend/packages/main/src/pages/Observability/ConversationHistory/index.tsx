import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ClockCircleOutlined,
  EyeOutlined,
  KeyOutlined,
  MessageOutlined,
  ReloadOutlined,
  SearchOutlined,
  UserOutlined,
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

export default function ConversationHistoryPage() {
  const [form] = Form.useForm<FilterForm>();
  const [apiKeyInput, setApiKeyInput] = useState(readApiKey);
  const [apiKey, setApiKey] = useState(readApiKey);
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
      message.warning('请先填写 API Key');
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
    if (apiKey) void loadConversations(1, 20, apiKey);
  }, [apiKey, loadConversations]);

  const activateApiKey = () => {
    const key = apiKeyInput.trim();
    if (!key) {
      message.warning('请先填写 API Key');
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
      message.warning('请先填写 API Key 并点击“加载会话”');
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

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <Title level={3} className={styles.title}>会话历史</Title>
          <Text type="secondary">查看工作流 API 调用产生的会话记录、用户问题和助手回答</Text>
        </div>
        <Button
          icon={<ReloadOutlined />}
          onClick={() => apiKey
            ? void loadConversations(current, size, apiKey)
            : message.warning('请先填写 API Key 并点击“加载会话”')}
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
            <Input.Password
              value={apiKeyInput}
              onChange={(event) => setApiKeyInput(event.target.value)}
              onPressEnter={activateApiKey}
              placeholder="输入有权访问会话数据的 API Key"
              autoComplete="off"
              className={styles.keyInput}
            />
            <Text type="secondary" className={styles.keyHelp}>
              密钥用于调用 /api/v1/conversations 接口，仅保存在当前浏览器标签页的会话存储中。
            </Text>
          </Col>
          <Col>
            <Space>
              <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={activateApiKey}>
                加载会话
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
              <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>查询</Button>
              <Button onClick={resetFilters}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card className={styles.tableCard} bodyStyle={{ padding: 0 }}>
        {!apiKey ? (
          <div className={styles.emptyState}>
            <Empty description="请先输入 API Key，再加载会话历史" />
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
                {messages.map((item, index) => (
                  <Card
                    key={item.messageId || `${item.createdAt || 'message'}-${index}`}
                    size="small"
                    className={styles.messageCard}
                    title={(
                      <Space wrap>
                        <Text strong>第 {(messageCurrent - 1) * messageSize + index + 1} 条记录</Text>
                        {statusTag(item.status)}
                        <Text type="secondary">{formatTime(item.createdAt)}</Text>
                      </Space>
                    )}
                    extra={item.messageId ? <Text type="secondary" code>#{item.messageId}</Text> : null}
                  >
                    <div className={`${styles.messageBubble} ${styles.questionBubble}`}>
                      <Text strong>用户问题</Text>
                      <Paragraph className={styles.messageContent} copyable={{ text: item.question || '' }}>
                        {item.question || '（接口未返回问题内容）'}
                      </Paragraph>
                    </div>
                    <div className={`${styles.messageBubble} ${styles.answerBubble}`}>
                      <Text strong>助手回答</Text>
                      <Paragraph className={styles.messageContent} copyable={{ text: item.answer || '' }}>
                        {item.answer || '（接口未返回回答内容）'}
                      </Paragraph>
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
                  </Card>
                ))}
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
    </div>
  );
}
