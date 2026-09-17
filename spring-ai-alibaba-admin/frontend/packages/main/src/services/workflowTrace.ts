import { request } from '@/request';

export interface WorkflowTrace {
  traceId: string;
  rootSpanId?: string;
  taskId?: string;
  requestId?: string;
  appId?: string;
  workflowVersion?: string;
  conversationId?: string;
  invokeSource?: string;
  status?: string;
  finishReason?: string;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  totalCost?: number;
  spanCount?: number;
  modelCallCount?: number;
  inputData?: string;
  outputData?: string;
  traceData?: string;
  errorData?: string;
  errorCode?: string;
  errorMessage?: string;
}

export interface WorkflowSpan {
  spanId: string;
  traceId: string;
  parentSpanId?: string;
  spanName?: string;
  spanKind?: string;
  sequenceNo?: number;
  nodeId?: string;
  nodeName?: string;
  nodeType?: string;
  attemptNo?: number;
  provider?: string;
  modelId?: string;
  modelName?: string;
  priceCost?: number;
  status?: string;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  inputData?: string;
  outputData?: string;
  spanData?: string;
  errorCode?: string;
  errorMessage?: string;
  errorData?: string;
}

export interface WorkflowTraceOverview {
  traceCount?: number;
  successCount?: number;
  failCount?: number;
  totalTokens?: number;
  modelCallCount?: number;
  avgDurationMs?: number;
}

export interface TraceListParams {
  current: number;
  size: number;
  appId?: string;
  keyword?: string;
  status?: string;
  invokeSource?: string;
  startTime?: string;
  endTime?: string;
}

export interface TracePage {
  records: WorkflowTrace[];
  total: number;
  current: number;
  size: number;
}

export interface TraceDetail {
  trace: WorkflowTrace;
  spans: WorkflowSpan[];
}

const unwrap = <T,>(promise: Promise<any>): Promise<T> =>
  promise.then((res) => res.data.data as T);

export const getWorkflowTraces = (params: TraceListParams) =>
  unwrap<TracePage>(request({
    url: '/console/v1/workflow-traces',
    method: 'GET',
    params,
  }));

export const getWorkflowTraceOverview = (
  params: Omit<TraceListParams, 'current' | 'size'>,
) =>
  unwrap<WorkflowTraceOverview>(request({
    url: '/console/v1/workflow-traces/overview',
    method: 'GET',
    params,
  }));

export const getWorkflowTraceDetail = (traceId: string) =>
  unwrap<TraceDetail>(request({
    url: `/console/v1/workflow-traces/${encodeURIComponent(traceId)}`,
    method: 'GET',
  }));
