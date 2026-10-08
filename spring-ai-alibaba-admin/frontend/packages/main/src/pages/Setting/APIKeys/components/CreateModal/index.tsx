import { createApiKey } from '@/services/apiKey';
import type { IApiKeyResource } from '@/types/apiKey';
import { Form, Input, message, Modal } from 'antd';
import React, { useEffect, useState } from 'react';
import ScopeFields from '../ScopeFields';

interface CreateModalProps {
  open: boolean;
  onCancel: () => void;
  onSuccess: () => void;
}

const CreateModal: React.FC<CreateModalProps> = ({ open, onCancel, onSuccess }) => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (open) {
      form.setFieldsValue({ scopeType: 'ALL', folderIds: [], appIds: [] });
    }
  }, [open, form]);

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setLoading(true);
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

      const res = await createApiKey({
        companyName: values.companyName,
        description: values.description,
        scopeType: values.scopeType,
        resources,
      });
      if (res?.data) {
        message.success('创建成功');
        form.resetFields();
        onSuccess();
      }
    } catch (error: any) {
      if (error?.errorFields) return;
    } finally {
      setLoading(false);
    }
  };

  const handleCancel = () => {
    form.resetFields();
    onCancel();
  };

  return (
    <Modal
      title="创建 API KEY"
      open={open}
      onCancel={handleCancel}
      onOk={handleSubmit}
      confirmLoading={loading}
      maskClosable={false}
      width={680}
      destroyOnClose
    >
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
    </Modal>
  );
};

export default CreateModal;
