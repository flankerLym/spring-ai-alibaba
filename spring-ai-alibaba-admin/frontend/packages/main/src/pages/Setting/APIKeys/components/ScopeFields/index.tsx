import { getAppList } from '@/services/appManage';
import {
  getProjectArchiveFolders,
  type IProjectArchiveFolder,
} from '@/services/projectArchive';
import type { IAppCard } from '@/types/appManage';
import { Form, Radio, Select, Spin, Typography } from 'antd';
import React, { useEffect, useState } from 'react';

interface ScopeFieldsProps {
  open: boolean;
  form: any;
}

async function loadAllApps(): Promise<IAppCard[]> {
  const pageSize = 200;
  let current = 1;
  let total = 0;
  const all: IAppCard[] = [];

  do {
    const page = await getAppList({ current, size: pageSize });
    const records = (page?.records || []) as IAppCard[];
    all.push(...records);
    total = Number(page?.total || records.length);
    current += 1;
    if (records.length === 0) break;
  } while (all.length < total);

  const dedup = new Map<string, IAppCard>();
  all.forEach((app) => dedup.set(app.app_id, app));
  return Array.from(dedup.values());
}

const ScopeFields: React.FC<ScopeFieldsProps> = ({ open, form }) => {
  const [folders, setFolders] = useState<IProjectArchiveFolder[]>([]);
  const [apps, setApps] = useState<IAppCard[]>([]);
  const [loading, setLoading] = useState(false);
  const scopeType = Form.useWatch('scopeType', form) || 'ALL';

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    Promise.all([getProjectArchiveFolders(), loadAllApps()])
      .then(([folderList, appList]) => {
        if (cancelled) return;
        setFolders(folderList || []);
        setApps(appList || []);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open]);

  return (
    <>
      <Form.Item name="scopeType" label="访问范围" initialValue="ALL">
        <Radio.Group>
          <Radio value="ALL">全部应用（ALL）</Radio>
          <Radio value="CUSTOM">指定范围</Radio>
        </Radio.Group>
      </Form.Item>

      {scopeType === 'CUSTOM' && (
        <Spin spinning={loading}>
          <Form.Item name="folderIds" label="授权文件夹">
            <Select
              mode="multiple"
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="可选择多个项目归档文件夹"
              options={folders.map((folder) => ({
                label: `${folder.name}（${folder.app_count || 0} 个应用）`,
                value: folder.folder_id,
              }))}
            />
          </Form.Item>

          <Form.Item name="appIds" label="额外授权应用">
            <Select
              mode="multiple"
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="可选择多个应用"
              options={apps.map((app) => ({
                label: app.name || app.app_id,
                value: app.app_id,
              }))}
            />
          </Form.Item>

          <Typography.Text type="secondary">
            文件夹内应用与单独选择的应用取并集；同一应用重复命中时后端自动去重。文件夹后续新增或移除应用时，授权范围会自动随之变化。
          </Typography.Text>
        </Spin>
      )}
    </>
  );
};

export default ScopeFields;
