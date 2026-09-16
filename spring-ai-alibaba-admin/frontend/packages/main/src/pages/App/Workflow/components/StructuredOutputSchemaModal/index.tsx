import { JsonSchema } from '../../nodes/LLM/structuredOutput';
import {
  Button,
  Flex,
  Input,
  Modal,
  Select,
  Switch,
  Tabs,
  Typography,
  message,
} from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import styles from './index.module.less';

type FieldKind =
  | 'string'
  | 'number'
  | 'integer'
  | 'boolean'
  | 'object'
  | 'array-string'
  | 'array-number'
  | 'array-integer'
  | 'array-boolean'
  | 'array-object'
  | 'enum';

interface SchemaField {
  id: string;
  key: string;
  kind: FieldKind;
  description: string;
  required: boolean;
  enumValues: string[];
  children: SchemaField[];
}

interface StructuredOutputSchemaModalProps {
  open: boolean;
  value?: JsonSchema;
  readOnly?: boolean;
  onCancel: () => void;
  onOk: (schema: JsonSchema) => void;
}

const TYPE_OPTIONS: Array<{ label: string; value: FieldKind }> = [
  { label: 'string', value: 'string' },
  { label: 'number', value: 'number' },
  { label: 'integer', value: 'integer' },
  { label: 'boolean', value: 'boolean' },
  { label: 'object', value: 'object' },
  { label: 'array[string]', value: 'array-string' },
  { label: 'array[number]', value: 'array-number' },
  { label: 'array[integer]', value: 'array-integer' },
  { label: 'array[boolean]', value: 'array-boolean' },
  { label: 'array[object]', value: 'array-object' },
  { label: 'enum', value: 'enum' },
];

let fieldSeed = 0;
const nextId = () => `structured_schema_${Date.now()}_${fieldSeed++}`;

const detectKind = (schema: JsonSchema): FieldKind => {
  if (Array.isArray(schema?.enum)) return 'enum';
  if (schema?.type === 'array') {
    const itemType = schema?.items?.type;
    if (itemType === 'object') return 'array-object';
    if (itemType === 'number') return 'array-number';
    if (itemType === 'integer') return 'array-integer';
    if (itemType === 'boolean') return 'array-boolean';
    return 'array-string';
  }
  if (
    schema?.type === 'number' ||
    schema?.type === 'integer' ||
    schema?.type === 'boolean' ||
    schema?.type === 'object'
  ) {
    return schema.type as FieldKind;
  }
  return 'string';
};

const schemaPropertiesToFields = (
  schema: JsonSchema = {},
): SchemaField[] => {
  const requiredSet = new Set<string>(schema.required || []);
  return Object.entries(schema.properties || {}).map(([key, rawSchema]) => {
    const property = (rawSchema || {}) as JsonSchema;
    const kind = detectKind(property);
    const nestedSchema = kind === 'array-object' ? property.items || {} : property;
    return {
      id: nextId(),
      key,
      kind,
      description: property.description || '',
      required: requiredSet.has(key),
      enumValues: Array.isArray(property.enum)
        ? property.enum.map((item: unknown) => String(item))
        : [],
      children:
        kind === 'object' || kind === 'array-object'
          ? schemaPropertiesToFields(nestedSchema)
          : [],
    };
  });
};

const fieldsToSchema = (fields: SchemaField[]): JsonSchema => {
  const properties: Record<string, JsonSchema> = {};
  const required: string[] = [];

  fields.forEach((field) => {
    if (!field.key.trim()) return;
    const description = field.description?.trim();
    let property: JsonSchema;

    switch (field.kind) {
      case 'number':
      case 'integer':
      case 'boolean':
      case 'string':
        property = { type: field.kind };
        break;
      case 'enum':
        property = {
          type: 'string',
          enum: field.enumValues.filter((item) => item !== ''),
        };
        break;
      case 'object': {
        const child = fieldsToSchema(field.children);
        property = {
          ...child,
          type: 'object',
        };
        break;
      }
      case 'array-object': {
        const child = fieldsToSchema(field.children);
        property = {
          type: 'array',
          items: {
            ...child,
            type: 'object',
          },
        };
        break;
      }
      case 'array-number':
        property = { type: 'array', items: { type: 'number' } };
        break;
      case 'array-integer':
        property = { type: 'array', items: { type: 'integer' } };
        break;
      case 'array-boolean':
        property = { type: 'array', items: { type: 'boolean' } };
        break;
      case 'array-string':
      default:
        property = { type: 'array', items: { type: 'string' } };
        break;
    }

    if (description) property.description = description;
    properties[field.key.trim()] = property;
    if (field.required) required.push(field.key.trim());
  });

  return {
    type: 'object',
    properties,
    required,
    additionalProperties: false,
  };
};

const validateFields = (
  fields: SchemaField[],
  parentName = 'structured_output',
): string | null => {
  const seen = new Set<string>();
  for (const field of fields) {
    const key = field.key.trim();
    if (!key) return `${parentName} 下存在未填写名称的字段`;
    if (seen.has(key)) return `${parentName} 下字段 ${key} 重复`;
    seen.add(key);
    if (field.kind === 'enum' && !field.enumValues.some(Boolean)) {
      return `${key} 的枚举值不能为空`;
    }
    if (field.kind === 'object' || field.kind === 'array-object') {
      const childError = validateFields(field.children, key);
      if (childError) return childError;
    }
  }
  return null;
};

const makeNewField = (fields: SchemaField[]): SchemaField => {
  const existing = new Set(fields.map((item) => item.key));
  let index = fields.length + 1;
  let key = `field_${index}`;
  while (existing.has(key)) {
    index += 1;
    key = `field_${index}`;
  }
  return {
    id: nextId(),
    key,
    kind: 'string',
    description: '',
    required: false,
    enumValues: [],
    children: [],
  };
};

function FieldEditor(props: {
  fields: SchemaField[];
  onChange: (fields: SchemaField[]) => void;
  readOnly?: boolean;
  depth?: number;
}) {
  const { fields, onChange, readOnly, depth = 0 } = props;

  const updateField = (id: string, patch: Partial<SchemaField>) => {
    onChange(
      fields.map((item) => (item.id === id ? { ...item, ...patch } : item)),
    );
  };

  const deleteField = (id: string) => {
    onChange(fields.filter((item) => item.id !== id));
  };

  return (
    <div className={styles['field-list']}>
      {fields.map((field) => {
        const hasChildren =
          field.kind === 'object' || field.kind === 'array-object';
        return (
          <div className={styles['field-wrap']} key={field.id}>
            <div className={styles['field-row']}>
              <Input
                disabled={readOnly}
                className={styles['field-name']}
                value={field.key}
                onChange={(event) =>
                  updateField(field.id, { key: event.target.value })
                }
                placeholder="字段名称"
              />
              <Select
                disabled={readOnly}
                className={styles['field-type']}
                value={field.kind}
                options={TYPE_OPTIONS}
                onChange={(kind: FieldKind) =>
                  updateField(field.id, {
                    kind,
                    children:
                      kind === 'object' || kind === 'array-object'
                        ? field.children
                        : [],
                    enumValues: kind === 'enum' ? field.enumValues : [],
                  })
                }
              />
              <Input
                disabled={readOnly}
                className={styles['field-description']}
                value={field.description}
                onChange={(event) =>
                  updateField(field.id, { description: event.target.value })
                }
                placeholder="描述"
              />
              <Flex gap={6} align="center" className={styles['required-control']}>
                <Switch
                  size="small"
                  disabled={readOnly}
                  checked={field.required}
                  onChange={(required) => updateField(field.id, { required })}
                />
                <span>必填</span>
              </Flex>
              {!readOnly && (
                <Button
                  type="text"
                  danger
                  size="small"
                  onClick={() => deleteField(field.id)}
                >
                  删除
                </Button>
              )}
            </div>

            {field.kind === 'enum' && (
              <div className={styles['enum-row']}>
                <span className={styles['enum-label']}>枚举值</span>
                <Input
                  disabled={readOnly}
                  value={field.enumValues.join(', ')}
                  placeholder="例如：none, low, medium, high"
                  onChange={(event) =>
                    updateField(field.id, {
                      enumValues: event.target.value
                        .split(',')
                        .map((item) => item.trim())
                        .filter(Boolean),
                    })
                  }
                />
              </div>
            )}

            {hasChildren && (
              <div
                className={styles['children-wrap']}
                style={{ marginLeft: Math.min(depth + 1, 4) * 12 }}
              >
                <FieldEditor
                  fields={field.children}
                  readOnly={readOnly}
                  depth={depth + 1}
                  onChange={(children) => updateField(field.id, { children })}
                />
                {!readOnly && (
                  <Button
                    size="small"
                    onClick={() =>
                      updateField(field.id, {
                        children: [
                          ...field.children,
                          makeNewField(field.children),
                        ],
                      })
                    }
                  >
                    + 添加子字段
                  </Button>
                )}
              </div>
            )}
          </div>
        );
      })}
      {!readOnly && (
        <Button
          size="small"
          className={styles['add-field-btn']}
          onClick={() => onChange([...fields, makeNewField(fields)])}
        >
          + 添加字段
        </Button>
      )}
    </div>
  );
}

const normalizeRootSchema = (schema?: JsonSchema): JsonSchema => {
  if (!schema || schema.type !== 'object') {
    return {
      type: 'object',
      properties: {},
      required: [],
      additionalProperties: false,
    };
  }
  return {
    ...schema,
    properties: schema.properties || {},
    required: schema.required || [],
  };
};

export default function StructuredOutputSchemaModal({
  open,
  value,
  readOnly,
  onCancel,
  onOk,
}: StructuredOutputSchemaModalProps) {
  const [activeTab, setActiveTab] = useState('visual');
  const [fields, setFields] = useState<SchemaField[]>([]);
  const [jsonText, setJsonText] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  const initialSchema = useMemo(() => normalizeRootSchema(value), [value, open]);

  useEffect(() => {
    if (!open) return;
    setFields(schemaPropertiesToFields(initialSchema));
    setJsonText(JSON.stringify(initialSchema, null, 2));
    setActiveTab('visual');
  }, [open, initialSchema]);

  const changeTab = (key: string) => {
    if (key === 'json') {
      setJsonText(JSON.stringify(fieldsToSchema(fields), null, 2));
      setActiveTab(key);
      return;
    }

    try {
      const parsed = normalizeRootSchema(JSON.parse(jsonText));
      setFields(schemaPropertiesToFields(parsed));
      setActiveTab(key);
    } catch (error) {
      message.error('JSON Schema 格式不正确，请修正后再切换到 Visual Editor');
    }
  };

  const handleSave = () => {
    if (activeTab === 'json') {
      try {
        const parsed = JSON.parse(jsonText);
        if (!parsed || parsed.type !== 'object' || !parsed.properties) {
          message.error('根 Schema 必须是 type=object，并包含 properties');
          return;
        }
        onOk(parsed);
      } catch (error) {
        message.error('JSON Schema 不是合法 JSON');
      }
      return;
    }

    const error = validateFields(fields);
    if (error) {
      message.error(error);
      return;
    }
    onOk(fieldsToSchema(fields));
  };

  const handleImportFile = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const raw = String(reader.result || '');
        const parsed = JSON.parse(raw);
        if (!parsed || parsed.type !== 'object' || !parsed.properties) {
          message.error('导入失败：根 Schema 必须是 object');
          return;
        }
        setJsonText(JSON.stringify(parsed, null, 2));
        setFields(schemaPropertiesToFields(parsed));
        setActiveTab('visual');
        message.success('JSON Schema 已导入');
      } catch (error) {
        message.error('导入失败：文件不是合法 JSON');
      }
    };
    reader.readAsText(file);
  };

  return (
    <Modal
      title="结构化输出 Schema"
      open={open}
      onCancel={onCancel}
      onOk={handleSave}
      okText="保存"
      cancelText="取消"
      width={980}
      destroyOnClose
      maskClosable={false}
      okButtonProps={{ disabled: readOnly }}
      className={styles['schema-modal']}
    >
      <div className={styles['toolbar']}>
        <Tabs
          activeKey={activeTab}
          onChange={changeTab}
          items={[
            { key: 'visual', label: '▣ Visual Editor' },
            { key: 'json', label: '{} JSON Schema' },
          ]}
        />
        {!readOnly && (
          <>
            <Button type="link" onClick={() => fileInputRef.current?.click()}>
              从 JSON 导入
            </Button>
            <input
              ref={fileInputRef}
              hidden
              type="file"
              accept=".json,application/json"
              onChange={handleImportFile}
            />
          </>
        )}
      </div>

      {activeTab === 'visual' ? (
        <div className={styles['visual-editor']}>
          <div className={styles['root-title']}>
            <Typography.Text strong>structured_output</Typography.Text>
            <Typography.Text type="secondary">object</Typography.Text>
          </div>
          <div className={styles['root-content']}>
            <FieldEditor
              fields={fields}
              onChange={setFields}
              readOnly={readOnly}
            />
          </div>
        </div>
      ) : (
        <Input.TextArea
          className={styles['json-editor']}
          value={jsonText}
          disabled={readOnly}
          onChange={(event) => setJsonText(event.target.value)}
          spellCheck={false}
        />
      )}
    </Modal>
  );
}
