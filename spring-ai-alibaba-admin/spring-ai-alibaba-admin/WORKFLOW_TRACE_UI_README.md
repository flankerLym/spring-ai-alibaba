# Workflow Trace 可视化页面

页面：`可观测 -> 工作流链路`
路由：`/admin/workflow-tracing`

保留 SAA 原有 `Tracing` 页面。新页面专门展示当前项目新增的
`workflow_trace / workflow_span` 工作流业务链路。

## 页面能力

- Trace 数、成功率、平均耗时、总 Token、模型调用数
- Trace / Task / Request / Conversation ID 模糊检索
- App ID、状态、来源、时间筛选
- Trace 分页列表
- Trace 详情 Drawer
- NODE / MODEL_CALL Span 父子结构
- Span 时间瀑布图
- Provider / Model / Token
- Input / Output / Span Data / Error

## 新增只读 API

- `GET /console/v1/workflow-traces`
- `GET /console/v1/workflow-traces/overview`
- `GET /console/v1/workflow-traces/{traceId}`

新增独立 `WorkflowTraceQueryMapper`，不修改现有 Trace 写入链路。

## 安装

把压缩包解压到 `spring-ai-alibaba` 仓库根目录，使
`spring-ai-alibaba-admin` 与原目录合并。

然后执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\spring-ai-alibaba-admin\apply-workflow-trace-ui.ps1
```

该脚本只定点修改：

- `frontend/packages/main/.umirc.ts`
- `frontend/packages/main/src/layouts/SideMenuLayout.tsx`

并自动生成 `.bak-workflow-trace` 备份。

也可以参考 `workflow-trace-ui.patch` 手工修改。

## 编译后端

```powershell
mvn -f .\spring-ai-alibaba-admin\pom.xml `
  -pl spring-ai-alibaba-admin-server-start `
  -am -DskipTests compile
```

之后按项目原方式启动 frontend，访问：

`/admin/workflow-tracing`

## 实现说明

- PostgreSQL JSONB 字段在查询 SQL 中显式 `::text`，避免直接映射 String 的类型问题。
- 可视化查询逻辑完全是只读的，不进入 Workflow 执行路径。
- 原有 `WorkflowTraceMapper / WorkflowSpanMapper` 持久化逻辑没有修改。
