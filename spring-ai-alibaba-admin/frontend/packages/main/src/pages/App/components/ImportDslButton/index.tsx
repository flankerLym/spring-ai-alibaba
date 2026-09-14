import { request } from '@/request';
import { IAppType } from '@/services/appComponent';
import { createApp } from '@/services/appManage';
import { Button, message } from '@spark-ai/design';
import { useRef, useState } from 'react';
import { importWorkflowDsl } from '../../utils/importWorkflowDsl';

export default function ImportDslButton({ onImported }: { onImported: (id: string) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const busy = useRef(false);
  const [loading, setLoading] = useState(false);

  const importFile = async (file?: File) => {
    if (!file || busy.current) return;
    busy.current = true;
    setLoading(true);
    try {
      if (!/\.(ya?ml|json)$/i.test(file.name)) throw new Error('请选择 YAML 或 JSON DSL 文件');
      if (file.size > 2 * 1024 * 1024) throw new Error('DSL 文件不能超过 2 MB');
      const response = await request({
        url: '/console/v1/workflow-dsl/parse',
        method: 'POST',
        data: { dsl: await file.text() },
      });
      const imported = importWorkflowDsl(response.data.data, file.name);
      const id = await createApp({ name: imported.name, type: IAppType.WORKFLOW, config: imported.config });
      message.success('DSL 已导入为草稿');
      if (imported.dify) message.warning('已转换为可编辑画布；运行前请检查模型配置及节点参数', 8);
      onImported(id);
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'DSL 导入失败');
    } finally {
      busy.current = false;
      setLoading(false);
      if (input.current) input.current.value = '';
    }
  };

  return <>
    <input ref={input} type="file" accept=".yml,.yaml,.json" hidden
      onChange={(event) => { void importFile(event.target.files?.[0]); }} />
    <Button loading={loading} onClick={() => input.current?.click()}>导入 DSL</Button>
  </>;
}
