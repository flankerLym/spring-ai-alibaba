# OpenAPI 模块 DDD 分层重构

基线：`flankerLym/spring-ai-alibaba` 的 `main` 分支，`spring-ai-alibaba-admin-server-openapi`。仅覆盖压缩包内的 Java 源文件；不改表结构、前端、配置与 API Key 拦截器。

## 分层

- `controller/`：只负责 REST 路由、请求 DTO 和 HTTP 响应包装/异常处理；保留原类名及 API 路径。
- `application/OpenApiAppQueryService`：应用查询、筛选和分页。
- `application/OpenApiAppViewAssembler`：公开应用字段及请求模板组装。
- `application/OpenApiConversationQueryService`：会话/历史查询、范围校验及权限约束。
- `application/OpenApiCompletionService`：Chat/Workflow 调用、异步启动/查询/停止。
- `infrastructure/transport/OpenApiSseTransport`：JSON/SSE 传输、连接取消、响应错误与日志。
- 原 `server-core` 和 `server-runtime` 继续承载既有领域模型、工作流服务和持久化接口，不复制一套域模型/Repository。

## 兼容性

- 保留 8 个已有 POST 路由（应用查询 1、会话查询 2、Chat/Workflow 5）。
- `OpenApiCallerContextAspect` 仍以 `ChatController` 为切点，入口方法和请求类型保持不变。
- API Key 授权、工作空间范围、分页过滤、JSON/SSE、异步 task_id 字段不改。
- 兼容保留 `/api/v1/apps/chat/completions`，但**不代表要把 Chat 接口加入你已精简的第三方联调文档**。
- 仅做代码分层重构，不顺带修复其他已有业务问题。

## 覆盖及检查

1. 先在本地保存未提交修改：`git status`、按需要 `git stash`/提交备份。
2. 在**`spring-ai-alibaba-admin` 同级目录**解压本 ZIP（路径以 `spring-ai-alibaba-admin/` 开头）。
3. 进入 `spring-ai-alibaba-admin` 执行：`mvn -pl spring-ai-alibaba-admin-server-openapi -am -DskipTests compile`。
4. 对应用查询、会话查询、同步 Workflow、SSE 与异步任务进行现网环境回归测试。

交付前已使用 JDK Java 语法分析器检查 Java 源文件，未发现语法错误。当前执行容器没有 Maven 及工程依赖，**未完成 Maven 构建、启动或接口集成测试**，需要按第 3-4 步验收。
