import { getAppDetail, updateApp } from '@/services/appManage';
import { IWorkFlowAppDetail } from '@/types/appManage';
import { Button, Drawer, IconFont, Input } from '@spark-ai/design';
import { Flex, message, Switch } from 'antd';
import { useState } from 'react';
import { useWorkflowAppStore } from '../../context/WorkflowAppProvider';

type PrologueConfig = {
  enabled?: boolean;
  prologue_text?: string;
};

export const WORKFLOW_PROLOGUE_UPDATED_EVENT =
  'workflow-prologue-updated';

export default function WorkflowFeatureConfigBtn() {
  const appId = useWorkflowAppStore((state) => state.appId);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [saveLoading, setSaveLoading] = useState(false);
  const [appDetail, setAppDetail] = useState<IWorkFlowAppDetail>();
  const [enabled, setEnabled] = useState(false);
  const [prologue, setPrologue] = useState('');

  const openDrawer = async () => {
    if (!appId) return;
    setOpen(true);
    setLoading(true);
    try {
      const detail = (await getAppDetail(appId)) as IWorkFlowAppDetail;
      const config = ((detail.config as any)?.prologue || {}) as PrologueConfig;
      setAppDetail(detail);
      setEnabled(
        config.enabled === undefined
          ? Boolean(config.prologue_text?.trim())
          : Boolean(config.enabled),
      );
      setPrologue(config.prologue_text || '');
    } catch (error) {
      message.error(
        error instanceof Error ? error.message : '读取功能配置失败',
      );
      setOpen(false);
    } finally {
      setLoading(false);
    }
  };

  const handleSave = async () => {
    if (!appDetail) return;
    if (enabled && !prologue.trim()) {
      message.warning('请输入开场白');
      return;
    }

    setSaveLoading(true);
    try {
      const oldPrologue = ((appDetail.config as any)?.prologue ||
        {}) as Record<string, any>;
      const nextPrologue = {
        ...oldPrologue,
        enabled,
        prologue_text: prologue.trim(),
      };

      await updateApp({
        app_id: appDetail.app_id,
        name: appDetail.name,
        type: appDetail.type,
        config: {
          ...appDetail.config,
          prologue: nextPrologue,
        } as any,
      });

      window.dispatchEvent(
        new CustomEvent(WORKFLOW_PROLOGUE_UPDATED_EVENT, {
          detail: {
            ...nextPrologue,
            name: appDetail.name,
          },
        }),
      );

      message.success('保存成功');
      setOpen(false);
    } catch (error) {
      message.error(
        error instanceof Error ? error.message : '保存开场白失败',
      );
    } finally {
      setSaveLoading(false);
    }
  };

  return (
    <>
      <Button onClick={openDrawer}>功能</Button>

      {open && (
        <Drawer
          title="功能"
          width={420}
          open
          onClose={() => setOpen(false)}
          footer={
            <Flex justify="flex-end" gap={12}>
              <Button onClick={() => setOpen(false)}>取消</Button>
              <Button
                type="primary"
                loading={saveLoading}
                disabled={loading}
                onClick={handleSave}
              >
                保存
              </Button>
            </Flex>
          }
        >
          <div
            style={{
              color: 'var(--ag-ant-color-text-secondary)',
              fontSize: 13,
              marginBottom: 16,
            }}
          >
            增强工作流应用的对话体验
          </div>

          <div
            style={{
              border: '1px solid var(--ag-ant-color-border-secondary)',
              borderRadius: 12,
              padding: 16,
              background: 'var(--ag-ant-color-fill-quaternary)',
              opacity: loading ? 0.6 : 1,
            }}
          >
            <Flex justify="space-between" align="flex-start" gap={12}>
              <Flex gap={12} align="flex-start">
                <div
                  style={{
                    width: 36,
                    height: 36,
                    borderRadius: 10,
                    background: 'var(--ag-ant-color-primary-bg)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    flexShrink: 0,
                  }}
                >
                  <IconFont type="spark-text-line" />
                </div>

                <div>
                  <div
                    style={{
                      fontSize: 16,
                      fontWeight: 600,
                      color: 'var(--ag-ant-color-text)',
                    }}
                  >
                    对话开场白
                  </div>
                  <div
                    style={{
                      marginTop: 6,
                      fontSize: 13,
                      lineHeight: '20px',
                      color: 'var(--ag-ant-color-text-secondary)',
                    }}
                  >
                    在文本对话测试开始时展示一段欢迎语。
                  </div>
                </div>
              </Flex>

              <Switch
                checked={enabled}
                disabled={loading}
                onChange={setEnabled}
              />
            </Flex>

            {enabled && (
              <div style={{ marginTop: 16 }}>
                <Input.TextArea
                  value={prologue}
                  disabled={loading}
                  onChange={(event) => setPrologue(event.target.value)}
                  placeholder="请输入开场白，例如：你好，我是智能心理助手，有什么可以帮你？"
                  rows={4}
                  maxLength={2000}
                  showCount
                />
              </div>
            )}
          </div>
        </Drawer>
      )}
    </>
  );
}
