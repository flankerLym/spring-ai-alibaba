# API KEY 应用访问范围覆盖说明

本覆盖包基于仓库 `flankerLym/spring-ai-alibaba` 的 `main` 最新提交：

```text
7eb91b3cfc97376c34000bb044550daf6abe3c8a
feat(workflow): 新增权限管理页面 / 新增调用方字段
```

## 覆盖方式

压缩包根目录就是 `spring-ai-alibaba-admin/`。
请在 **spring-ai-alibaba-admin 的同级目录（仓库根目录）** 解压并覆盖。

## 本次功能

- 保留现有权限管理页面、企业名称/调用方字段和 API KEY CRUD。
- API KEY 新增访问范围：`ALL` / `CUSTOM`。
- `ALL`：当前 workspace 下全部应用。
- `CUSTOM`：可同时选择多个项目归档文件夹和多个应用。
- 文件夹授权与单应用授权取并集，同一 appId 自动去重。
- 文件夹权限是动态关系：文件夹后来增加/移除应用时，API KEY 权限自动变化，不复制展开后的 appId。
- OpenAPI 调用、应用详情、应用列表都会执行 appId 权限判断。
- CUSTOM 应用列表在数据库分页前加入权限过滤，避免分页/total 错误。
- 异步结果/停止接口在 Redis 中能够找到任务上下文时，也会按任务对应 appId 校验权限。
- Console 登录调用不受影响；只有通过 API KEY 鉴权产生的 RequestContext 才启用应用权限过滤。

## 数据库

数据库需要：

1. `api_key.scope_type` 字段；
2. 一张 `api_key_resource_permission` 表。

具体 PostgreSQL 命令由交付消息单独给出，本包不包含 SQL 文件。

## 构建

后端在 `spring-ai-alibaba-admin` 下：

```bash
mvn clean package -pl spring-ai-alibaba-admin-server-start -am -DskipTests
```

前端：

```powershell
cd frontend
npm install
npm run build:flow
cd packages/main
npm run build
```
