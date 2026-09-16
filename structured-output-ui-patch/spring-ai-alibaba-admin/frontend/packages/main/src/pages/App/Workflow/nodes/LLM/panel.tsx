import $i18n from '@/i18n';
import { ISelectedModelParams } from '@/types/modelService';
import {
  IVarTreeItem,
  OutputParamsTree,
  VarInputTextArea,
  filterVarItemsByType,
  useNodeDataUpdate,
  useNodesOutputParams,
  useNodesReadOnly,
  useReactFlowStore,
} from '@spark-ai/flow';
import { Button, Divider, Flex, Switch, Typography } from 'antd';
import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import ErrorCatchForm from '../../components/ErrorCatchForm';
import InfoIcon from '../../components/InfoIcon';
import ModelConfigFormWrap from '../../components/ModelConfigFormWrap';
import RetryForm from '../../components/RetryForm';
import ShortMemoryForm from '../../components/ShortMemoryForm';
import StructuredOutputSchemaModal from '../../components/StructuredOutputSchemaModal';
import {
  LLM_NODE_OUTPUT_PARAMS_DEFAULT,
  LLM_WITH_REASONING_NODE_OUTPUT_PARAMS_DEFAULT,
} from '../../constant';
import { useWorkflowAppStore } from '../../context/WorkflowAppProvider';
import { ILLMNodeData, ILLMNodeParam } from '../../types';
import { getDefaultValueSchemaFromOutputParams } from '../APINode/panel';
import {
  DEFAULT_STRUCTURED_OUTPUT_SCHEMA,
  JsonSchema,
  getStructuredSchemaTypeLabel,
  mergeStructuredOutputParam,
} from './structuredOutput';

const schemaChildren = (schema?: JsonSchema): Record<string, JsonSchema> => {
  if (schema?.type === 'array' && schema?.items?.type === 'object') {
    return schema.items.properties || {};
  }
  return schema?.properties || {};
};

const schemaRequired = (schema?: JsonSchema): Set<string> => {
  if (schema?.type === 'array' && schema?.items?.type === 'object') {
    return new Set<string>(schema.items.required || []);
  }
  return new Set<string>(schema?.required || []);
};

const StructuredOutputPreviewNode = memo(
  (props: {
    name: string;
    schema: JsonSchema;
    required?: boolean;
    depth?: number;
  }) => {
    const { name, schema, required, depth = 0 } = props;
    const children = schemaChildren(schema);
    const requiredSet = schemaRequired(schema);
    return (
      <div
        style={{
          marginLeft: depth ? 14 : 0,
          paddingLeft: depth ? 12 : 0,
          borderLeft: depth ? '1px solid #e5e7eb' : undefined,
        }}
      >
        <Flex align="center" gap={8} wrap="wrap">
          <Typography.Text strong>{name}</Typography.Text>
          <Typography.Text type="secondary">
            {getStructuredSchemaTypeLabel(schema)}
          </Typography.Text>
          {required && (
            <Typography.Text style={{ color: '#ff7d00', fontSize: 12 }}>
              必填
            </Typography.Text>
          )}
        </Flex>
        {!!schema?.description && (
          <Typography.Text
            type="secondary"
            style={{ display: 'block', marginTop: 2, fontSize: 12 }}
          >
            {schema.description}
          </Typography.Text>
        )}
        {Object.entries(children).map(([childName, childSchema]) => (
          <div key={childName} style={{ marginTop: 8 }}>
            <StructuredOutputPreviewNode
              name={childName}
              schema={childSchema as JsonSchema}
              required={requiredSet.has(childName)}
              depth={depth + 1}
            />
          </div>
        ))}
      </div>
    );
  },
);

export default memo((props: { id: string; data: ILLMNodeData }) => {
  const { handleNodeDataUpdate } = useNodeDataUpdate();
  const { getVariableList } = useNodesOutputParams();
  const globalVariableList = useWorkflowAppStore(
    (state) => state.globalVariableList,
  );
  const nodes = useReactFlowStore((store) => store.nodes);
  const edges = useReactFlowStore((store) => store.edges);
  const { nodesReadOnly } = useNodesReadOnly();
  const [schemaModalOpen, setSchemaModalOpen] = useState(false);

  const structuredOutputEnabled = Boolean(
    props.data.node_param.structured_output_enabled,
  );
  const structuredOutputSchema =
    props.data.node_param.structured_output_schema ||
    DEFAULT_STRUCTURED_OUTPUT_SCHEMA;

  const flowVariableList = useMemo(() => {
    return getVariableList({
      nodeId: props.id,
    });
  }, [props.id, nodes, edges]);

  const variableList = useMemo(() => {
    return [...globalVariableList, ...flowVariableList];
  }, [globalVariableList, flowVariableList]);

  const changeNodeParam = useCallback(
    (payload: Partial<ILLMNodeParam>) => {
      handleNodeDataUpdate({
        id: props.id,
        data: {
          node_param: {
            ...props.data.node_param,
            ...payload,
          },
        },
      });
    }, [props.data.node_param, handleNodeDataUpdate, props.id]);

  const variableListByArrayType = useMemo(() => {
    const list: IVarTreeItem[] = [];
    variableList.forEach((item) => {
      const params = filterVarItemsByType(item.children, [
        'Array<String>',
        'Array<Object>',
      ]);
      if (!params.length) return;
      list.push({
        ...item,
        children: params,
      });
    });
    return list;
  }, [variableList]);

  const fileVariableList = useMemo(() => {
    const list: IVarTreeItem[] = [];
    variableList.forEach((item) => {
      const subList = filterVarItemsByType(item.children, [
        'File',
        'Array<File>',
      ]);
      if (subList.length > 0) {
        list.push({
          ...item,
          children: subList,
        });
      }
    });
    return list;
  }, [variableList]);

  const baseOutputParams = useMemo(
    () =>
      (props.data.output_params || []).filter(
        (item) => item.key !== 'structured_output',
      ),
    [props.data.output_params],
  );

  // Dify imported nodes may already contain structured_output_schema in node_param
  // but not have a matching output_params tree. Keep the output variable tree in sync
  // so downstream nodes can directly reference ${nodeId.structured_output.xxx}.
  useEffect(() => {
    if (!structuredOutputEnabled) return;
    const nextOutputParams = mergeStructuredOutputParam(
      baseOutputParams,
      true,
      structuredOutputSchema,
    );
    if (JSON.stringify(nextOutputParams) === JSON.stringify(props.data.output_params)) {
      return;
    }
    handleNodeDataUpdate({
      id: props.id,
      data: {
        output_params: nextOutputParams,
      },
    });
  }, [
    structuredOutputEnabled,
    structuredOutputSchema,
    baseOutputParams,
    props.data.output_params,
    props.id,
    handleNodeDataUpdate,
  ]);

  const changeLLMConfig = useCallback(
    (
      payload: ISelectedModelParams,
      options?: { isSupportReasoning: boolean; isSupportVision: boolean },
    ) => {
      const modelOutputParams = options
        ? options.isSupportReasoning
          ? LLM_WITH_REASONING_NODE_OUTPUT_PARAMS_DEFAULT
          : LLM_NODE_OUTPUT_PARAMS_DEFAULT
        : baseOutputParams;
      handleNodeDataUpdate({
        id: props.id,
        data: {
          node_param: {
            ...props.data.node_param,
            model_config: {
              ...props.data.node_param.model_config,
              ...payload,
            },
          },
          output_params: mergeStructuredOutputParam(
            modelOutputParams,
            structuredOutputEnabled,
            structuredOutputSchema,
          ),
        },
      });
    }, [
      baseOutputParams,
      handleNodeDataUpdate,
      props.data.node_param,
      props.id,
      structuredOutputEnabled,
      structuredOutputSchema,
    ],
  );

  const changeStructuredOutputEnabled = useCallback(
    (enabled: boolean) => {
      const schema =
        props.data.node_param.structured_output_schema ||
        DEFAULT_STRUCTURED_OUTPUT_SCHEMA;
      handleNodeDataUpdate({
        id: props.id,
        data: {
          node_param: {
            ...props.data.node_param,
            structured_output_enabled: enabled,
            structured_output_schema: schema,
          },
          output_params: mergeStructuredOutputParam(
            baseOutputParams,
            enabled,
            schema,
          ),
        },
      });
    }, [
      baseOutputParams,
      handleNodeDataUpdate,
      props.data.node_param,
      props.id,
    ],
  );

  const saveStructuredOutputSchema = useCallback(
    (schema: JsonSchema) => {
      handleNodeDataUpdate({
        id: props.id,
        data: {
          node_param: {
            ...props.data.node_param,
            structured_output_enabled: true,
            structured_output_schema: schema,
          },
          output_params: mergeStructuredOutputParam(
            baseOutputParams,
            true,
            schema,
          ),
        },
      });
      setSchemaModalOpen(false);
    }, [
      baseOutputParams,
      handleNodeDataUpdate,
      props.data.node_param,
      props.id,
    ],
  );

  return (
    <>
      <div className="spark-flow-panel-form-section">
        <Flex vertical gap={12}>
          <div className="spark-flow-panel-form-title">
            {$i18n.get({
              id: 'main.pages.App.Workflow.nodes.LLM.panel.modelSelection',
              dm: '模型选择',
            })}

            <InfoIcon
              tip={$i18n.get({
                id: 'main.pages.App.Workflow.nodes.LLM.panel.configureModel',
                dm: '请自行配置模型，根据业务场景选择即可。',
              })}
            />
          </div>
          <ModelConfigFormWrap
            disabled={nodesReadOnly}
            variableList={fileVariableList}
            value={props.data.node_param.model_config}
            onChange={changeLLMConfig}
          />
        </Flex>
        <Flex vertical gap={12}>
          <div className="spark-flow-panel-form-title">
            {$i18n.get({
              id: 'main.pages.App.Workflow.nodes.LLM.panel.prompt',
              dm: '提示词',
            })}

            <InfoIcon
              tip={$i18n.get({
                id: 'main.pages.App.Workflow.nodes.LLM.panel.systemInstruction',
                dm: '为模型提供系统级的指令，如人设、约束等。',
              })}
            />
          </div>
          <VarInputTextArea
            disabled={nodesReadOnly}
            onChange={(val) =>
              changeNodeParam({
                sys_prompt_content: val,
              })
            }
            value={props.data.node_param.sys_prompt_content}
            variableList={variableList}
            maxLength={Number.MAX_SAFE_INTEGER}
          />
        </Flex>
        <Flex vertical gap={12}>
          <div className="spark-flow-panel-form-title">
            {$i18n.get({
              id: 'main.pages.App.Workflow.nodes.LLM.panel.userPrompt',
              dm: '用户提示词',
            })}

            <InfoIcon
              tip={$i18n.get({
                id: 'main.pages.App.Workflow.nodes.LLM.panel.interactionContent',
                dm: '用户和模型的交互内容，如要求、指令等。',
              })}
            />
          </div>
          <VarInputTextArea
            disabled={nodesReadOnly}
            onChange={(val) =>
              changeNodeParam({
                prompt_content: val,
              })
            }
            value={props.data.node_param.prompt_content}
            variableList={variableList}
            maxLength={Number.MAX_SAFE_INTEGER}
          />
        </Flex>
        <ShortMemoryForm
          disabled={nodesReadOnly}
          onChange={(val) => changeNodeParam({ short_memory: val })}
          value={props.data.node_param.short_memory}
          variableList={variableListByArrayType}
        />
      </div>
      <div className="spark-flow-panel-form-section">
        <Flex vertical gap={12}>
          <Flex justify="space-between" align="center" gap={12}>
            <div className="spark-flow-panel-form-title">
              {$i18n.get({
                id: 'main.pages.App.Workflow.nodes.LLM.panel.output',
                dm: '输出',
              })}

              <InfoIcon
                tip={$i18n.get({
                  id: 'main.pages.App.Workflow.nodes.LLM.panel.outputContent',
                  dm: '模型运行结束后的输出内容。',
                })}
              />
            </div>
            <Flex align="center" gap={6}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                结构化输出
              </Typography.Text>
              <Switch
                size="small"
                disabled={nodesReadOnly}
                checked={structuredOutputEnabled}
                onChange={changeStructuredOutputEnabled}
              />
            </Flex>
          </Flex>

          <OutputParamsTree data={baseOutputParams} />

          {structuredOutputEnabled && (
            <>
              <Divider style={{ margin: '2px 0 4px' }} />
              <Flex vertical gap={10}>
                <Flex justify="space-between" align="center">
                  <Flex gap={8} align="center">
                    <Typography.Text strong>structured_output</Typography.Text>
                    <Typography.Text type="secondary">object</Typography.Text>
                  </Flex>
                  <Button size="small" onClick={() => setSchemaModalOpen(true)}>
                    配置
                  </Button>
                </Flex>
                <div style={{ paddingLeft: 8 }}>
                  {Object.entries(structuredOutputSchema.properties || {}).map(
                    ([name, schema]) => (
                      <div key={name} style={{ marginBottom: 10 }}>
                        <StructuredOutputPreviewNode
                          name={name}
                          schema={schema as JsonSchema}
                          required={new Set<string>(
                            structuredOutputSchema.required || [],
                          ).has(name)}
                        />
                      </div>
                    ),
                  )}
                  {!Object.keys(structuredOutputSchema.properties || {}).length && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      暂未配置字段，点击“配置”添加结构化输出字段。
                    </Typography.Text>
                  )}
                </div>
              </Flex>
            </>
          )}
        </Flex>
      </div>
      <div className="spark-flow-panel-form-section">
        <RetryForm
          disabled={nodesReadOnly}
          value={props.data.node_param.retry_config}
          onChange={(val) =>
            changeNodeParam({
              retry_config: val,
            })
          }
        />
      </div>
      <div className="spark-flow-panel-form-section">
        <ErrorCatchForm
          disabled={nodesReadOnly}
          nodeId={props.id}
          value={props.data.node_param.try_catch_config}
          onChange={(val) => {
            changeNodeParam({
              try_catch_config: val,
            });
          }}
          onChangeType={(type) => {
            const params =
              type === 'failBranch'
                ? {
                    default_values: getDefaultValueSchemaFromOutputParams(
                      props.data.output_params,
                    ),
                  }
                : {};
            changeNodeParam({
              try_catch_config: {
                ...props.data.node_param.try_catch_config,
                strategy: type,
                ...params,
              },
            });
          }}
        />
      </div>

      <StructuredOutputSchemaModal
        open={schemaModalOpen}
        value={structuredOutputSchema}
        readOnly={nodesReadOnly}
        onCancel={() => setSchemaModalOpen(false)}
        onOk={saveStructuredOutputSchema}
      />
    </>
  );
});
