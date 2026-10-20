import { VariableBaseInput } from '@/components/VariableBaseInput';
import $i18n from '@/i18n';
import Welcome from '@/pages/App/AssistantAppEdit/components/SparkChat/components/Welcome';
import {
  getWorkFlowTaskProcess,
  startPartGraphTask,
} from '@/services/workflow';
import { IBizEdge } from '@/types/workflow';
import { Button, Drawer, Empty, IconFont } from '@spark-ai/design';
import {
  buildOutputParamsTree,
  extractVariables,
  FlowIcon,
  INodeSchema,
  IValueType,
  IVarTreeItem,
  IWorkFlowNode,
  IWorkFlowNodeData,
  IWorkFlowStatus,
  IWorkFlowTaskProcess,
  useFlowDebugInteraction,
  useNodesOutputParams,
  useReactFlowStore,
  useStore,
} from '@spark-ai/flow';
import { useMount, useSetState, useUnmount } from 'ahooks';
import { Flex, Typography, message } from 'antd';
import classNames from 'classnames';
import { memo, useMemo, useRef } from 'react';
import { useWorkflowAppStore } from '../../context/WorkflowAppProvider';
import { INodeDataNodeParam } from '../../types';
import { NodeResultPanelList } from '../NodeResultPanel';
import ResultStatus from '../ResultStatus';
import { getSparkFlowUsageList } from '../TaskTestPanel';
import styles from './index.module.less';

interface ISingleNodeDrawer {
  selectedNodeData: IWorkFlowNode;
  onClose: () => void;
}

interface IInputParamItem {
  type: IValueType;
  key: string;
  value?: string;
  nodeType: string;
  nodeTitle: string;
  displayKey?: string;
}

const getFinalKey = (key: string) => {
  const finalValue = extractVariables(key.replace(/[\[]]/g, ''))[0];
  const list = finalValue.split('.');

  return list[list.length - 1];
};

const getInputParams = ({
  selectedNodeData,
  nodes,
  variableKeyList,
  globalVariableList,
  systemVariableList,
  nodeSchemaMap,
}: {
  selectedNodeData: IWorkFlowNode;
  nodes: IWorkFlowNode[];
  variableKeyList: string[];
  globalVariableList: IVarTreeItem[];
  systemVariableList: IVarTreeItem[];
  nodeSchemaMap: Record<string, INodeSchema>;
}) => {
  const inputParams: IInputParamItem[] = [];
  if (!variableKeyList.length) return inputParams;

  const cacheNodeOutputParamsMap: Record<string, IInputParamItem[]> = {};

  variableKeyList.forEach((variableKey) => {
    const match = variableKey.match(/\${([^.]+)\./);
    const nodeId = match ? match[1] : variableKey;
    // if there is no cache, rebuild new data
    if (!cacheNodeOutputParamsMap[nodeId]) {
      switch (nodeId) {
        case 'conversation':
          cacheNodeOutputParamsMap[nodeId] = globalVariableList[0].children.map(
            (item) => ({
              type: item.type,
              key: item.value,
              nodeType: 'conversation',
              nodeTitle: $i18n.get({
                id: 'main.pages.App.Workflow.components.SingleNodeDrawer.index.conversationVariable',
                dm: '会话变量',
              }),
            }),
          );
          break;
        case 'sys':
          cacheNodeOutputParamsMap[nodeId] = systemVariableList[0].children.map(
            (item) => ({
              type: item.type,
              key: item.value,
              nodeType: 'sys',
              nodeTitle: $i18n.get({
                id: 'main.pages.App.Workflow.components.SingleNodeDrawer.index.builtinVariable',
                dm: '内置变量',
              }),
            }),
          );
          break;
        default:
          const node = nodes.find((item) => item.id === nodeId);
          if (node) {
            if (node.id !== selectedNodeData.parentId) {
              // if it is not the parent node of the debug node, the output_params can be referenced normally;
              const outputParams = buildOutputParamsTree({
                outputParams: node.data.output_params,
                nodeId: node.id,
                flat: true,
              });
              cacheNodeOutputParamsMap[nodeId] = outputParams.map((item) => ({
                type: item.type,
                key: item.value,
                nodeTitle: node.data.label,
                nodeType: node.type,
              }));
            } else {
              if (!nodeSchemaMap[node.type].getParentNodeVariableList) return;
              const parentParams =
                nodeSchemaMap[node.type]?.getParentNodeVariableList?.(node);
              if (!parentParams) return;
              // if it is the parent node of the debug node, build the parameters of the parent node;
              cacheNodeOutputParamsMap[nodeId] = parentParams[0].children.map(
                (item) => ({
                  type: item.type,
                  key: item.value,
                  nodeTitle: node.data.label,
                  nodeType: node.type,
                }),
              );
            }
          }
          break;
      }
    }

    // get the corresponding variable from the cache;
    const targetVariableItem = cacheNodeOutputParamsMap[nodeId]?.find(
      (item) => item.key === variableKey,
    );
    if (targetVariableItem) {
      inputParams.push({
        type: targetVariableItem.type,
        key: variableKey,
        value: void 0,
        displayKey: getFinalKey(variableKey),
        nodeTitle: targetVariableItem.nodeTitle,
        nodeType: targetVariableItem.nodeType,
      });
    }
  });

  return inputParams;
};

const getVariableSimpleKey = (key: string) => {
  const match = key.match(/\${([^}]+)}/);
  return match ? match[1] : key;
};

const getDebugErrorText = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message;
  if (error && typeof error === 'object') {
    const detail = error as {
      message?: string;
      response?: { data?: { message?: string; error?: string } };
    };
    return (
      detail.response?.data?.message ||
      detail.response?.data?.error ||
      detail.message ||
      fallback
    );
  }
  return fallback;
};

const SingleNodeDrawer = (props: ISingleNodeDrawer) => {
  const { selectedNodeData } = props;
  const nodeSchemaMap = useStore((state) => state.nodeSchemaMap);
  const nodes = useReactFlowStore((state) => state.nodes) as IWorkFlowNode[];
  const edges = useReactFlowStore((state) => state.edges);
  const appId = useWorkflowAppStore((state) => state.appId);
  const globalVariableList = useWorkflowAppStore(
    (state) => state.globalVariableList,
  );
  const { getSystemVariableList } = useNodesOutputParams();
  const [state, setState] = useSetState({
    inputs: [] as IInputParamItem[],
    taskInfo: null as IWorkFlowTaskProcess | null,
    loading: false,
  });
  const timer = useRef(null as NodeJS.Timeout | null);
  const taskId = useRef<string | null>(null);
  const setShowResults = useStore((state) => state.setShowResults);
  const { updateTaskStore } = useFlowDebugInteraction();

  useMount(() => {
    const systemVariableList = getSystemVariableList();
    const inputs = getInputParams({
      selectedNodeData,
      nodes,
      variableKeyList:
        nodeSchemaMap[selectedNodeData.type]?.getRefVariables?.(
          selectedNodeData.data,
        ) || [],
      globalVariableList,
      nodeSchemaMap,
      systemVariableList,
    });

    setState({
      inputs,
    });
  });

  const clearTimer = () => {
    if (timer.current) {
      clearTimeout(timer.current);
      timer.current = null;
    }
  };

  useUnmount(() => {
    clearTimer();
  });

  // SINGLE_NODE_DEBUG_FIX_V1: surface polling failures and always release loading state.
  const queryTaskStatus = async () => {
    clearTimer();
    if (!taskId.current) return;

    try {
      const res = await getWorkFlowTaskProcess({
        task_id: taskId.current,
      });
      if (!res) {
        throw new Error('后端未返回节点执行状态');
      }

      res.node_results = (res.node_results || []).filter(
        (item) => !['Start', 'End'].includes(item.node_type),
      );
      res.task_results = (res.task_results || []).filter(
        (item) => !['Start', 'End'].includes(item.node_type),
      );
      updateTaskStore(res);
      setState({ taskInfo: res });

      if (res.task_status === 'executing') {
        timer.current = setTimeout(() => {
          void queryTaskStatus();
        }, 500);
        return;
      }

      setState({ loading: false });
      clearTimer();

      if (['fail', 'failed'].includes(String(res.task_status))) {
        const failedNode = res.node_results.find(
          (item) => item.node_status === 'fail' || item.node_status === 'failed',
        ) as unknown as { error_info?: string } | undefined;
        const processError = (res as unknown as { error_info?: string }).error_info;
        message.error(
          failedNode?.error_info ||
            processError ||
            '节点执行失败，请查看运行结果中的错误详情',
        );
      }
    } catch (error) {
      clearTimer();
      setState({ loading: false });
      message.error(getDebugErrorText(error, '查询节点执行结果失败'));
    }
  };

  const handleTest = async () => {
    if (state.loading) return;

    // Classifier nodes must receive a real value for the configured input variable.
    if (selectedNodeData.type === 'Classifier') {
      const inputParam = selectedNodeData.data.input_params?.[0];
      if (!inputParam?.value) {
        message.warning('请先在节点配置中选择输入变量');
        return;
      }
      if ((inputParam.value_from || 'refer') === 'refer') {
        const inputKey = getVariableSimpleKey(String(inputParam.value));
        const inputItem = state.inputs.find(
          (item) => getVariableSimpleKey(item.key) === inputKey,
        );
        if (
          !inputItem ||
          inputItem.value === undefined ||
          inputItem.value === null ||
          String(inputItem.value).trim() === ''
        ) {
          message.warning('请先填写待分类文本，再运行意图分类节点');
          return;
        }
      }
    }

    setState({
      loading: true,
      taskInfo: null,
    });
    taskId.current = null;

    try {
      const extraConfig = nodeSchemaMap[selectedNodeData.type].isGroup
        ? {
            block: {
              nodes: [] as {
                id: string;
                name: string;
                type: string;
                config: IWorkFlowNodeData<INodeDataNodeParam>;
              }[],
              edges: [] as IBizEdge[],
            },
          }
        : {};

      if (nodeSchemaMap[selectedNodeData.type].isGroup) {
        const subFlowNodes = nodes.filter(
          (item) => item.parentId === selectedNodeData.id,
        );
        if (extraConfig.block) {
          extraConfig.block.nodes = subFlowNodes.map((item) => ({
            id: item.id,
            name: item.data.label,
            type: item.type,
            config: item.data,
          }));
          edges.forEach((item) => {
            if (
              subFlowNodes.some((nodeItem) =>
                [item.source, item.target].includes(nodeItem.id),
              )
            ) {
              extraConfig.block.edges.push({
                source: item.source,
                target: item.target,
                id: item.id,
                target_handle: item.targetHandle as string,
                source_handle: item.sourceHandle as string,
              });
            }
          });
        }
      }

      const res = await startPartGraphTask({
        app_id: appId,
        nodes: [
          {
            id: selectedNodeData.id,
            name: selectedNodeData.data.label,
            type: selectedNodeData.type,
            config: {
              ...selectedNodeData.data,
              node_param: {
                ...selectedNodeData.data.node_param,
                ...extraConfig,
              },
            },
          },
        ],
        edges: [],
        input_params: state.inputs.map((item) => ({
          key: getVariableSimpleKey(item.key),
          type: item.type,
          value: item.value,
        })),
      });

      if (!res?.task_id) {
        throw new Error('后端未返回任务 ID，无法查询调试结果');
      }

      taskId.current = res.task_id;
      setShowResults(true);
      void queryTaskStatus();
    } catch (error) {
      clearTimer();
      taskId.current = null;
      setState({ loading: false });
      message.error(getDebugErrorText(error, '启动单节点调试失败，请检查后端日志'));
    }
  };

  const renderTitle = useMemo(() => {
    return (
      <div className="flex items-center gap-[8px]">
        <span className={classNames(styles['drawer-title'], 'font-semibold')}>
          {$i18n.get({
            id: 'main.pages.App.Workflow.components.SingleNodeDrawer.index.testRun',
            dm: '测试运行',
          })}
        </span>
        <div className="flex items-center">
          <div className="size-[28px] flex-center">
            <IconFont type={nodeSchemaMap[selectedNodeData.type].iconType} />
          </div>
          <span className={styles['drawer-title']}>
            {selectedNodeData.data.label}
          </span>
        </div>
      </div>
    );
  }, [selectedNodeData.type, selectedNodeData.data.label, nodeSchemaMap]);

  const changeRowItem = (payload: IInputParamItem) => {
    setState({
      inputs: state.inputs.map((item) =>
        item.key === payload.key ? { ...item, value: payload.value } : item,
      ),
    });
  };

  const handleStop = () => {
    clearTimer();
    setState({
      loading: false,
      taskInfo: {
        ...(state.taskInfo as IWorkFlowTaskProcess),
        task_status: 'interrupted' as IWorkFlowStatus,
        node_results: (state.taskInfo?.node_results || []).map((item) => {
          return {
            ...item,
            node_status: (['executing', 'pause'].includes(item.node_status)
              ? 'interrupted'
              : item.node_status) as IWorkFlowStatus,
          };
        }),
      },
    });
  };

  const handleReset = () => {
    setState({
      inputs: state.inputs.map((item) => ({
        ...item,
        value: void 0,
      })),
    });
  };

  const usageList = useMemo(() => {
    return getSparkFlowUsageList(state.taskInfo);
  }, [state.taskInfo]);

  return (
    <Drawer
      onClose={props.onClose}
      open
      className={styles['drawer']}
      height="90%"
      placement="bottom"
      getContainer={false}
      title={renderTitle}
      maskClassName={styles['drawer-mask']}
    >
      <div className="h-full flex-col flex">
        <div className={styles['test-form']}>
          {!state.inputs.length ? (
            <div className="flex-center">
              <Empty
                size={150}
                description={$i18n.get({
                  id: 'main.pages.App.Workflow.components.SingleNodeDrawer.index暂无输入',
                  dm: '暂无输入',
                })}
              />
            </div>
          ) : (
            <>
              <div className={styles['panel-form-title']}>
                {$i18n.get({
                  id: 'main.pages.App.Workflow.components.SingleNodeDrawer.index.inputVariables',
                  dm: '输入变量',
                })}
              </div>
              <div className={styles['test-form-inputs']}>
                {state.inputs.map((item) => (
                  <div key={item.key} className="flex flex-col gap-[8px]">
                    <div className="flex flex-col gap-[4px]">
                      <div className="flex items-center gap-[4px]">
                        <Flex align="center" gap={2}>
                          <FlowIcon
                            noWidth
                            showBg={false}
                            nodeType={item.nodeType}
                          />

                          <span className={styles['single-var-node-title']}>
                            {item.nodeTitle}
                          </span>
                          {'/'}
                          <Typography.Text
                            style={{ maxWidth: 100 }}
                            className={styles['single-var-name']}
                          >
                            {item.displayKey || item.key}
                          </Typography.Text>
                        </Flex>
                        <span className={styles['single-var-type']}>
                          {item.type}
                        </span>
                      </div>
                      <div className={styles['single-var-desc']}></div>
                    </div>
                    <VariableBaseInput
                      value={item.value}
                      type={item.type}
                      onChange={(val) => {
                        changeRowItem({
                          ...item,
                          value: val.value,
                        });
                      }}
                    />
                  </div>
                ))}
              </div>
            </>
          )}
          <div className="flex gap-[8px]">
            <Button
              loading={state.loading}
              onClick={handleTest}
              color="default"
              variant="solid"
            >
              {state.loading
                ? $i18n.get({
                    id: 'main.pages.App.Workflow.components.SingleNodeDrawer.index.running',
                    dm: '运行中...',
                  })
                : $i18n.get({
                    id: 'main.pages.App.Workflow.components.SingleNodeDrawer.index.run',
                    dm: '运行',
                  })}
            </Button>
            {state.loading && (
              <Button onClick={handleStop}>
                {$i18n.get({
                  id: 'main.pages.App.Workflow.components.SingleNodeDrawer.index.stop',
                  dm: '停止',
                })}
              </Button>
            )}
            <Button disabled={state.loading} onClick={handleReset}>
              {$i18n.get({
                id: 'main.pages.App.Workflow.components.SingleNodeDrawer.index.reset',
                dm: '重置',
              })}
            </Button>
          </div>
        </div>
        <div className={styles['test-result']}>
          <div className={styles['title-wrap']}>
            <span className={styles.title}>
              {$i18n.get({
                id: 'main.pages.App.Workflow.components.SingleNodeDrawer.index.runResult',
                dm: '运行结果',
              })}
            </span>
            {state.taskInfo && (
              <ResultStatus
                usages={usageList}
                status={state.taskInfo.task_status}
                execTime={state.taskInfo.task_exec_time}
              />
            )}
          </div>
          <div className={styles['test-result-content']}>
            {!state.taskInfo ? (
              <Welcome
                data={{}}
                title={$i18n.get({
                  id: 'main.pages.App.Workflow.components.SingleNodeDrawer.index.outputResultDisplayed',
                  dm: '输出结果在这里展示',
                })}
              />
            ) : (
              <NodeResultPanelList data={state.taskInfo.node_results} />
            )}
          </div>
        </div>
      </div>
    </Drawer>
  );
};

export default memo(SingleNodeDrawer);
