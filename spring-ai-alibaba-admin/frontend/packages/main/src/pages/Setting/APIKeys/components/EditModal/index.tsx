import { updateApiKey } from '@/services/apiKey';
import type { IApiKey } from '@/types/apiKey';
import { Form, Input, message, Modal } from '@spark-ai/design';
import React, { useEffect, useState } from 'react';

interface EditModalProps {
  open: boolean;
  record: IApiKey | null;
  onCancel: () => void;
  onSuccess: () => void;
}

const EditModal: React.FC<EditModalProps> = ({ open, record, onCancel, onSuccess }) => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (open && record) {
      form.setFieldsValue({
        companyName: record.companyName || '',
        description: record.description || '',
      });
    }
  }, [open, record, form]);

  const handleSubmit = async () => {
    if (record?.id == null) return;
    try {
      const values = await form.validateFields();
      setLoading(true);
      const res = await updateApiKey(record.id, values);
      if (res) {
        message.success('保存成功');
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
      title="编辑 API KEY"
      open={open}
      onCancel={handleCancel}
      onOk={handleSubmit}
      confirmLoading={loading}
      maskClosable={false}
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
          <Input.TextArea placeholder="请输入 API KEY 描述" rows={4} />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default EditModal;
