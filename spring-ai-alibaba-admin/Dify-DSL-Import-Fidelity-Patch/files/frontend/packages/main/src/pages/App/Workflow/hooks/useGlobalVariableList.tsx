import $i18n from '@/i18n';
import { IGlobalVariableItem } from '@/types/appManage';
import { useCallback } from 'react';
import { useWorkflowAppStore } from '../context/WorkflowAppProvider';

export const useGlobalVariableList = () => {
  const globalVariableList = useWorkflowAppStore(
    (state) => state.globalVariableList,
  );
  const setGlobalVariableList = useWorkflowAppStore(
    (state) => state.setGlobalVariableList,
  );

  const initGlobalVariableList = useCallback(
    (list: IGlobalVariableItem[]) => {
      setGlobalVariableList([
        {
          label: $i18n.get({
            id: 'main.pages.App.Workflow.hooks.useGlobalVariableList.index.systemVariable',
            dm: '系统变量',
          }),
          nodeId: 'sys',
          nodeType: 'sys',
          children: [
            { label: 'query', value: '${sys.query}', type: 'String' },
            { label: 'files', value: '${sys.files}', type: 'Array<File>' },
            {
              label: 'conversation_id',
              value: '${sys.conversation_id}',
              type: 'String',
            },
            { label: 'user_id', value: '${sys.user_id}', type: 'String' },
            {
              label: 'dialogue_count',
              value: '${sys.dialogue_count}',
              type: 'Number',
            },
            {
              label: 'history_list',
              value: '${sys.history_list}',
              type: 'Array<Object>',
            },
          ],
        },
        ...(list?.length
          ? [
              {
                label: $i18n.get({
                  id: 'main.pages.App.Workflow.hooks.useGlobalVariableList.index.conversationVariable',
                  dm: '会话变量',
                }),
                nodeId: 'conversation',
                nodeType: 'conversation',
                children: list.map((item) => ({
                  label: item.key,
                  value: `\${conversation.${item.key}}`,
                  type: item.type,
                })),
              },
            ]
          : []),
      ]);
    },
    [setGlobalVariableList],
  );

  return {
    globalVariableList,
    initGlobalVariableList,
  };
};
