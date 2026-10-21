import { request } from '@/request';
import { IAppType } from '@/services/appComponent';
import { createApp, updateApp } from '@/services/appManage';
import { getModelSelector } from '@/services/modelService';
import { createAppInProjectArchiveFolder } from '@/services/projectArchive';
import type { IWorkFlowAppDetail } from '@/types/appManage';
import { Button, message } from '@spark-ai/design';
import { Modal } from 'antd';
import { useRef, useState } from 'react';
import {
  prepareDifyArithmeticAssignments,
  restoreStudioArithmeticAssignments,
} from '../../utils/difyVariableAssignOperations';
import { importWorkflowDsl } from '../../utils/importWorkflowDsl';

interface ImportDslButtonProps {
  onImported: (id: string) => void | Promise<void>;
  archiveFolderId?: string;
  /** When supplied, import into the existing workflow instead of creating an app. */
  appDetail?: IWorkFlowAppDetail;
}

export default function ImportDslButton({
  onImported,
  archiveFolderId,
  appDetail,
}: ImportDslButtonProps) {
  const input = useRef<HTMLInputElement>(null);
  const busy = useRef(false);
  const [loading, setLoading] = useState(false);

  const importFile = async (file?: File) => {
    if (!file || busy.current) return;
    busy.current = true;
    setLoading(true);
    try {
      if (!/\.(ya?ml|json)$/i.test(file.name))
        throw new Error('请选择 YAML 或 JSON DSL 文件');
      if (file.size > 2 * 1024 * 1024)
        throw new Error('DSL 文件不能超过 2 MB');

      const response = await request({
        url: '/console/v1/workflow-dsl/parse',
        method: 'POST',
        data: { dsl: await file.text() },
      });
      const selectors = await getModelSelector('llm');

      const prepared = prepareDifyArithmeticAssignments(response.data.data);
      const imported = restoreStudioArithmeticAssignments(
        importWorkflowDsl(
          prepared.document,
          file.name,
          selectors.data.flatMap((item) => item.models),
        ),
        prepared.operations,
      );

      if (appDetail) {
        // Update the current app, preserving its ID and existing name/type.
        await updateApp({
          app_id: appDetail.app_id,
          name: appDetail.name,
          type: appDetail.type,
          config: imported.config,
        });
        await onImported(appDetail.app_id);
        message.success('DSL 已覆盖当前工作流，应用 ID 保持不变');
      } else {
        const appParams = {
          name: imported.name,
          type: IAppType.WORKFLOW,
          config: imported.config,
        };
        const id = archiveFolderId
          ? await createAppInProjectArchiveFolder(archiveFolderId, appParams)
          : await createApp(appParams);

        await onImported(id);
        message.success(
          archiveFolderId ? 'DSL 已导入并归档到当前文件夹' : 'DSL 已导入为草稿',
        );
      }

      if (imported.dify)
        message.warning(
          '已转换为可编辑画布；运行前请检查模型配置及节点参数',
          8,
        );
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'DSL 导入失败');
    } finally {
      busy.current = false;
      setLoading(false);
      if (input.current) input.current.value = '';
    }
  };

  return (
    <>
      <input
        ref={input}
        type="file"
        accept=".yml,.yaml,.json"
        hidden
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (!file) return;

          if (appDetail) {
            Modal.confirm({
              title: '覆盖当前工作流？',
              content:
                '导入的 DSL 将替换当前草稿中的节点、连线和工作流配置。应用 ID 与应用名称保持不变，此操作无法自动撤销，建议先导出备份。',
              okText: '确认覆盖',
              cancelText: '取消',
              okButtonProps: { danger: true },
              onOk: () => importFile(file),
              onCancel: () => {
                if (input.current) input.current.value = '';
              },
            });
            return;
          }

          void importFile(file);
        }}
      />
      <Button loading={loading} disabled={loading} onClick={() => input.current?.click()}>
        {appDetail ? '导入 DSL 覆盖' : '导入 DSL'}
      </Button>
    </>
  );
}
