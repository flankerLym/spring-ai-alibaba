import InnerLayout from '@/components/InnerLayout';
import $i18n from '@/i18n';
import { deleteApiKey, getApiKey, listApiKeys } from '@/services/apiKey';
import type { IApiKey } from '@/types/apiKey';
import { AlertDialog, Button, IconFont, message, Pagination } from '@spark-ai/design';
import { Table, Tag } from 'antd';
import type { TableProps } from 'antd';
import copy from 'copy-to-clipboard';
import dayjs from 'dayjs';
import { useEffect, useState } from 'react';
import CreateModal from './components/CreateModal';
import EditModal from './components/EditModal';
import styles from './index.module.less';

export default function APIKeys() {
  const [loading, setLoading] = useState(false);
  const [apiKeys, setApiKeys] = useState<IApiKey[]>([]);
  const [total, setTotal] = useState(0);
  const [current, setCurrent] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [editingKey, setEditingKey] = useState<IApiKey | null>(null);
  const [visibleKeys, setVisibleKeys] = useState<Record<string, string>>({});

  const fetchApiKeys = async (page = current, size = pageSize) => {
    setLoading(true);
    try {
      const res = await listApiKeys({ current: page, size });
      if (res.data) {
        setApiKeys(res.data.records || []);
        setTotal(res.data.total || 0);
        setCurrent(res.data.current || page);
        setPageSize(res.data.size || size);
        setVisibleKeys({});
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchApiKeys(1, 10);
  }, []);

  const handleDeleteApiKey = (id: number | string) => {
    AlertDialog.warning({
      title: '删除 API KEY',
      children: '删除后将无法使用该 API KEY，确定要删除吗？',
      onOk: async () => {
        await deleteApiKey(id);
        message.success('删除成功');
        const previousPage = apiKeys.length === 1 && current > 1 ? current - 1 : current;
        await fetchApiKeys(previousPage, pageSize);
      },
    });
  };

  const showApiKey = async (id: number | string) => {
    const res = await getApiKey(id);
    if (res?.data?.api_key) {
      setVisibleKeys((previous) => ({
        ...previous,
        [String(id)]: res.data.api_key as string,
      }));
    }
  };

  const columns: TableProps<IApiKey>['columns'] = [
    {
      title: 'API KEY',
      dataIndex: 'api_key',
      key: 'api_key',
      width: 250,
      render: (value: string, record) => (
        <div className={styles['api-key-cell']}>
          <span className={styles['api-key-text']}>
            {record.id != null && visibleKeys[String(record.id)]
              ? visibleKeys[String(record.id)]
              : value || '--'}
          </span>
        </div>
      ),
    },
    {
      title: '企业名称',
      dataIndex: 'companyName',
      key: 'companyName',
      width: 170,
      render: (value?: string) => value || '--',
    },
    {
      title: '访问范围',
      dataIndex: 'scopeType',
      key: 'scopeType',
      width: 130,
      render: (value?: string) =>
        value === 'CUSTOM' ? <Tag>指定范围</Tag> : <Tag color="blue">全部应用</Tag>,
    },
    {
      title: $i18n.get({
        id: 'main.pages.Setting.APIKeys.index.description',
        dm: '描述',
      }),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (value?: string) => value || '--',
    },
    {
      title: '创建时间',
      dataIndex: 'gmt_create',
      key: 'gmt_create',
      width: 170,
      render: (value?: string) => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '--',
    },
    {
      title: '操作',
      key: 'action',
      width: 240,
      render: (_, record) => {
        if (record.id == null) return null;
        const id = String(record.id);
        return (
          <div className={styles['action-column']}>
            {visibleKeys[id] ? (
              <>
                <Button type="link" onClick={() => {
                  copy(visibleKeys[id]);
                  message.success('复制成功');
                }}>复制</Button>
                <Button type="link" onClick={() => {
                  setVisibleKeys((prev) => {
                    const next = { ...prev };
                    delete next[id];
                    return next;
                  });
                }}>隐藏</Button>
              </>
            ) : (
              <Button type="link" onClick={() => showApiKey(id)}>查看</Button>
            )}
            <Button type="link" onClick={() => setEditingKey(record)}>编辑</Button>
            <Button type="link" onClick={() => handleDeleteApiKey(id)}>删除</Button>
          </div>
        );
      },
    },
  ];

  return (
    <InnerLayout
      breadcrumbLinks={[
        { title: '首页', path: '/' },
        { title: '权限管理', path: '/permission' },
        { title: 'API KEY 管理' },
      ]}
      left={`共 ${total} 个 API KEY`}
      right={
        <Button
          type="primary"
          icon={<IconFont type="spark-plus-line" />}
          onClick={() => setIsCreateModalOpen(true)}
        >
          新增 API KEY
        </Button>
      }
      bottom={
        <div className={styles.pagination}>
          <Pagination
            hideTips
            current={current}
            pageSize={pageSize}
            total={total}
            onChange={(page, size) => fetchApiKeys(page, size)}
          />
        </div>
      }
    >
      <div className={styles.container}>
        <Table
          loading={loading}
          columns={columns}
          dataSource={apiKeys}
          rowKey="id"
          pagination={false}
          scroll={{ x: 1200 }}
        />
      </div>
      <CreateModal
        open={isCreateModalOpen}
        onCancel={() => setIsCreateModalOpen(false)}
        onSuccess={() => {
          setIsCreateModalOpen(false);
          fetchApiKeys(1, pageSize);
        }}
      />
      <EditModal
        open={!!editingKey}
        record={editingKey}
        onCancel={() => setEditingKey(null)}
        onSuccess={() => {
          setEditingKey(null);
          fetchApiKeys(current, pageSize);
        }}
      />
    </InnerLayout>
  );
}
