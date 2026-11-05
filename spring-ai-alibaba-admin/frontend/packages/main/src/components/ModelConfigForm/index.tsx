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

const text = (key: string) => $i18n.get(`modelParams.${key}`);
const translated = (key: string) => {
  const value = text(key);
  return value === `modelParams.${key}` ? undefined : value;
};
const isChinese = () => $i18n.getCurrentLanguage().toLowerCase().startsWith('zh');
const hasChinese = (value: string) => /[\u3400-\u9fff]/.test(value);

function parameterName(rule: Rule) {
  return translated(`name.${rule.code}`)
    || (!isChinese() && hasChinese(rule.name || '') ? rule.code : rule.name || rule.code);
}

function parameterDescription(rule: Rule) {
  const help = rule.help || {};
  const localized = isChinese() ? help.zh || help['zh-cn'] || help.zh_Hans || help.zh_CN
    : help.en || help['en-us'] || help.en_US;
  return localized || translated(`description.${rule.code}`)
    || (rule.description && (isChinese() || !hasChinese(rule.description)) ? rule.description : text('genericDescription'));
}

function metadataText(value?: string) {
  return translated(`meta.${value || 'unknown'}`) || value || text('unknown');
}

function normalizeOptions(options?: any[]) {
  return (options || []).map((option) => {
    if (option && typeof option === 'object' && 'value' in option) {
      const label = String(option.label ?? option.value);
      return { label: translated(`option.${option.value}`)
        || (!isChinese() && hasChinese(label) ? String(option.value) : label), value: option.value };
    }
    return { label: translated(`option.${option}`) || String(option), value: option };
  });
}

function JsonParameterEditor({ value, disabled, onChange }: {
  value: any; disabled?: boolean; onChange: (value: any) => void;
}) {
  const [draft, setDraft] = useState(JSON.stringify(value ?? {}, null, 2));
  const [error, setError] = useState(false);
  useEffect(() => { setDraft(JSON.stringify(value ?? {}, null, 2)); setError(false); }, [value]);
  return <div style={{ width: '100%' }}>
    <Input.TextArea disabled={disabled} autoSize={{ minRows: 2, maxRows: 10 }}
      status={error ? 'error' : undefined} value={draft}
      onChange={(event) => {
        const text = event.target.value;
        setDraft(text);
        try { const parsed = JSON.parse(text); setError(false); onChange(parsed); }
        catch { setError(true); }
      }} />
    {error && <Typography.Text type="danger">{text('invalidJson')}</Typography.Text>}
  </div>;
}

function ParameterControls({
  rules, params, disabled, onChange,
}: {
  rules: Rule[];
  params: Param[];
  disabled?: boolean;
  onChange: (next: Param[]) => void;
}) {
  const visible = [
    ...rules.filter((rule) => !rule.code.startsWith('__') && !['model', 'messages', 'stream', 'input', 'result_format', 'incremental_output', 'multi_model'].includes(rule.code)),
    ...params.filter((param) => !param.key.startsWith('__') && !rules.some((rule) => rule.code === param.key))
      .map((param) => ({ code: param.key, name: param.key, type: param.type } as Rule)),
  ];
  const [newKey, setNewKey] = useState('');
  const metadata = rules.find((rule) => rule.code === METADATA_KEY)?.default_value as any;

  const update = (key: string, patch: Partial<Param>) => {
    const current = params || [];
    const rule = visible.find((item) => item.code === key);
    if (!rule) return;
    const old = current.find((item) => item.key === key);
    const next: Param = {
      key,
      type: rule.type,
      value: old?.value ?? rule.default_value ?? null,
      enable: old?.enable ?? Boolean(rule.required),
      ...patch,
    };
    onChange([...current.filter((item) => item.key !== key), next]);
  };

  return (
    <Popover
      placement="bottomLeft"
      trigger="click"
      content={
        <div className="flex flex-col gap-3" style={{ minWidth: 340, maxWidth: 600, maxHeight: '70vh', overflowY: 'auto' }}>
          {metadata && (
            <Typography.Text
              type={metadata.endpoint_type === 'unknown' ? 'danger' : 'secondary'}
              style={{ fontSize: 11 }}
            >
              {`${text('protocol')}: ${metadata.protocol ?? text('unknown')} · ${text('endpoint')}: ${metadataText(metadata.endpoint_type)}`}
              {` · ${text('source')}: ${metadataText(metadata.parameter_source)}`}
              {metadata.endpoint_id ? ` · ${text('endpointId')}: ${metadata.endpoint_id}` : ''}
              {metadata.endpoint_type === 'unknown' ? text('unverified') : ''}
            </Typography.Text>
          )}
          {!visible.length && (
            <Typography.Text type="secondary">{text('empty')}</Typography.Text>
          )}
          {visible.map((rule) => {
            const item = params?.find((param) => param.key === rule.code);
            const enabled = item?.enable ?? Boolean(rule.required);
            const currentValue = item?.value ?? rule.default_value;
            const options = normalizeOptions(rule.options);
            const hasOptions = options.length > 0 && !['Object', 'Boolean'].includes(rule.type)
              && !rule.type.startsWith('Array<');

            return (
              <div key={rule.code} className="flex-justify-between gap-4">
                <div className="flex items-center gap-2">
                  <Checkbox
                    checked={enabled}
                    disabled={disabled || Boolean(rule.required)}
                    onChange={(event) => update(rule.code, { enable: event.target.checked })}
                  />
                  <Typography.Text
                    className={styles['model-selector-form-label']}
                    ellipsis={{ tooltip: `${rule.code}: ${parameterDescription(rule)}` }}
                    style={{ width: 112 }}
                  >
                    {parameterName(rule)}
                  </Typography.Text>
                </div>

                <div className="flex items-center gap-2 flex-1 min-w-0">
                  {hasOptions ? (
                    <Select
                      disabled={!enabled || disabled}
                      style={{ width: '100%' }}
                      value={currentValue}
                      options={options}
                      onChange={(value) => update(rule.code, { value })}
                    />
                  ) : rule.type === 'Number' ? (
                    <>
                      {rule.min != null && rule.max != null && (
                        <Slider
                          disabled={!enabled || disabled}
                          style={{ flex: 1, minWidth: 80 }}
                          min={rule.min}
                          max={rule.max}
                          step={stepFromPrecision(rule.precision)}
                          value={typeof currentValue === 'number' ? currentValue : Number(currentValue || 0)}
                          onChange={(value) => update(rule.code, { value })}
                        />
                      )}
                      <InputNumber
                        disabled={!enabled || disabled}
                        style={{ width: 92 }}
                        min={rule.min ?? undefined}
                        max={rule.max ?? undefined}
                        precision={rule.precision ?? undefined}
                        value={typeof currentValue === 'number' ? currentValue : null}
                        onChange={(value) => update(rule.code, { value })}
                      />
                    </>
                  ) : rule.type === 'Boolean' ? (
                    <Switch
                      disabled={!enabled || disabled}
                      checked={Boolean(currentValue ?? false)}
                      onChange={(value) => update(rule.code, { value })}
                    />
                  ) : rule.type === 'Object' || rule.type.startsWith('Array<') ? (
                    <JsonParameterEditor
                      disabled={!enabled || disabled}
                      value={currentValue ?? (rule.type.startsWith('Array<') ? [] : {})}
                      onChange={(value) => update(rule.code, { value })}
                    />
                  ) : (
                    <Input
                      disabled={!enabled || disabled}
                      value={String(currentValue ?? '')}
                      onChange={(event) => update(rule.code, { value: event.target.value })}
                    />
                  )}
                </div>
              </div>
            );
          })}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text('hint')}
          </Typography.Text>
          <div className="flex gap-2">
            <Input disabled={disabled} placeholder={text('extensionName')} value={newKey}
              onChange={(event) => setNewKey(event.target.value)} />
            <Button disabled={disabled} onClick={() => {
              const key = newKey.trim();
              if (!/^[a-zA-Z][a-zA-Z0-9_]*$/.test(key) || visible.some((rule) => rule.code === key)
                  || ['model', 'messages', 'stream', 'input', 'result_format', 'incremental_output', 'multi_model'].includes(key)) {
                message.error(text('invalidName')); return;
              }
              onChange([...params, { key, type: 'Object', value: {}, enable: false }]);
              setNewKey('');
            }}>{text('add')}</Button>
          </div>
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
    }).catch((error) => {
      if (active) message.error(text('loadModelsFailed'));
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


      const raw = systemRule.default_value as any;
      const snapshot = { ...raw, raw_metadata: undefined };
      const previousMetadata = previous.find((param) => param.key === METADATA_KEY)?.value;
      if (JSON.stringify(previousMetadata) === JSON.stringify(snapshot)) return;
      const metadataParam = {
        key: METADATA_KEY,
        type: 'Object',
        value: snapshot,
        enable: true,
      } as Param;
      const original = currentModel || ({
        provider: value.provider, model_id: value.model_id,
        name: value.model_id, type: 'llm',
      } as IModel);
      onChange({
        provider: value.provider,
        model_id: value.model_id,
        params: [...previous.filter((param) => param.key !== METADATA_KEY), metadataParam],
      }, original as IModel);
    }).catch((error) => {
      if (!active) return;
      setSchema([]);
      message.error(text('loadRulesFailed'));
    });
    return () => { active = false; };
    // The selected model identity is what triggers a fresh schema read.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value.provider, value.model_id]);

  const updateParams = (params: Param[]) => {
    const original = currentModel || ({
      provider: value.provider, model_id: value.model_id,
      name: value.model_id, type: 'llm',
    } as IModel);
    onChange({ provider: value.provider, model_id: value.model_id, params }, original as IModel);
  };

  return (
    <div className="flex items-center gap-2">
      <Select
        disabled={disabled}
        placeholder={$i18n.get({
          id: 'main.pages.App.Workflow.nodes.ParameterExtractor.schema.selectModel',
          dm: '请选择模型',
        })}
        className="flex-1 w-[240px]"
        value={value.provider && value.model_id ? `${value.provider}@@@${value.model_id}` : undefined}
        options={groups}
        onChange={(_, option: any) => {
          const picked: IModel = option?.extra;
          if (!picked) return;
          setSchema([]);
          onChange({ provider: picked.provider, model_id: picked.model_id, params: [] }, picked);
        }}
      />
      <ParameterControls
        rules={schema}
        params={value.params || []}
        disabled={disabled}
        onChange={updateParams}
      />
    </div>
  );
};

export default memo(ModelConfigForm);
