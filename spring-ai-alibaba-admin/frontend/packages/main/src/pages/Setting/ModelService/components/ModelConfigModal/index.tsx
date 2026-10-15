import { listAvailableModels } from '@/services/modelService';
import { IAvailableModel, ICreateModelParams, IModel } from '@/types/modelService';
import { Button, Input, Modal, Select, message } from '@spark-ai/design';
import { Spin } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import styles from './index.module.less';

interface ModelConfigModalProps {
  open: boolean;
  providerId: string;
  existingModelIds: string[];
  onCancel: () => void;
  onOk: (models: ICreateModelParams[]) => Promise<void> | void;
  model?: IModel;
  title?: string;
}

const ModelConfigModal: React.FC<ModelConfigModalProps> = ({
  open,
  providerId,
  existingModelIds,
  onCancel,
  onOk,
  model,
  title,
}) => {
  const isEdit = !!model?.model_id;
  const [availableModels, setAvailableModels] = useState<IAvailableModel[]>([]);
  const [selectedModels, setSelectedModels] = useState<string[]>([]);
  const [name, setName] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState('');
  const existingIds = useMemo(() => new Set(existingModelIds), [existingModelIds]);

  const fetchAvailableModels = useCallback(() => {
    if (!providerId) return;
    setLoading(true);
    setLoadError('');
    listAvailableModels(providerId)
      .then((response) => setAvailableModels(response?.data || []))
      .catch((error) => setLoadError(error?.response?.data?.message || error?.message || '获取供应商模型列表失败'))
      .finally(() => setLoading(false));
  }, [providerId]);

  useEffect(() => {
    if (!open) return;
    setName(model?.name || '');
    setSelectedModels([]);
    if (model || !providerId) return;
    fetchAvailableModels();
  }, [open, model, providerId, fetchAvailableModels]);

  const handleSubmit = async () => {
    if (isEdit && model) {
      if (!name.trim()) return;
      setSaving(true);
      try {
        await onOk([{ ...model, name: name.trim(), model_id: model.model_id, tags: model.tags || [] }]);
      } finally {
        setSaving(false);
      }
      return;
    }
    if (!selectedModels.length) {
      message.warning('请至少选择一个模型');
      return;
    }
    setSaving(true);
    try {
      await onOk(selectedModels.map((modelId) => {
        const item = availableModels.find((model) => model.model_id === modelId);
        const id = modelId.toLowerCase();
        const type = item?.tags.includes('embedding') ? 'text_embedding'
          : id.includes('rerank') ? 'rerank'
            : /(^|[-_])(tts|speech)([-_]|$)/.test(id) ? 'tts'
              : /whisper|(^|[-_])(asr|stt)([-_]|$)/.test(id) ? 'stt' : 'llm';
        return {
          name: modelId,
          model_id: modelId,
          type,
          tags: item?.tags || [],
        };
      }));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title={title || (isEdit ? '编辑模型' : '新增模型')}
      open={open}
      onCancel={onCancel}
      footer={[
        <Button key="cancel" onClick={onCancel}>取消</Button>,
        <Button key="submit" type="primary" loading={saving} disabled={loading} onClick={handleSubmit}>
          {isEdit ? '确认' : `添加所选模型${selectedModels.length ? `（${selectedModels.length}）` : ''}`}
        </Button>,
      ]}
      width={560}
      destroyOnClose
    >
      <div className={styles.container}>
        {isEdit ? (
          <div className={styles['edit-field']}>
            <label>模型名称</label>
            <Input value={name} maxLength={50} onChange={(event) => setName(event.target.value)} />
          </div>
        ) : (
          <>
            <p className={styles.hint}>从供应商账号可用模型中勾选，模型能力将根据供应商返回信息自动设置。</p>
            {loadError && <div className={styles.error}>{loadError}</div>}
            <Spin spinning={loading}>
              <Select
                mode="multiple"
                showSearch
                value={selectedModels}
                onChange={setSelectedModels}
                placeholder="选择一个或多个模型"
                options={availableModels.map((item) => ({
                  label: existingIds.has(item.model_id) ? `${item.model_id}（已添加）` : item.model_id,
                  value: item.model_id,
                  disabled: existingIds.has(item.model_id),
                }))}
                filterOption={(input, option) => String(option?.label || '').toLowerCase().includes(input.toLowerCase())}
                notFoundContent={loading ? '加载中...' : '没有可添加的模型'}
                style={{ width: '100%' }}
              />
            </Spin>
            {loadError && <Button type="link" onClick={fetchAvailableModels}>重试</Button>}
          </>
        )}
      </div>
    </Modal>
  );
};

export default ModelConfigModal;
