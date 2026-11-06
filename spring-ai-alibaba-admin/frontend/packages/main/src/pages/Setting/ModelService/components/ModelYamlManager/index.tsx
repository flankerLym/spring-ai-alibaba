import $i18n from '@/i18n';
import { IProvider } from '@/types/modelService';
import { deleteYamlFile, listYamlFiles, readYamlFile, saveYamlFile, YamlFileInfo, YamlFileContent } from '@/services/modelYamlFiles';
import { Alert, Button, Drawer, Empty, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd';
import { useEffect, useRef, useState } from 'react';

const t = (zh: string, en: string) => $i18n.getCurrentLanguage().toLowerCase().startsWith('zh') ? zh : en;
const template = `model: your-model-id
model_type: llm
parameter_rules:
  - name: temperature
    type: float
    min: 0
    max: 2
    default: 1
    label:
      zh_Hans: 温度
      en_US: Temperature
    help:
      zh_Hans: 调低回答更稳定，调高回答更多样。
      en_US: Lower values give more consistent answers; higher values add variety.
`;

export default function ModelYamlManager({ open, onClose, providers }: {
  open: boolean; onClose: () => void; providers: IProvider[];
}) {
  const [provider, setProvider] = useState('');
  const selected = useRef('');
  const upload = useRef<HTMLInputElement>(null);
  const [rows, setRows] = useState<YamlFileInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [editor, setEditor] = useState<{ file: YamlFileContent; create: boolean }>();
  const current = providers.find(p => p.provider === provider);
  const refresh = async (id = provider) => {
    if (!id) return;
    setLoading(true);
    try {
      const response = await listYamlFiles(id);
      if (selected.current === id) setRows(response.data || []);
    } catch { /* Request layer displays the server validation message. */ }
    finally { if (selected.current === id) setLoading(false); }
  };
  useEffect(() => {
    if (!open) return;
    const id = providers.some(p => p.provider === provider) ? provider : providers[0]?.provider || '';
    selected.current = id; setProvider(id); setRows([]);
    if (id) void refresh(id);
  }, [open, providers]);
  const selectProvider = (id: string) => {
    selected.current = id; setProvider(id); setRows([]); void refresh(id);
  };
  const edit = async (row: YamlFileInfo) => {
    setBusy(true);
    try {
      const response = await readYamlFile(provider, row.filename);
      setEditor({ file: response.data, create: false });
    } catch { /* Request layer displays errors. */ }
    finally { setBusy(false); }
  };
  const save = async () => {
    if (!editor || !editor.file.filename.trim()) return;
    setBusy(true);
    try {
      await saveYamlFile(provider, { ...editor.file, filename: editor.file.filename.trim() }, editor.create);
      message.success(t('保存成功', 'Saved')); setEditor(undefined); await refresh();
    } catch { /* Keep the editor open on validation or version conflicts. */ }
    finally { setBusy(false); }
  };
  const remove = async (row: YamlFileInfo) => {
    setBusy(true);
    try { await deleteYamlFile(provider, row.filename, row.version); message.success(t('删除成功', 'Deleted')); await refresh(); }
    catch { /* Request layer displays errors. */ }
    finally { setBusy(false); }
  };
  const importFiles = async (files: FileList | null) => {
    if (!files?.length) return;
    setBusy(true); let success = 0;
    try {
      for (const file of Array.from(files)) {
        if (!/\.ya?ml$/i.test(file.name) || file.size > 1024 * 1024) {
          message.error(t('请选择不超过 1 MiB 的 YAML 文件：', 'Choose a YAML file no larger than 1 MiB: ') + file.name); continue;
        }
        try { await saveYamlFile(provider, { filename: file.name, content: await file.text(), version: '' }, true); success++; }
        catch { /* Continue the batch; never overwrite an existing file silently. */ }
      }
      if (success) message.success(t(`已上传 ${success} 个文件`, `Uploaded ${success} file(s)`));
      await refresh();
    } finally { if (upload.current) upload.current.value = ''; setBusy(false); }
  };
  const close = () => { if (!busy && !editor) onClose(); };
  return <>
    <Drawer title={t('模型参数配置', 'Model parameter configuration')} width={1100} open={open} onClose={close}
      maskClosable={!busy && !editor} keyboard={!busy && !editor}>
      <Alert showIcon type="info" message={t('选择供应商后自动创建配置目录，无需手动创建文件夹。', 'Provider folders are created automatically when selected.')}
        description={t('保存的配置用于以后新增模型。已有模型请使用原来的 YAML 导入覆盖功能更新。', 'Saved definitions apply to models added later. Use the existing YAML import with overwrite to update registered models.')} />
      <Space wrap style={{ margin: '20px 0', width: '100%' }}>
        <Select style={{ minWidth: 280 }} value={provider || undefined} onChange={selectProvider} disabled={busy || !!editor}
          placeholder={t('选择供应商', 'Select provider')} options={providers.map(p => ({ value: p.provider, label: `${p.name || p.provider} (${p.provider})` }))} />
        <Button disabled={!provider || busy} onClick={() => void refresh()}>{t('刷新', 'Refresh')}</Button>
        <Button type="primary" disabled={!provider || busy} onClick={() => setEditor({ create: true, file: { filename: '', content: template, version: '' } })}>{t('新增 YAML', 'New YAML')}</Button>
        <Button disabled={!provider || busy} loading={busy && !editor} onClick={() => upload.current?.click()}>{t('上传 YAML', 'Upload YAML')}</Button>
        <input ref={upload} type="file" accept=".yaml,.yml" multiple hidden onChange={e => void importFiles(e.target.files)} />
      </Space>
      {current && <Typography.Paragraph type="secondary">{t('当前供应商：', 'Provider: ')}{current.name} · {provider}</Typography.Paragraph>}
      {!providers.length ? <Empty description={t('请先新增模型供应商', 'Add a model provider first')} /> :
        <Table<YamlFileInfo> rowKey="filename" dataSource={rows} loading={loading} pagination={{ pageSize: 10 }} scroll={{ x: 800 }} columns={[
          { title: t('文件名', 'Filename'), dataIndex: 'filename', width: 220, ellipsis: true },
          { title: t('模型', 'Models'), render: (_, row) => row.error ? <Tag color="error">{t('配置无效，请编辑修正', 'Invalid YAML; edit to fix')}</Tag> : row.models.join(', ') },
          { title: t('参数数', 'Parameters'), dataIndex: 'parameter_count', width: 90 },
          { title: t('更新时间', 'Modified'), width: 170, render: (_, row) => new Date(row.modified).toLocaleString() },
          { title: t('操作', 'Actions'), width: 150, render: (_, row) => <Space>
            <Button type="link" size="small" disabled={busy} onClick={() => void edit(row)}>{t('查看 / 编辑', 'View / Edit')}</Button>
            <Popconfirm title={t('确定删除该 YAML 配置？', 'Delete this YAML definition?')} disabled={busy} onConfirm={() => remove(row)}>
              <Button type="link" danger size="small" disabled={busy}>{t('删除', 'Delete')}</Button>
            </Popconfirm>
          </Space> },
        ]} />}
    </Drawer>
    <Modal title={editor?.create ? t('新增 YAML 配置', 'New YAML definition') : t('查看 / 编辑 YAML', 'View / Edit YAML')}
      open={!!editor} width={920} confirmLoading={busy} maskClosable={false} keyboard={!busy}
      onCancel={() => { if (!busy) setEditor(undefined); }} onOk={() => void save()} okButtonProps={{ disabled: !editor?.file.filename.trim() }}
      okText={t('校验并保存', 'Validate and save')} cancelText={t('取消', 'Cancel')}>
      <Typography.Paragraph>{t('供应商：', 'Provider: ')}{current?.name} ({provider})</Typography.Paragraph>
      <Input value={editor?.file.filename || ''} disabled={busy || !editor?.create} placeholder={t('文件名，例如 deepseek-v4-pro.yaml', 'Filename, e.g. deepseek-v4-pro.yaml')}
        style={{ marginBottom: 12 }} onChange={e => setEditor(value => value && ({ ...value, file: { ...value.file, filename: e.target.value } }))} />
      <Input.TextArea value={editor?.file.content || ''} disabled={busy} rows={21} spellCheck={false}
        style={{ fontFamily: 'monospace', lineHeight: 1.6 }} onChange={e => setEditor(value => value && ({ ...value, file: { ...value.file, content: e.target.value } }))} />
    </Modal>
  </>;
}
