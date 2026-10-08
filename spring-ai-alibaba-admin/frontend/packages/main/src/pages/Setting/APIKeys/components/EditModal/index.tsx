import { getApiKey, updateApiKey } from '@/services/apiKey';
import type { IApiKey, IApiKeyResource } from '@/types/apiKey';
import { Form, Input, message, Modal, Spin } from 'antd';
import React, { useEffect, useState } from 'react';
import ScopeFields from '../ScopeFields';

interface EditModalProps {
  open: boolean;
  record: IApiKey | null;
  onCancel: () => void;
  onSuccess: () => void;
}

const EditModal: React.FC<EditModalProps> = ({ open, record, onCancel, onSuccess }) => {
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => {
    if (!open || record?.id == null) return;
    let cancelled = false;
    setDetailLoading(true);
    getApiKey(record.id)
      .then((res) => {
        if (cancelled) return;
        const detail = res?.data || record;
        const resources = detail.resources || [];
        form.setFieldsValue({
          companyName: detail.companyName || '',
          description: detail.description || '',
          scopeType: detail.scopeType || 'ALL',
          folderIds: resources.filter((item) => item.type === 'FOLDER').map((item) => item.id),
          appIds: resources.filter((item) => item.type === 'APP').map((item) => item.id),
        });
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, record, form]);

  const handleSubmit = async () => {
    if (record?.id == null) return;
    try {
      const values = await form.validateFields();
      setSaving(true);
      const resources: IApiKeyResource[] =
        values.scopeType === 'CUSTOM'
          ? [
              ...(values.folderIds || []).map((id: string) => ({
                type: 'FOLDER' as const,
                id,
              })),
              ...(values.appIds || []).map((id: string) => ({
                type: 'APP' as const,
                id,
              })),
            ]
          : [];

      await updateApiKey(record.id, {
        companyName: values.companyName,
        description: values.description,
        scopeType: values.scopeType,
        resources,
      });
      message.success('保存成功');
      form.resetFields();
      onSuccess();
    } catch (error: any) {
      if (error?.errorFields) return;
    } finally {
      setSaving(false);
    }
  };

  const handleCancel = () => {
    form.resetFields();
    onCancel();
  };

  return (
    <Modal
      title="编辑 API KEY"
      open={open}
      onCancel={handleCancel}
      onOk={handleSubmit}
      confirmLoading={saving || detailLoading}
      maskClosable={false}
      width={680}
      destroyOnClose
    >
      <Spin spinning={detailLoading}>
        <Form form={form} layout="vertical">
          <Form.Item
            name="companyName"
            label="企业名称"
            rules={[{ max: 200, message: '企业名称最多 200 个字符' }]}
          >
            <Input placeholder="请输入企业名称（可选）" maxLength={200} />
          </Form.Item>
          <Form.Item
            name="description"
            label="描述"
            rules={[{ required: true, whitespace: true, message: '请输入 API KEY 描述' }]}
          >
            <Input.TextArea placeholder="请输入 API KEY 描述" rows={3} />
          </Form.Item>
          <ScopeFields open={open} form={form} />
        </Form>
      </Spin>
    </Modal>
  );
};

export default EditModal;
