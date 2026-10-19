import $i18n from '@/i18n';
import type { IWorkFlowNode } from '@spark-ai/flow';
import { BaseNode, NodeProps, SourceHandle } from '@spark-ai/flow';
import { Typography } from 'antd';
import { memo } from 'react';
import { IClassifierNodeParam } from '../../types';

export default memo(function Classifier(props: NodeProps<IWorkFlowNode>) {
  const nodeParam = props.data.node_param as IClassifierNodeParam;
  const conditions = (nodeParam.conditions || []).filter(
    (item) => item.id !== 'default',
  );

  return (
    <BaseNode disableShowSourceHandle {...props}>
      {conditions.map((item) => (
        <div
          key={item.id}
          className="spark-flow-judge-branch flex-justify-between"
        >
          <Typography.Text
            ellipsis={{ tooltip: item.subject }}
            style={{ maxWidth: 200, fontSize: 12 }}
          >
            {item.subject ||
              $i18n.get({
                id: 'main.pages.App.Workflow.nodes.Classifier.node.noIntentionConfigured',
                dm: '暂未配置意图',
              })}
          </Typography.Text>
          <SourceHandle
            className="spark-flow-judge-branch-handle"
            nodeType={props.type}
            nodeId={props.id}
            handleId={`${props.id}_${item.id}`}
          />
        </div>
      ))}
    </BaseNode>
  );
});
