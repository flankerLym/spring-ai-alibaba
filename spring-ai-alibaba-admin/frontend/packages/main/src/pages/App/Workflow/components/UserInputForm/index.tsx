import $i18n from '@/i18n';
import { Button, Form, Input } from '@spark-ai/design';
import { IUserInputItem, IWorkFlowTaskResultItem } from '@spark-ai/flow';
import { memo, useEffect, useMemo, useState } from 'react';
import styles from './index.module.less';

export interface IUserInputSubmitParams {
  input_params: Array<{ key: string; value: string }>;
  resume_node_id: string;
  resume_parent_id?: string;
}

export interface IUserInputFormProps {
  onSubmit: (params: IUserInputSubmitParams) => void | Promise<unknown>;
  nodeData: IWorkFlowTaskResultItem;
}

export default memo(function UserInputForm(props: IUserInputFormProps) {
  const [form] = Form.useForm();
  const [isSubmit, setIsSubmit] = useState(false);
  const { node_content: inputParams } = props.nodeData;

  const disabled = useMemo(() => {
    return ['success', 'stop'].includes(props.nodeData.node_status) || isSubmit;
  }, [props.nodeData.node_status, isSubmit]);

  useEffect(() => {
    const valueMap: Record<string, string | undefined> = {};
    (inputParams as IUserInputItem[]).forEach((item) => {
      valueMap[item.key] = item.value ?? undefined;
    });
    form.setFieldsValue(valueMap);
  }, [inputParams]);

  const submit = () => {
    form.validateFields().then(async (values) => {
      setIsSubmit(true);
      const input_params = [] as Array<{ key: string; value: string }>;
      Object.keys(values).forEach((key) => {
        if (values[key] !== undefined && values[key] !== null) {
          input_params.push({ key, value: values[key] });
        }
      });
      try {
        await props.onSubmit({
          input_params,
          resume_node_id: props.nodeData.node_id,
          resume_parent_id: props.nodeData.parent_node_id,
        });
      } catch {
        setIsSubmit(false);
      }
    }).catch(() => { /* Form displays validation errors. */ });
  };

  return (
    <Form className={styles.form} disabled={disabled} form={form}>
      {(inputParams as IUserInputItem[]).map((item) => {
        return (
          <Form.Item
            key={item.key}
            label={item.key}
            name={item.key}
            required={item.required}
            rules={[
              {
                required: item.required,
                whitespace: item.required === true,
                message: $i18n.get({
                  id: 'main.pages.App.Workflow.components.UserInputForm.index.enter',
                  dm: '请输入',
                }),
              },
            ]}
          >
            <Input
              placeholder={$i18n.get({
                id: 'main.pages.App.Workflow.components.UserInputForm.index.enter',
                dm: '请输入',
              })}
            />
          </Form.Item>
        );
      })}
      <Button
        className={'w-full'}
        disabled={disabled}
        type="primary"
        onClick={submit}
      >
        {props.nodeData.node_status === 'success'
          ? $i18n.get({
              id: 'main.pages.App.Workflow.components.UserInputForm.index.submitted',
              dm: '已提交',
            })
          : $i18n.get({
              id: 'main.pages.App.Workflow.components.UserInputForm.index.submit',
              dm: '提交',
            })}
      </Button>
    </Form>
  );
});
