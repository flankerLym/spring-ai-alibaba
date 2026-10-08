# API KEY 权限管理覆盖说明

## 1. 覆盖位置

本包根目录包含 `spring-ai-alibaba-admin/`。在**仓库根目录**（即 `spring-ai-alibaba-admin` 的同级目录）解压，覆盖同名文件。

无需执行新的建表语句：你已在 PostgreSQL 的 `api_key` 表中添加：

```sql
company_name VARCHAR(200)
```

请先确认上述字段确实存在于目标数据库中，否则 API Key 列表和创建操作将因字段不存在而失败。

## 2. 功能

- 前端左侧第二个一级导航：**权限管理**，地址 `/permission`。
- API KEY 列表增加 **企业名称**，保留分页、查看/复制、删除。
- 新增 API KEY 可填写 `companyName`（最长 200 字符）和 `description`。
- 编辑已有 API KEY 的 `companyName` 和 `description`。
- 后端沿用 `/console/v1/api-keys`，无需新增接口地址；`PUT /console/v1/api-keys/{id}` 已支持编辑。
- 原有 `/setting/apiKeys` 入口继续可用。
- API KEY 仍属于当前登录账户，密钥值、所属账户、状态、创建人不允许通过编辑接口修改。
- 原有创建上限 20、AES 加密、Redis 缓存、删除及鉴权逻辑保留。
- 老版本客户端更新描述但不发送 `companyName` 时，将保留旧企业名称；显式发送空字符串可以清空。

## 3. 本地构建

在 `spring-ai-alibaba-admin` 目录：

```bash
mvn clean package -pl spring-ai-alibaba-admin-server-start -am -DskipTests
```

前端在 Windows PowerShell：

```powershell
cd frontend
Remove-Item Env:WEB_SERVER -ErrorAction SilentlyContinue
$env:BACK_END="java"
npm run build:flow
cd packages/main
npm run build
```

**生产前端注意：**检查 `packages/main/.env*` 文件，生产包不能将 `WEB_SERVER=http://127.0.0.1:8080` 写入最终 JS。线上同源调用应请求 `/console/v1/...`，由 Nginx 转发到后端。

## 4. 接口入参示例

新增：`POST /console/v1/api-keys`

```json
{
  "companyName": "示例公司",
  "description": "第三方业务接入"
}
```

编辑：`PUT /console/v1/api-keys/{id}`

```json
{
  "companyName": "示例公司（更新）",
  "description": "新的业务用途"
}
```

返回列表原有字段继续保持原样，新字段使用驼峰 `companyName`，数据库列为 `company_name`。

## 5. 验证

1. 登录 SAA，左侧第二行出现 **权限管理**。
2. 进入 `/permission`，已有 API KEY 可正常展示，列表显示企业名称。
3. 创建 API KEY，确认新增的企业名称持久化。
4. 编辑企业名称、描述并刷新页面，确认修改保留。
5. 原有密钥可继续用于 OpenAPI 调用；删除后无法继续使用。

## 6. 说明

此包基于 GitHub 仓库 `flankerLym/spring-ai-alibaba` 的 `main` 分支现有文件结构制作，只包含需要覆盖的源文件，不包含编译产物。Java 17 语法与 TS/TSX 语法已检查，但因未获得完整本地依赖及数据库环境，未进行 Maven 全量编译或前后端联调。
