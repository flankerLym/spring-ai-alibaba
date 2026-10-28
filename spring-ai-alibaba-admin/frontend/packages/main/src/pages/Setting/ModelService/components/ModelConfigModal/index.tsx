import { listAvailableModels } from '@/services/modelService';
import { IAvailableModel, ICreateModelParams, IModel } from '@/types/modelService';
import { Button, Input, Modal, Select, message } from '@spark-ai/design';
import { Spin } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import styles from './index.module.less';

interface ModelConfigModalProps {
  open: boolean;
  providerId: string;
  providerProtocol?: string;
  existingModelIds: string[];
  onCancel: () => void;
  onOk: (models: ICreateModelParams[]) => Promise<void> | void;
  model?: IModel;
  title?: string;
}

const ModelConfigModal: React.FC<ModelConfigModalProps> = ({
  open,
  providerId,
  providerProtocol,
  existingModelIds,
  onCancel,
  onOk,
  model,
  title,
}) => {
  const isEdit = !!model?.model_id;
  const [availableModels, setAvailableModels] = useState<IAvailableModel[]>([]);
  const [selectedModels, setSelectedModels] = useState<string[]>([]);
  const [manualModelId, setManualModelId] = useState('');
  const [name, setName] = useState('');
  const [endpointId, setEndpointId] = useState('');
  const [endpointIds, setEndpointIds] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState('');
  const existingIds = useMemo(() => new Set(existingModelIds), [existingModelIds]);
  const needsEndpointId = providerProtocol?.toLowerCase() === 'doubao';

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
    setEndpointId(model?.endpoint_id || '');
    setEndpointIds({});
    setSelectedModels([]);
    setManualModelId('');
    if (model || !providerId) return;
    fetchAvailableModels();
  }, [open, model, providerId, fetchAvailableModels]);

  const handleSubmit = async () => {
    if (isEdit && model) {
      if (!name.trim()) return;
      if (needsEndpointId && !endpointId.trim()) {
        message.warning('请填写火山方舟接入点 ID');
        return;
      }
      setSaving(true);
      try {
        await onOk([{ ...model, name: name.trim(), model_id: model.model_id, tags: model.tags || [], endpoint_id: needsEndpointId ? endpointId.trim() : model.endpoint_id }]);
      } finally {
        setSaving(false);
      }
      return;
    }
    if (!selectedModels.length) {
      message.warning('请至少选择一个模型');
      return;
    }
    if (needsEndpointId) {
      const missing = selectedModels.find((id) => !endpointIds[id]?.trim());
      if (missing) {
        message.warning(`请填写模型 ${missing} 的接入点 ID`);
        return;
      }
    }
    setSaving(true);
    try {
      await onOk(selectedModels.map((modelId) => {
        const item = availableModels.find((model) => model.model_id === modelId);
        const id = modelId.toLowerCase();
        const tags = item?.tags || inferTagsFromName(modelId);
        const type = item?.tags.includes('embedding') ? 'text_embedding'
          : id.includes('rerank') ? 'rerank'
            : /(^|[-_])(tts|speech)([-_]|$)/.test(id) ? 'tts'
              : /whisper|(^|[-_])(asr|stt)([-_]|$)/.test(id) ? 'stt' : 'llm';
        return {
          name: modelId,
          model_id: modelId,
          type,
          tags,
          endpoint_id: needsEndpointId ? endpointIds[modelId]?.trim() : undefined,
        };
      }));
    } finally {
      setSaving(false);
    }
  };

  const addManualModel = () => {
    const id = manualModelId.trim();
    if (!id) return;
    if (existingIds.has(id) || selectedModels.includes(id)) {
      message.warning('该模型已添加或已在选择列表中');
      return;
    }
    setAvailableModels((models) => [...models, { model_id: id, tags: inferTagsFromName(id) }]);
    setSelectedModels((models) => [...models, id]);
    setManualModelId('');
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
          <>
            <div className={styles['edit-field']}>
              <label>模型名称</label>
              <Input value={name} maxLength={50} onChange={(event) => setName(event.target.value)} />
            </div>
            {needsEndpointId && (
              <div className={styles['edit-field']}>
                <label>火山方舟接入点 ID</label>
                <Input value={endpointId} onChange={(event) => setEndpointId(event.target.value)}
                  placeholder="输入该模型对应的 Endpoint ID" />
              </div>
            )}
          </>
        ) : (
          <>
            <p className={styles.hint}>从供应商账号可用模型中勾选，模型能力将根据供应商返回信息自动设置。</p>
            {loadError && <div className={styles.error}>{loadError}</div>}
            <Spin spinning={loading}>
              <Select
                mode="multiple"
                showSearch
                value={selectedModels}
                onChange={(values) => {
                  setSelectedModels(values);
                  setEndpointIds((current) => {
                    const next: Record<string, string> = {};
                    values.forEach((id) => { next[id] = current[id] || ''; });
                    return next;
                  });
                }}
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
            {needsEndpointId && selectedModels.length > 0 && (
              <div className={styles['endpoint-fields']}>
                {selectedModels.map((modelId) => (
                  <div className={styles['endpoint-field']} key={modelId}>
                    <label>{modelId} 的接入点 ID</label>
                    <Input
                      value={endpointIds[modelId] || ''}
                      onChange={(event) => setEndpointIds((current) => ({
                        ...current,
                        [modelId]: event.target.value,
                      }))}
                      placeholder="输入该模型对应的 Endpoint ID"
                    />
                  </div>
                ))}
              </div>
            )}
            <div className={styles['manual-model']}>
              <span>找不到模型？手动输入模型 ID</span>
              <div>
                <Input
                  value={manualModelId}
                  onChange={(event) => setManualModelId(event.target.value)}
                  onPressEnter={addManualModel}
                  placeholder="输入模型 ID"
                />
                <Button onClick={addManualModel} disabled={!manualModelId.trim()}>加入选择</Button>
              </div>
            </div>
            {loadError && <Button type="link" onClick={fetchAvailableModels}>重试</Button>}
          </>
        )}
      </div>
    </Modal>
  );
};

export default ModelConfigModal;

function inferTagsFromName(modelId: string): string[] {
  const name = modelId.toLowerCase();
  const tags: string[] = [];
  if (/vision|(^|[-_])vl([-_]|$)|gpt-4o|gemini/.test(name)) tags.push('vision');
  if (/search|web[-_]?search/.test(name)) tags.push('web_search');
  if (/embed|embedding/.test(name)) tags.push('embedding');
  if (/reason|(^|[-_])r1([-_]|$)|qwq|thinking|(^|[-_])o[1-9]([-_]|$)/.test(name)) tags.push('reasoning');
  if (!tags.includes('embedding') && !/image|tts|whisper|rerank/.test(name)) tags.push('function_call');
  return tags;
}
