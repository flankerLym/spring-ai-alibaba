import $i18n from '@/i18n';
import { IBranchItem, IConditionItem } from '@/types/work-flow';
import { generateUniqueName } from '@/utils';
import uniqueId from '@/utils/uniqueId';
import { Button, IconFont } from '@spark-ai/design';
import { Flex, Tag, Tooltip, Typography } from 'antd';
import classNames from 'classnames';
import React, { memo, useCallback } from 'react';
import BranchTitleHeader from '../BranchTitleHeader';
import ConditionItem from '../ConditionItem';
import FlowIcon from '../FlowIcon';
import { IVarTreeItem } from '../VariableTreeSelect';
import './index.less';

export default memo(function JudgeForm(props: {
  value: IBranchItem[];
  onChange: (val: IBranchItem[]) => void;
  leftVariableList: IVarTreeItem[];
  rightVariableList: IVarTreeItem[];
  disabled?: boolean;
  areaStyle?: React.CSSProperties;
  branchNextNodes?: Record<
    string,
    { id: string; label: string; type?: string }[]
  >;
}) {
  const { value = [], onChange, disabled, areaStyle, branchNextNodes } = props;

  const changeBranchItem = useCallback(
    (id: string, payload: Partial<IBranchItem>) => {
      onChange(
        value.map((item) => (item.id === id ? { ...item, ...payload } : item)),
      );
    },
    [value, onChange],
  );

  const changeConditionItem = useCallback(
    (branchId: string, condIndex: number, payload: Partial<IConditionItem>) => {
      changeBranchItem(branchId, {
        conditions:
          value
            .find((item) => item.id === branchId)
            ?.conditions?.map((item, index) =>
              index === condIndex ? { ...item, ...payload } : item,
            ) || [],
      });
    },
    [value, changeBranchItem],
  );

  const addBranchItem = useCallback(() => {
    onChange([
      ...value,
      {
        id: uniqueId(4),
        label: generateUniqueName(
          $i18n.get({
            id: 'spark-flow.JudgeForm.index.newConditionGroup',
            dm: '新条件组',
          }),
          value.map((item) => item.label),
        ),
        logic: 'and',
        conditions: [
          {
            left: {
              value_from: 'refer',
              type: 'String',
              value: void 0,
            },
            right: {
              value_from: 'input',
              type: 'String',
              value: void 0,
            },
            operator: void 0,
          },
        ],
      },
    ]);
  }, [value]);

  const addConditionItem = useCallback(
    (branchId: string) => {
      const targetBranch = value.find((item) => item.id === branchId);
      if (!targetBranch) return;
      changeBranchItem(branchId, {
        conditions: [
          ...(targetBranch.conditions || []),
          {
            left: {
              value_from: 'refer',
              type: 'String',
              value: void 0,
            },
            right: {
              value_from: 'input',
              type: 'String',
              value: void 0,
            },
            operator: void 0,
          },
        ],
      });
    },
    [value, changeBranchItem],
  );

  const deleteConditionItem = useCallback(
    (branchId: string, condIndex: number) => {
      changeBranchItem(branchId, {
        conditions:
          value
            .find((item) => item.id === branchId)
            ?.conditions?.filter((_, index) => index !== condIndex) || [],
      });
    },
    [value, changeBranchItem],
  );

  const deleteBranchItem = useCallback(
    (branchId: string) => {
      onChange(value.filter((item) => item.id !== branchId));
    },
    [value, onChange],
  );

  const renderNextNodes = (branchId: string) => {
    const nextNodes = branchNextNodes?.[branchId] || [];
    return (
      <div className="spark-flow-judge-next-nodes">
        <span className="spark-flow-judge-next-nodes-label">
          {$i18n.get({
            id: 'spark-flow.JudgeForm.index.nextNode',
            dm: '下一个节点',
          })}
        </span>
        <span className="spark-flow-judge-next-nodes-arrow">→</span>
        {nextNodes.length > 0 ? (
          <div className="spark-flow-judge-next-nodes-list">
            {nextNodes.map((node) => (
              <Tooltip key={node.id} title={node.label} placement="topLeft">
                <Tag className="spark-flow-judge-next-node-chip" bordered={false}>
                  <span className="spark-flow-judge-next-node-content">
                    {node.type && <FlowIcon nodeType={node.type} size="small" />}
                    <Typography.Text ellipsis>{node.label}</Typography.Text>
                  </span>
                </Tag>
              </Tooltip>
            ))}
          </div>
        ) : (
          <span className="spark-flow-judge-next-nodes-empty">
            {$i18n.get({
              id: 'spark-flow.JudgeForm.index.noNextNode',
              dm: '未连接',
            })}
          </span>
        )}
      </div>
    );
  };

  return (
    <>
      {value.map((item) => {
        if (item.id === 'default') return null;
        return (
          <div
            key={item.id}
            style={areaStyle}
            className="spark-flow-panel-form-section spark-flow-judge-case-card"
          >
            <Flex vertical gap={16}>
              <div className="spark-flow-judge-branch-header-wrap">
                <BranchTitleHeader
                  data={item}
                  onChange={(payload) => changeBranchItem(item.id, payload)}
                  deleteBranchItem={() => deleteBranchItem(item.id)}
                  branches={value}
                  disabled={disabled}
                />
                {renderNextNodes(item.id)}
              </div>

              {(item.conditions || []).map((condition, index) => (
                <Flex key={index} gap={8}>
                  <div className="flex-1 flex-start">
                    <ConditionItem
                      disabled={disabled}
                      onChange={(val) =>
                        changeConditionItem(item.id, index, val)
                      }
                      value={condition}
                      leftVariableList={props.leftVariableList}
                      rightVariableList={props.rightVariableList}
                    />
                  </div>
                  <div
                    className={classNames(
                      'py-[8px] flex-shrink-0',
                      'spark-flow-judge-form-delete-btn-wrap',
                    )}
                  >
                    <IconFont
                      onClick={() => {
                        deleteConditionItem(item.id, index);
                      }}
                      className={disabled ? 'disabled-icon-btn' : ''}
                      size="small"
                      isCursorPointer={!disabled}
                      type="spark-delete-line"
                    />
                  </div>
                </Flex>
              ))}
              <Button
                onClick={() => addConditionItem(item.id)}
                icon={<IconFont type="spark-plus-line" />}
                type="dashed"
                disabled={disabled}
                className="spark-flow-judge-form-add-branch-btn"
              >
                {$i18n.get({
                  id: 'spark-flow.JudgeForm.index.addCondition',
                  dm: '添加条件',
                })}
              </Button>
            </Flex>
          </div>
        );
      })}
      <div
        style={areaStyle}
        className="spark-flow-panel-form-section spark-flow-judge-case-card spark-flow-judge-default-card"
      >
        <Flex vertical gap={8}>
          <Flex align="center" justify="space-between">
            <div className="spark-flow-panel-form-title">
              {$i18n.get({
                id: 'spark-flow.JudgeForm.index.defaultBranch',
                dm: '默认',
              })}
            </div>
            <span>ELSE</span>
          </Flex>
          {renderNextNodes('default')}
        </Flex>
      </div>
      <div style={areaStyle} className="spark-flow-panel-form-section">
        <Button
          onClick={() => addBranchItem()}
          icon={<IconFont type="spark-plus-line" />}
          type="dashed"
          className="spark-flow-judge-form-add-branch-btn"
          disabled={disabled}
        >
          {$i18n.get({
            id: 'spark-flow.JudgeForm.index.addConditionGroup',
            dm: '添加条件组',
          })}
        </Button>
      </div>
    </>
  );
});
