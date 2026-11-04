import { request } from '@/request';
import { IAppType } from '@/services/appComponent';
import { createApp, updateApp } from '@/services/appManage';
import { getModelParameterRules, getModelSelector } from '@/services/modelService';
import { createAppInProjectArchiveFolder } from '@/services/projectArchive';
import type { IWorkFlowAppDetail, IWorkFlowConfig } from '@/types/appManage';
import { Button, message } from '@spark-ai/design';
import { Modal } from 'antd';
import { useRef, useState } from 'react';
import {
  prepareDifyArithmeticAssignments,
  restoreStudioArithmeticAssignments,
} from '../../utils/difyVariableAssignOperations';
import { importWorkflowDsl } from '../../utils/importWorkflowDsl';

const MODEL_METADATA_KEY = '__model_metadata__';

interface ImportedModelConfig {
  provider?: string;
  model_id?: string;
  endpoint_id?: string;
  params?: Array<{
    key: string;
    type: string;
    value: any;
    enable: boolean;
  }>;
}

/**
 * Dify DSL does not carry SAA's internal provider protocol snapshot on every
 * LLM node. Fetch it once per distinct model and attach it to every imported
 * node while preserving the Dify completion parameters.
 */
async function hydrateImportedModelMetadata(
  config: IWorkFlowConfig,
  providerProtocols: Map<string, string>,
) {
  const modelConfigs = config.nodes
    .map((node) => node.config?.node_param?.model_config as ImportedModelConfig | undefined)
    .filter(
      (modelConfig): modelConfig is ImportedModelConfig =>
        !!modelConfig?.provider && !!modelConfig?.model_id,
    );

  const uniqueModels = new Map<string, { provider: string; modelId: string }>();
  modelConfigs.forEach((modelConfig) => {
    const provider = String(modelConfig.provider);
    const modelId = String(modelConfig.model_id);
    uniqueModels.set(JSON.stringify([provider, modelId]), { provider, modelId });
  });

  const metadataByModel = new Map<string, Record<string, any>>();
  await Promise.all(
    Array.from(uniqueModels.entries()).map(async ([key, model]) => {
      const response = await getModelParameterRules(model.provider, model.modelId);
      const metadataRule = (response.data || []).find(
        (rule) => rule.code === MODEL_METADATA_KEY,
      );
      const raw = metadataRule?.default_value;
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
        throw new Error(
          `模型 ${model.provider}/${model.modelId} 未返回协议元数据，请先检查模型管理中的参数规则后重试`,
        );
      }

      const protocol = String(
        raw.protocol || providerProtocols.get(model.provider) || 'openai',
      ).toLowerCase();
      const metadata = {
        schema_version: raw.schema_version ?? 2,
        provider: raw.provider ?? model.provider,
        model_id: raw.model_id ?? model.modelId,
        protocol,
        endpoint_type: raw.endpoint_type ?? 'unknown',
        route_source: raw.route_source ?? 'unknown',
        parameter_source: raw.parameter_source ?? 'unknown',
        endpoint_id: raw.endpoint_id ?? null,
      };

      if (
        metadata.provider !== model.provider ||
        metadata.model_id !== model.modelId
      ) {
        throw new Error(
          `模型 ${model.provider}/${model.modelId} 返回了不匹配的协议元数据，请刷新模型参数规则后重试`,
        );
      }
      if (
        protocol === 'dashscope' &&
        !['text-generation', 'multimodal-generation'].includes(
          String(metadata.endpoint_type),
        )
      ) {
        throw new Error(
          `DashScope 模型 ${model.provider}/${model.modelId} 的推理接口类型尚未确认，请先在模型管理中刷新模型参数规则`,
        );
      }

      metadataByModel.set(key, metadata);
    }),
  );

  modelConfigs.forEach((modelConfig) => {
    const provider = String(modelConfig.provider);
    const modelId = String(modelConfig.model_id);
    const metadata = metadataByModel.get(JSON.stringify([provider, modelId]));
    if (!metadata) return;

    const params = Array.isArray(modelConfig.params) ? modelConfig.params : [];
    modelConfig.params = [
      ...params.filter((param) => param?.key !== MODEL_METADATA_KEY),
      {
        key: MODEL_METADATA_KEY,
        type: 'Object',
        value: metadata,
        enable: true,
      },
    ];
  });
}

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

      const providerProtocols = new Map(
        selectors.data.map((item) => [
          item.provider.provider,
          String(item.provider.protocol || 'openai'),
        ]),
      );
      await hydrateImportedModelMetadata(imported.config, providerProtocols);

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
          '已转换为可编辑画布；模型协议元数据已自动补全，请继续检查节点提示词及参数',
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
