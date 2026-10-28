import $i18n from '@/i18n';
import { getModelParameterRules, getModelSelector } from '@/services/modelService';
import {
  IModel,
  IModelConfigParamItem,
  IModelParameterRule,
  IModelSelectorItem,
  ISelectedModelParams,
} from '@/types/modelService';
import { Button, IconFont, Slider } from '@spark-ai/design';
import { Checkbox, Input, InputNumber, Popover, Select, Switch, Typography, message } from 'antd';
import { memo, useEffect, useMemo, useState } from 'react';
import styles from './index.module.less';

// These are internal execution settings, NOT arguments sent to the provider.
const METADATA_KEY = '__model_metadata__';
type SelectedModel = IModelSelectorItem['models'][0];
type Param = IModelConfigParamItem;
type Rule = IModelParameterRule;
type OptionGroup = {
  label: string;
  options: { label: string; value: string; extra: SelectedModel }[];
};

function stepFromPrecision(precision?: number) {
  return Math.pow(10, -Math.max(0, Math.min(10, precision ?? 0)));
}

function ParameterControls({
  rules, params, disabled, onChange,
}: {
  rules: Rule[];
  params: Param[];
  disabled?: boolean;
  onChange: (next: Param[]) => void;
}) {
  const visible = rules.filter((rule) => rule.code !== METADATA_KEY);
  const metadata = rules.find((rule) => rule.code === METADATA_KEY)?.default_value as any;

  const update = (key: string, patch: Partial<Param>) => {
    const current = params || [];
    const rule = visible.find((r) => r.code === key);
    if (!rule) return;
    const old = current.find((r) => r.key === key);
    const next: Param = {
      key,
      type: (rule.type as Param['type']),
      value: (old?.value ?? rule.default_value ?? null) as Param['value'],
      enable: old?.enable ?? false,
      ...patch,
    };
    onChange([...current.filter((item) => item.key !== key), next]);
  };

  return (
    <Popover
      placement="bottomLeft"
      trigger="click"
      content={
        <div className="flex flex-col gap-3" style={{ minWidth: 330, maxWidth: 460 }}>
          {metadata && (
            <Typography.Text type={metadata.endpoint_type === 'unknown' ? 'danger' : 'secondary'}
              style={{ fontSize: 11 }}>
              {`协议：${metadata.protocol ?? 'unknown'} · 接口：${metadata.endpoint_type ?? 'unknown'}`}
              {` · ${metadata.route_source ?? 'unknown'}`}
              {metadata.endpoint_type === 'unknown' ? '（未验证，请检查模型供应商配置）' : ''}
            </Typography.Text>
          )}
          {visible.length === 0 && <Typography.Text type="secondary">该模型暂无可配置的参数规则</Typography.Text>}
          {visible.map((rule) => {
            const value = params?.find((item) => item.key === rule.code);
            const enabled = value?.enable ?? false;
            const currentValue = value?.value ?? rule.default_value;
            return (
              <div key={rule.code} className="flex-justify-between gap-5">
                <div className="flex items-center gap-2">
                  <Checkbox
                    checked={enabled}
                    disabled={disabled}
                    onChange={(event) => update(rule.code, { enable: event.target.checked })}
                  />
                  <Typography.Text
                    className={styles['model-selector-form-label']}
                    ellipsis={{ tooltip: rule.description || rule.name }}
                    style={{ width: 96 }}
                  >{rule.name || rule.code}</Typography.Text>
                </div>
                {rule.type === 'Number' && (
                  <div className="flex items-center gap-3 flex-1">
                    {rule.min != null && rule.max != null && (
                      <Slider
                        disabled={!enabled || disabled}
                        style={{ width: 170 }}
                        min={rule.min}
                        max={rule.max}
                        step={stepFromPrecision(rule.precision)}
                        value={typeof currentValue === 'number' ? currentValue : undefined}
                        onChange={(val) => update(rule.code, { value: val })}
                      />
                    )}
                    <InputNumber
                      disabled={!enabled || disabled}
                      style={{ width: 90 }}
                      min={rule.min ?? undefined}
                      max={rule.max ?? undefined}
                      precision={rule.precision ?? undefined}
                      value={typeof currentValue === 'number' ? currentValue : null}
                      onChange={(val) => update(rule.code, { value: val as number })}
                    />
                  </div>
                )}
                {rule.type === 'String' && (
                  rule.options?.length ? (
                    <Select
                      disabled={!enabled || disabled}
                      style={{ flex: 1 }}
                      value={currentValue as string}
                      options={rule.options.map((option) => ({ label: String(option), value: String(option) }))}
                      onChange={(val) => update(rule.code, { value: val })}
                    />
                  ) : (
                    <Input
                      disabled={!enabled || disabled}
                      className="flex-1"
                      value={(currentValue ?? '') as string}
                      onChange={(event) => update(rule.code, { value: event.target.value })}
                    />
                  )
                )}
                {rule.type === 'Boolean' && (
                  <Switch
                    disabled={!enabled || disabled}
                    checked={Boolean(currentValue ?? false)}
                    onChange={(checked) => update(rule.code, { value: checked })}
                  />
                )}
              </div>
            );
          })}
        </div>
      }
    >
      <Button disabled={disabled || !rules.length} icon={<IconFont type="spark-modify-line" />} />
    </Popover>
  );
}

const ModelConfigForm = ({ value, onChange, onSelectedModelChange, disabled }: {
  value: ISelectedModelParams;
  onChange: (modelConfig: Partial<ISelectedModelParams>, originalModel: IModel) => void;
  onSelectedModelChange?: (model: IModel) => void;
  disabled?: boolean;
}) => {
  const [groups, setGroups] = useState<OptionGroup[]>([]);
  const [schema, setSchema] = useState<Rule[]>([]);

  useEffect(() => {
    let active = true;
    getModelSelector('llm').then((res) => {
      if (!active) return;
      setGroups((res.data || []).map((item) => ({
        label: item.provider.name,
        options: item.models.map((model) => ({
          label: model.name || model.model_id,
          value: `${model.provider}@@@${model.model_id}`,
          extra: model,
        })),
      })));
    });
    return () => { active = false; };
  }, []);

  const currentModel = useMemo(() => groups.flatMap((group) => group.options)
    .find((option) => option.extra.provider === value.provider
      && option.extra.model_id === value.model_id)?.extra,
  [groups, value.provider, value.model_id]);

  useEffect(() => {
    if (currentModel) onSelectedModelChange?.(currentModel as IModel);
  }, [currentModel]);

  useEffect(() => {
    if (!value.provider || !value.model_id) {
      setSchema([]);
      return;
    }
    let active = true;
    getModelParameterRules(value.provider, value.model_id).then((response) => {
      if (!active) return;
      const fetched = response.data || [];
      setSchema(fetched);
      const systemRule = fetched.find((rule) => rule.code === METADATA_KEY);
      if (!systemRule) return;
      const previous = value.params || [];
      const existing = previous.find((p) => p.key === METADATA_KEY);
      // Preserve published workflow snapshots. Refresh it only when switching
      // models, where parent resets params to an empty array first.
      if (existing) return;
      // Keep complete provider metadata only in PostgreSQL. Store a compact,
      // immutable execution snapshot in the workflow JSON for fast inference.
      const raw = systemRule.default_value as any;
      const snapshot = {
        schema_version: raw?.schema_version ?? 1,
        provider: raw?.provider ?? value.provider,
        model_id: raw?.model_id ?? value.model_id,
        protocol: raw?.protocol ?? 'openai',
        endpoint_type: raw?.endpoint_type ?? 'unknown',
        route_source: raw?.route_source ?? 'unknown',
      };
      const metadataParam = {
        key: METADATA_KEY,
        type: 'Object',
        value: snapshot,
        enable: true,
      } as unknown as Param;
      const original = currentModel || ({ provider: value.provider, model_id: value.model_id,
        name: value.model_id, type: 'llm' } as IModel);
      onChange({ provider: value.provider, model_id: value.model_id,
        params: [...previous.filter((p) => p.key !== METADATA_KEY), metadataParam] }, original as IModel);
    }).catch((error) => {
      if (!active) return;
      setSchema([]);
      message.error(error?.message || '读取供应商模型参数失败');
    });
    return () => { active = false; };
    // The selected model identity is what triggers a fresh schema read.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value.provider, value.model_id]);

  const updateParams = (params: Param[]) => {
    const original = currentModel || ({ provider: value.provider, model_id: value.model_id,
      name: value.model_id, type: 'llm' } as IModel);
    onChange({ provider: value.provider, model_id: value.model_id, params }, original as IModel);
  };

  return (
    <div className="flex items-center gap-2">
      <Select
        disabled={disabled}
        placeholder={$i18n.get({ id: 'main.pages.App.Workflow.nodes.ParameterExtractor.schema.selectModel', dm: '请选择模型' })}
        className="flex-1 w-[240px]"
        value={value.provider && value.model_id ? `${value.provider}@@@${value.model_id}` : undefined}
        options={groups}
        onChange={(_, option: any) => {
          const picked: IModel = option?.extra;
          if (!picked) return;
          setSchema([]);
          // The next effect loads the model-specific schema from PostgreSQL.
          onChange({ provider: picked.provider, model_id: picked.model_id, params: [] }, picked);
        }}
      />
      <ParameterControls rules={schema} params={value.params || []}
        disabled={disabled} onChange={updateParams} />
    </div>
  );
};

export default memo(ModelConfigForm);
