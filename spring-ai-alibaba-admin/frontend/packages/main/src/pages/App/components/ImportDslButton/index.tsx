import { request } from '@/request';
import { IAppType } from '@/services/appComponent';
import { createApp } from '@/services/appManage';
import { getModelSelector } from '@/services/modelService';
import { createAppInProjectArchiveFolder } from '@/services/projectArchive';
import { Button, message } from '@spark-ai/design';
import { useRef, useState } from 'react';
import {
  prepareDifyArithmeticAssignments,
  restoreStudioArithmeticAssignments,
} from '../../utils/difyVariableAssignOperations';
import { importWorkflowDsl } from '../../utils/importWorkflowDsl';

interface ImportDslButtonProps {
  onImported: (id: string) => void | Promise<void>;
  archiveFolderId?: string;
}

export default function ImportDslButton({
  onImported,
  archiveFolderId,
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
          void importFile(event.target.files?.[0]);
        }}
      />
      <Button loading={loading} onClick={() => input.current?.click()}>
        导入 DSL
      </Button>
    </>
  );
}
