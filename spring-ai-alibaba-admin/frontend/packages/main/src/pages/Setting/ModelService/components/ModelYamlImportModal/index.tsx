import $i18n from '@/i18n';
import { importModelYaml } from '@/services/modelService';
import { IModelYamlImportResult } from '@/types/modelService';
import { Alert, Button, Checkbox, Modal, Table, Upload, message } from 'antd';
import type { UploadFile } from 'antd';
import { useState } from 'react';

const t = (key: string) => $i18n.get(`modelParams.${key}`);

export default function ModelYamlImportModal({ provider, onClose, onSuccess }: {
  provider: string; onClose: () => void; onSuccess: () => void;
}) {
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [overwrite, setOverwrite] = useState(false);
  const [preview, setPreview] = useState<IModelYamlImportResult>();
  const [busy, setBusy] = useState(false);
  const submit = async (dryRun: boolean) => {
    if (!files.length) { message.warning(t('chooseFiles')); return; }
    setBusy(true);
    try {
      const response = await importModelYaml(provider, files.map((file) => file.originFileObj || file) as File[], overwrite, dryRun);
      if (!response.data?.details) throw new Error(response.message || t('invalidImportResponse'));
      if (dryRun) setPreview(response.data);
      else { message.success(t('importSuccess')); onSuccess(); }
    } catch (error: any) {
      setPreview(undefined);
      message.error(error?.response?.data?.message || error?.message || t('importFailed'));
    } finally { setBusy(false); }
  };
  return <Modal open title={t('importTitle')} width={850} onCancel={busy ? undefined : onClose}
    closable={!busy} maskClosable={!busy} footer={[
      <Button key="preview" disabled={!files.length || busy} onClick={() => submit(true)}>{t('preview')}</Button>,
      <Button key="import" type="primary" loading={busy} disabled={!preview || busy} onClick={() => submit(false)}>{t('import')}</Button>,
    ]}>
    <div className="flex flex-col gap-4">
      <Alert type="info" message={t('importHint')} description={t('previewHint')} />
      <Upload multiple accept=".yaml,.yml,.zip" fileList={files} beforeUpload={() => false} disabled={busy}
        onChange={({ fileList }) => { setFiles(fileList); setPreview(undefined); }}>
        <Button disabled={busy}>{t('selectFiles')}</Button>
      </Upload>
      <Checkbox checked={overwrite} disabled={busy} onChange={(event) => { setOverwrite(event.target.checked); setPreview(undefined); }}>{t('overwrite')}</Checkbox>
      {preview && <>
        <Table size="small" rowKey="model_id" dataSource={preview.details} pagination={{ pageSize: 8 }}
          expandable={{ expandedRowRender: (row) => <pre style={{ maxHeight: 260, overflow: 'auto' }}>{JSON.stringify(row.parameters, null, 2)}</pre> }}
          columns={[
            { title: t('model'), dataIndex: 'model_id' },
            { title: t('file'), dataIndex: 'file', ellipsis: true },
            { title: t('action'), dataIndex: 'action', render: (value: string) => t(value) },
            { title: t('parameterCount'), dataIndex: 'parameter_count' },
          ]} />
        {!!preview.ignored_files.length && <Alert type="warning" message={`${t('ignored')}: ${preview.ignored_files.join(', ')}`} />}
      </>}
    </div>
  </Modal>;
}
