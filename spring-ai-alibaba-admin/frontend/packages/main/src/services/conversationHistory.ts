import axios from 'axios';
import { baseURL } from '@/request/request';

export type ConversationTime = string | number | null;

export interface ConversationRecord {
  conversationId: string;
  appId?: string;
  userId?: string;
  invokeSource?: string;
  name?: string;
  status?: string;
  messageCount?: number;
  createdAt?: ConversationTime;
  updatedAt?: ConversationTime;
}

export interface ConversationMessage {
  messageId?: string;
  question?: string;
  answer?: string;
  traceId?: string;
  requestId?: string;
  userId?: string;
  status?: string;
  createdAt?: ConversationTime;
  updatedAt?: ConversationTime;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
}

export interface ConversationQuery {
  conversationId?: string;
  appId?: string;
  userId?: string;
  invokeSource?: string;
  name?: string;
  status?: string;
  startTime?: string;
  endTime?: string;
  pageNum: number;
  pageSize: number;
}

export interface MessageQuery {
  conversationId: string;
  appId?: string;
  status?: string;
  startTime?: string;
  endTime?: string;
  pageNum: number;
  pageSize: number;
}

const SUCCESS_CODES = new Set(['0', '200', '10000', 'SUCCESS']);

async function postOpenApi<T>(apiKey: string, url: string, data: object): Promise<T> {
  try {
    const response = await axios.request({
      baseURL: baseURL.get(),
      url,
      method: 'POST',
      data,
      timeout: 60 * 1000,
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
    });

    const body = response.data;
    if (body?.code !== undefined && !SUCCESS_CODES.has(String(body.code))) {
      throw new Error(body?.message || `接口返回错误：${body.code}`);
    }
    return (body?.data ?? body) as T;
  } catch (error: any) {
    const responseBody = error?.response?.data;
    const detail = responseBody?.message || responseBody?.error?.message || error?.message;
    throw new Error(detail || '请求失败，请检查服务地址和 API Key');
  }
}

/** POST /api/v1/conversations：查询当前 API Key 有权访问的会话。 */
export const queryConversations = (apiKey: string, params: ConversationQuery) =>
  postOpenApi<PageResult<ConversationRecord>>(apiKey, '/api/v1/conversations', params);

/** POST /api/v1/conversations/messages：查询指定会话的分页问答记录。 */
export const queryConversationMessages = (apiKey: string, params: MessageQuery) =>
  postOpenApi<PageResult<ConversationMessage>>(apiKey, '/api/v1/conversations/messages', params);
