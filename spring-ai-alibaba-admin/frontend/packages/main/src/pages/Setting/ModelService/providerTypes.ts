export const PROVIDER_TYPES = [
  { value: 'openai', label: 'OpenAI', endpoint: 'https://api.openai.com' },
  { value: 'deepseek', label: 'DeepSeek', endpoint: 'https://api.deepseek.com' },
  { value: 'dashscope', label: 'DashScope（通义千问）', endpoint: 'https://dashscope.aliyuncs.com/compatible-mode' },
  { value: 'doubao', label: '豆包（ByteDance）', endpoint: 'https://ark.cn-beijing.volces.com/api/v3' },
];

export function providerType(value?: string) {
  const key = (value || 'openai').toLowerCase();
  return PROVIDER_TYPES.find((item) => item.value === key) || PROVIDER_TYPES[0];
}

