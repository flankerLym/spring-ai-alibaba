import $i18n from '@/i18n';
import {
  JudgeForm,
  useNodeDataUpdate,
  useNodesOutputParams,
  useNodesReadOnly,
  useReactFlowStore,
} from '@spark-ai/flow';
import { memo, useCallback, useMemo } from 'react';
import { useWorkflowAppStore } from '../../context/WorkflowAppProvider';
import { IJudgeNodeData, IJudgeNodeParam } from '../../types';

export default memo(function JudgePanel(props: {
  id: string;
  data: IJudgeNodeData;
}) {
  const { handleNodeDataUpdate } = useNodeDataUpdate();
  const { getVariableList } = useNodesOutputParams();
  const globalVariableList = useWorkflowAppStore(
    (state) => state.globalVariableList,
  );
  const nodes = useReactFlowStore((store) => store.nodes);
  const edges = useReactFlowStore((store) => store.edges);
  const { nodesReadOnly } = useNodesReadOnly();

  const flowVariableList = useMemo(() => {
    return getVariableList({
      nodeId: props.id,
    });
  }, [props.id, nodes, edges]);

  const variableList = useMemo(() => {
    return [...globalVariableList, ...flowVariableList];
  }, [globalVariableList, flowVariableList]);

  // Resolve each CASE's outgoing edges to readable downstream node names.
  // Branch IDs and source-handle IDs are read-only here; workflow execution is unchanged.
  const branchNextNodes = useMemo(() => {
    const branchIds = [
      ...(props.data.node_param.branches || []).map((branch) => branch.id),
      'default',
    ];
    const nodeById = new Map<string, (typeof nodes)[number]>();
    nodes.forEach((node) => nodeById.set(node.id, node));
    const result: Record<string, { id: string; label: string }[]> = {};

    branchIds.forEach((branchId) => {
      const nextNodeMap = new Map<string, { id: string; label: string }>();
      const sourceHandleId = `${props.id}_${branchId}`;

      edges.forEach((edge) => {
        if (edge.source !== props.id || edge.sourceHandle !== sourceHandleId) {
          return;
        }
        const targetNode = nodeById.get(edge.target);
        if (!targetNode) return;

        const label =
          String(targetNode.data?.label || '').trim() ||
          targetNode.type ||
          '未命名节点';
        nextNodeMap.set(targetNode.id, { id: targetNode.id, label });
      });

      result[branchId] = Array.from(nextNodeMap.values());
    });

    return result;
  }, [edges, nodes, props.id, props.data.node_param.branches]);

  const changeNodeParam = useCallback(
    (payload: Partial<IJudgeNodeParam>) => {
      handleNodeDataUpdate({
        id: props.id,
        data: {
          node_param: {
            ...props.data.node_param,
            ...payload,
          },
        },
      });
    },
    [props.data.node_param, handleNodeDataUpdate, props.id],
  );

  return (
    <>
      <JudgeForm
        leftVariableList={variableList}
        rightVariableList={variableList}
        value={props.data.node_param.branches}
        onChange={(val) => changeNodeParam({ branches: val })}
        branchNextNodes={branchNextNodes}
        disabled={nodesReadOnly}
      />

      <div className="spark-flow-panel-form-section">
        <div className="spark-flow-panel-form-title">
          {$i18n.get({
            id: 'main.pages.App.Workflow.nodes.Judge.panel.other',
            dm: '其他',
          })}
        </div>
      </div>
    </>
  );
});
