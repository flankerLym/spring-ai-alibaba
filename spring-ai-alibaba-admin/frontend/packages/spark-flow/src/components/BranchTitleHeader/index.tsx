import $i18n from '@/i18n';
import { IBranchItem } from '@/types/work-flow';
import { getBranchDisplayLabel } from '@/utils/branchLabel';
import { IconFont, Select } from '@spark-ai/design';
import { useSetState } from 'ahooks';
import { Flex, Input, message, Typography } from 'antd';
import React, { memo, useCallback, useRef } from 'react';
import './index.less';

export interface IBranchTitleHeaderProps {
  data: IBranchItem;
  onChange: (data: Partial<IBranchItem>) => void;
  deleteBranchItem: () => void;
  branches: IBranchItem[];
  disabled?: boolean;
}

export default memo(function BranchTitleHeader(props: IBranchTitleHeaderProps) {
  const displayLabel = getBranchDisplayLabel(props.data, props.branches);
  const [state, setState] = useSetState({
    isEdit: false,
    tempName: displayLabel,
  });
  const isComposingRef = useRef(false);

  const startEdit = useCallback(() => {
    if (props.disabled) return;
    setState({
      isEdit: true,
      tempName: displayLabel,
    });
  }, [displayLabel, props.disabled, setState]);

  const handleSure = () => {
    const nextName = state.tempName.trim();
    if (!nextName) {
      message.warning(
        $i18n.get({
          id: 'spark-flow.BranchTitleHeader.index.conditionGroupNameEmpty',
          dm: '条件组名称不能为空',
        }),
      );
      return;
    }

    if (
      props.branches.some(
        (item) =>
          item.id !== props.data.id &&
          getBranchDisplayLabel(item, props.branches) === nextName,
      )
    ) {
      message.warning(
        $i18n.get({
          id: 'spark-flow.BranchTitleHeader.index.conditionGroupNameExists',
          dm: '条件组名称已存在',
        }),
      );
      return;
    }

    // Persist the user-facing name in the branch label. The branch ID and
    // its edge/handle IDs remain unchanged.
    props.onChange({ label: nextName });
    setState({ isEdit: false, tempName: nextName });
  };

  const cancelEdit = useCallback(() => {
    setState({
      isEdit: false,
      tempName: displayLabel,
    });
  }, [displayLabel, setState]);

  // Keep Chinese IME composition from triggering an early Enter-to-save.
  const handleCompositionStart = useCallback(() => {
    isComposingRef.current = true;
  }, []);

  const handleCompositionEnd = useCallback(() => {
    isComposingRef.current = false;
  }, []);

  const handlePressEnter = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (isComposingRef.current) return;
      handleSure();
      e.preventDefault();
    },
    [handleSure],
  );

  const handleLabelKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLElement>) => {
      if (props.disabled || (e.key !== 'Enter' && e.key !== ' ')) return;
      e.preventDefault();
      startEdit();
    },
    [props.disabled, startEdit],
  );

  return (
    <Flex justify="space-between">
      {state.isEdit ? (
        <Flex className="flex-1" gap={8} align="center">
          <Input
            autoFocus
            maxLength={64}
            disabled={props.disabled}
            onFocus={(e) => e.currentTarget.select()}
            onCompositionStart={handleCompositionStart}
            onCompositionEnd={handleCompositionEnd}
            onPressEnter={handlePressEnter}
            value={state.tempName}
            onChange={(e) => setState({ tempName: e.target.value })}
            placeholder="输入分支名称，例如：用户咨询"
          />

          <IconFont
            onClick={handleSure}
            isCursorPointer
            className="spark-flow-name-input-ok-btn"
            type="spark-true-line"
          />

          <IconFont
            onClick={cancelEdit}
            isCursorPointer
            type="spark-false-line"
          />
        </Flex>
      ) : (
        <>
          <Flex align="center" gap={16}>
            <Typography.Text
              ellipsis={{ tooltip: `${displayLabel}（点击可修改名称）` }}
              style={{
                maxWidth: 120,
                cursor: props.disabled ? 'default' : 'pointer',
              }}
              className="spark-flow-panel-form-title"
              role={props.disabled ? undefined : 'button'}
              tabIndex={props.disabled ? -1 : 0}
              onClick={startEdit}
              onKeyDown={handleLabelKeyDown}
            >
              {displayLabel}
            </Typography.Text>
            <Flex align="center" gap={4}>
              <span>
                {$i18n.get({
                  id: 'spark-flow.BranchTitleHeader.index.whenSatisfy',
                  dm: '当满足以下',
                })}
              </span>
              <Select
                disabled={props.disabled}
                className="spark-flow-logic-selector"
                value={props.data.logic}
                onChange={(val) => props.onChange({ logic: val })}
                options={[
                  {
                    label: $i18n.get({
                      id: 'spark-flow.BranchTitleHeader.index.all',
                      dm: '所有',
                    }),
                    value: 'and',
                  },
                  {
                    label: $i18n.get({
                      id: 'spark-flow.BranchTitleHeader.index.any',
                      dm: '任意',
                    }),
                    value: 'or',
                  },
                ]}
                popupMatchSelectWidth={false}
                size="small"
                variant="borderless"
              />

              <span>
                {$i18n.get({
                  id: 'spark-flow.BranchTitleHeader.index.conditions',
                  dm: '条件时',
                })}
              </span>
            </Flex>
          </Flex>
          <Flex gap={12}>
            <IconFont
              className={props.disabled ? 'disabled-icon-btn' : ''}
              onClick={startEdit}
              size="small"
              isCursorPointer={!props.disabled}
              type="spark-edit-line"
            />

            <IconFont
              onClick={props.disabled ? void 0 : props.deleteBranchItem}
              className={props.disabled ? 'disabled-icon-btn' : ''}
              size="small"
              isCursorPointer={!props.disabled}
              type="spark-delete-line"
            />
          </Flex>
        </>
      )}
    </Flex>
  );
});
