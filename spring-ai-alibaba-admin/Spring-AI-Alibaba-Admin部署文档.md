# Spring AI Alibaba Admin 非 Docker 生产部署文档

> 适用仓库：`flankerLym/spring-ai-alibaba`  
> 适用目录：`spring-ai-alibaba-admin`  
> 基线：当前 `main` 分支（2026-09-29）  
> 部署方式：Linux 裸机/ 云主机
---

## 1. 部署架构

```text
Browser
   |
   v
Nginx :80/:443
   |----------------------> 前端静态文件
   |
   +-- /api/* -----------> SAA Admin Backend :8080
   +-- /console/* -------> SAA Admin Backend :8080
   +-- /oauth2/* --------> SAA Admin Backend :8080

SAA Admin Backend
   |
   +--> PostgreSQL
   +--> Redis
   +--> Nacos
   +--> AI Model Provider API
```

推荐目录：

```text
/opt/saa/
├── backend/
│   ├── spring-ai-alibaba-admin-server-start.jar
│   └── logs/
├── frontend/
│   └── dist/
└── releases/

/etc/saa/
└── saa-admin.env
```

---

## 2. 当前代码实际环境要求

当前根 `pom.xml` 明确配置：

- Java 17
- Maven 3.8+
- Spring Boot 3.3.6
- Spring AI 1.1.2
- Spring AI Alibaba 1.0.0.3

当前主 `application.yml` 已经使用：

```yaml
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
```

因此当前生产部署应以 **PostgreSQL** 为准。

> 注意：仓库中的旧 README、`application-local.yml`、`application-dev.yml` 仍保留 MySQL 配置。生产环境不要激活 `local` 或 `dev` profile，否则会重新切回 MySQL。

推荐组件版本：

| 组件 | 建议版本 |
|---|---|
| JDK | 17 |
| Maven | 3.8+ |
| Node.js | 18/20 LTS |
| PostgreSQL | 15/16 |
| Redis | 7.2.x |
| Elasticsearch | 9.1.x |
| Nacos | 2.x |
| RocketMQ | 5.3.x |
| Nginx | 1.22+ |

其中 Redis 7.2.5、Elasticsearch 9.1.2、RocketMQ 5.3.2 与仓库完整环境保持一致；PostgreSQL 版本当前代码没有锁死。

---

## 3. 服务器建议

完整功能建议：

```text
CPU：8 Core+
内存：16 GB+
磁盘：100 GB+
系统：Ubuntu 22.04/24.04 LTS 或同级 Linux
```

如果 PostgreSQL、Elasticsearch、RocketMQ 与应用部署在同一台机器，建议 32 GB 内存。

---

## 4. 安装基础环境

Ubuntu 示例：

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk maven nginx git curl wget unzip tar jq rsync
```

检查 Java：

```bash
java -version
mvn -v
```

必须保证 Maven 实际使用 Java 17。不要出现：

```text
java -version -> 17/21
mvn -v       -> Java 11
```

如有需要：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

---

## 5. PostgreSQL

安装：

```bash
sudo apt install -y postgresql postgresql-contrib
sudo systemctl enable postgresql
sudo systemctl start postgresql
```

创建账号和数据库：

```bash
sudo -u postgres psql
```

```sql
CREATE USER saa WITH PASSWORD '请替换为强密码';
CREATE DATABASE saa OWNER saa;
GRANT ALL PRIVILEGES ON DATABASE saa TO saa;
\q
```

验证：

```bash
psql -h 127.0.0.1 -U saa -d saa
```

### 5.1 数据库初始化的重要说明

当前仓库存在：

```text
spring-ai-alibaba-admin/docker/middleware/init/mysql/admin-schema.sql
```

但该 SQL 是 MySQL 语法，包含：

```text
BIGINT UNSIGNED
AUTO_INCREMENT
ENGINE=InnoDB
TINYINT
ON UPDATE CURRENT_TIMESTAMP
```

**不要直接导入 PostgreSQL。**

当前仓库没有一份和最新代码完全一致的统一 PostgreSQL schema/migration。

生产部署推荐直接迁移已有可运行 PostgreSQL：

```bash
pg_dump -h OLD_DB_HOST -U OLD_DB_USER -d saa -Fc -f saa.dump
```

恢复：

```bash
pg_restore -h 127.0.0.1 -U saa -d saa --no-owner saa.dump
```

如果是全新空库，建议上线前先补齐 PostgreSQL migration。至少应覆盖当前新增表：

```text
workflow_trace
workflow_span
conversation_record
conversation_message
project_archive_folder
project_archive_app
```

以及原有 app、app_version、model_config、prompt、dataset、knowledge、plugin、account、api key、evaluation 等业务表。

---

## 6. Redis

安装：

```bash
sudo apt install -y redis-server
```

编辑：

```bash
sudo vim /etc/redis/redis.conf
```

建议：

```conf
bind 127.0.0.1
protected-mode yes
requirepass 你的强密码
appendonly yes
```

启动：

```bash
sudo systemctl enable redis-server
sudo systemctl restart redis-server
```

验证：

```bash
redis-cli -a '你的强密码' ping
```

返回：

```text
PONG
```

当前主配置默认使用 Redis database 3。

---

## 7. Elasticsearch

当前仓库完整环境使用 Elasticsearch 9.1.2，建议裸机安装 9.1.x。

单机至少配置：

```yaml
cluster.name: saa-es
node.name: saa-es-01
network.host: 127.0.0.1
http.port: 9200
discovery.type: single-node
```

检查：

```bash
curl http://127.0.0.1:9200/_cluster/health
```

代码默认 Trace 索引：

```text
loongsuite_traces
```

仓库中的：

```text
spring-ai-alibaba-admin/docker/middleware/init/elasticsearch/init-indices.sh
```

本质是 curl 脚本，可参考其内容在裸机 Elasticsearch 上创建：

```text
parsing_loongsuite_traces ingest pipeline
loongsuite_traces index
```

注意：你新增的 `workflow_trace/workflow_span` 是 PostgreSQL 数据，不依赖 Elasticsearch。

---

## 8. Nacos

安装 Nacos 2.x standalone 版本，假设目录：

```text
/opt/nacos
```

启动：

```bash
cd /opt/nacos/bin
./startup.sh -m standalone
```

检查：

```bash
curl http://127.0.0.1:8848/nacos/
```

项目环境变量：

```bash
NACOS_SERVER_ADDR=127.0.0.1:8848
```

如果生产 Nacos 开启鉴权，需要额外在外部 Spring 配置中增加客户端用户名和密码；当前主 `application.yml` 只显式定义了 server address。

---

## 9. RocketMQ

建议与当前仓库一致使用 RocketMQ 5.3.x。

至少需要：

```text
NameServer :9876
Broker     :10911 等
Proxy      :18080 / :18081
```

启动顺序：

```text
1. NameServer
2. Broker
3. Proxy
```

应用连接：

```bash
ROCKETMQ_ENDPOINTS=127.0.0.1:18080
```

项目需要：

```text
topic_saa_studio_document_index
group_saa_studio_document_index
```

创建示例：

```bash
cd /opt/rocketmq/bin

./mqadmin updateTopic \
  -n 127.0.0.1:9876 \
  -t topic_saa_studio_document_index \
  -c DefaultCluster \
  -a +message.type=NORMAL

./mqadmin updateSubGroup \
  -n 127.0.0.1:9876 \
  -g group_saa_studio_document_index \
  -c DefaultCluster
```

---

## 10. 获取代码

```bash
cd /opt
git clone https://github.com/flankerLym/spring-ai-alibaba.git
cd spring-ai-alibaba
```

生产建议固定 commit：

```bash
git checkout <commit-sha>
```

不要长期直接运行不断变化的 `main`。

---

## 11. 后端环境变量

创建：

```text
/etc/saa/saa-admin.env
```

内容：

```bash
SERVER_PORT=8080

SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/saa
SPRING_DATASOURCE_USERNAME=saa
SPRING_DATASOURCE_PASSWORD=请替换

SPRING_REDIS_HOST=127.0.0.1
SPRING_REDIS_PORT=6379
SPRING_REDIS_DATABASE=3
SPRING_DATA_REDIS_PASSWORD=请替换

SPRING_ELASTICSEARCH_URL=http://127.0.0.1:9200

NACOS_SERVER_ADDR=127.0.0.1:8848

ROCKETMQ_ENDPOINTS=127.0.0.1:18080
ROCKETMQ_DOCUMENT_INDEX_TOPIC=topic_saa_studio_document_index
ROCKETMQ_DOCUMENT_INDEX_GROUP=group_saa_studio_document_index

# 按实际供应商填写
DASHSCOPE_API_KEY=
DEEPSEEK_API_KEY=
OPENAI_API_KEY=
```

权限：

```bash
sudo chmod 600 /etc/saa/saa-admin.env
```

生产不要设置：

```text
-Dspring.profiles.active=local
-Dspring.profiles.active=dev
```

---

## 12. 模型配置

目录：

```text
spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/
├── model-config.yml
├── model-config-dashscope.yaml
├── model-config-deepseek.yaml
└── model-config-openai.yaml
```

`model-config.yml` 当前只是占位文件。

例如 DashScope 模板使用：

```yaml
models:
  - id: 1
    name: qwen-plus
    provider: dashscope
    modelName: qwen-plus
    baseUrl: https://dashscope.aliyuncs.com/compatible-mode
    apiKey: ${DASHSCOPE_API_KEY}
```

真实 API Key 只通过环境变量注入，不要提交到 Git。

---

## 13. 后端打包

从仓库根目录：

```bash
cd /opt/spring-ai-alibaba
```

执行：

```bash
mvn \
  -f spring-ai-alibaba-admin/pom.xml \
  -pl spring-ai-alibaba-admin-server-start \
  -am \
  clean package \
  -DskipTests
```

主要产物：

```text
spring-ai-alibaba-admin/
└── spring-ai-alibaba-admin-server-start/
    └── target/
        └── spring-ai-alibaba-admin-server-start.jar
```

部署：

```bash
sudo mkdir -p /opt/saa/backend/logs
sudo cp \
  spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/target/spring-ai-alibaba-admin-server-start.jar \
  /opt/saa/backend/
```

---

## 14. 手工启动后端验证

```bash
set -a
source /etc/saa/saa-admin.env
set +a

cd /opt/saa/backend

java \
  -Xms1g \
  -Xmx2g \
  -XX:+UseG1GC \
  -jar spring-ai-alibaba-admin-server-start.jar
```

当前没有自定义 `server.port` 时，Spring Boot 默认端口为：

```text
8080
```

检查：

```bash
curl http://127.0.0.1:8080/actuator/health
```

---

## 15. systemd 管理后端

创建：

```text
/etc/systemd/system/saa-admin.service
```

内容：

```ini
[Unit]
Description=Spring AI Alibaba Admin
After=network.target postgresql.service redis-server.service

[Service]
Type=simple
User=saa
Group=saa
WorkingDirectory=/opt/saa/backend
EnvironmentFile=/etc/saa/saa-admin.env

ExecStart=/usr/bin/java \
  -Xms1g \
  -Xmx2g \
  -XX:+UseG1GC \
  -jar /opt/saa/backend/spring-ai-alibaba-admin-server-start.jar

Restart=always
RestartSec=5
SuccessExitStatus=143
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

创建用户：

```bash
sudo useradd --system --home /opt/saa --shell /usr/sbin/nologin saa || true
sudo chown -R saa:saa /opt/saa
```

启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable saa-admin
sudo systemctl start saa-admin
```

查看：

```bash
sudo systemctl status saa-admin
journalctl -u saa-admin -f
```

---

## 16. Node.js 与前端构建

仓库没有锁死 Node 版本，推荐 Node.js 18 LTS 或 20 LTS。

```bash
node -v
npm -v
```

进入：

```bash
cd /opt/spring-ai-alibaba/spring-ai-alibaba-admin/frontend
```

仓库有 `package-lock.json`，生产构建优先：

```bash
npm ci
```

先构建 flow：

```bash
npm run build:flow
```

再构建主应用：

```bash
cd packages/main
BACK_END=java WEB_SERVER= npm run build
```

生产建议保持：

```text
WEB_SERVER=
```

当前前端 Axios 初始化逻辑是：

```text
process.env.WEB_SERVER || ''
```

因此为空时浏览器会访问同源：

```text
/api/...
/console/...
/oauth2/...
```

由 Nginx 转给 Java 后端。

构建产物通常为：

```text
frontend/packages/main/dist
```

复制：

```bash
sudo mkdir -p /opt/saa/frontend
sudo rsync -a --delete \
  /opt/spring-ai-alibaba/spring-ai-alibaba-admin/frontend/packages/main/dist/ \
  /opt/saa/frontend/dist/
```

---

## 17. Nginx

创建：

```text
/etc/nginx/sites-available/saa-admin
```

配置：

```nginx
server {
    listen 80;
    server_name your-domain.example.com;

    client_max_body_size 500m;

    root /opt/saa/frontend/dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 工作流 SSE 必须关闭缓冲
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }

    location /console/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_read_timeout 600s;
    }

    location /oauth2/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /actuator/ {
        proxy_pass http://127.0.0.1:8080;
        allow 127.0.0.1;
        deny all;
    }
}
```

启用：

```bash
sudo ln -s /etc/nginx/sites-available/saa-admin /etc/nginx/sites-enabled/saa-admin
sudo nginx -t
sudo systemctl reload nginx
```

---

## 18. OpenAPI 地址

当前 `ChatController` 基础路径：

```text
/api/v1/apps
```

工作流：

```text
POST /api/v1/apps/workflow/completions
```

聊天：

```text
POST /api/v1/apps/chat/completions
```

外部地址：

```text
https://your-domain.example.com/api/v1/apps/...
```

对于流式调用，Nginx 的：

```nginx
proxy_buffering off;
proxy_read_timeout 600s;
```

不能删除，否则 SSE 可能被缓冲成一次性返回。

---

## 19. 上线检查

### 服务状态

```bash
systemctl status postgresql
systemctl status redis-server
systemctl status saa-admin
systemctl status nginx
```

### PostgreSQL

```bash
psql -h 127.0.0.1 -U saa -d saa -c "select 1;"
```

### Redis

```bash
redis-cli -a '你的密码' ping
```

### Elasticsearch

```bash
curl http://127.0.0.1:9200/_cluster/health
```

### 后端

```bash
curl http://127.0.0.1:8080/actuator/health
```

### 前端

```bash
curl -I http://127.0.0.1/
```

### 监听端口

```bash
ss -lntp | grep -E '8080|5432|6379|9200|8848|9876|18080'
```

---

## 20. Trace 验证

执行一次工作流后：

```sql
SELECT
    trace_id,
    root_span_id,
    span_count,
    model_call_count,
    status,
    finish_reason,
    start_time
FROM workflow_trace
ORDER BY start_time DESC
LIMIT 10;
```

查询 Span：

```sql
SELECT
    trace_id,
    span_id,
    parent_span_id,
    span_kind,
    node_id,
    node_name,
    node_type,
    sequence_no,
    status
FROM workflow_span
WHERE trace_id = '你的traceId'
ORDER BY sequence_no;
```

---

## 21. 安全检查

生产环境至少做到：

```text
[ ] PostgreSQL 独立生产账号、强密码
[ ] Redis 强密码，仅监听内网
[ ] Elasticsearch 不开放公网
[ ] Nacos 不开放公网
[ ] RocketMQ 不开放公网
[ ] Java 8080 不开放公网
[ ] API Key 只用环境变量
[ ] /actuator 只允许内网
[ ] Nginx 开启 HTTPS
[ ] PostgreSQL 定时备份
[ ] Redis 开启持久化
[ ] 日志配置轮转
```

当前源码 `application.yml` 中仍存在开发环境默认数据库、Redis 地址和密码，生产必须全部由环境变量覆盖。

---

## 22. 常见问题

### 22.1 Maven 使用 Java 11

```bash
mvn -v
```

必须显示 Java 17。

### 22.2 PostgreSQL 报 relation does not exist

说明 schema 没有完整初始化。

当前配置：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
```

不会自动建业务表。

### 22.3 NACOS_SERVER_ADDR 缺失

错误：

```text
Could not resolve placeholder 'NACOS_SERVER_ADDR'
```

检查 `/etc/saa/saa-admin.env`。

### 22.4 RocketMQ endpoint 缺失

必须配置：

```bash
ROCKETMQ_ENDPOINTS=127.0.0.1:18080
```

### 22.5 前端刷新页面 404

Nginx 必须：

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

### 22.6 SSE 不实时

必须：

```nginx
proxy_buffering off;
proxy_cache off;
proxy_read_timeout 600s;
```

---

## 23. HTTPS

推荐结构：

```nginx
server {
    listen 443 ssl http2;
    server_name your-domain.example.com;

    ssl_certificate     /etc/nginx/ssl/fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/private.key;

    ...
}
```

80 跳 HTTPS：

```nginx
server {
    listen 80;
    server_name your-domain.example.com;
    return 301 https://$host$request_uri;
}
```

---

## 24. 升级与回滚

升级前备份：

```bash
pg_dump -h DB_HOST -U saa -d saa -Fc -f backup-before-upgrade.dump
```

停止：

```bash
sudo systemctl stop saa-admin
```

备份旧 Jar：

```bash
mkdir -p /opt/saa/releases
cp /opt/saa/backend/spring-ai-alibaba-admin-server-start.jar \
   /opt/saa/releases/$(date +%Y%m%d-%H%M%S)-backend.jar
```

替换新 Jar 和前端后：

```bash
sudo systemctl start saa-admin
sudo systemctl reload nginx
curl http://127.0.0.1:8080/actuator/health
```

回滚：

```bash
sudo systemctl stop saa-admin
cp /opt/saa/releases/<old-version>-backend.jar \
   /opt/saa/backend/spring-ai-alibaba-admin-server-start.jar
sudo systemctl start saa-admin
```

如果升级包含数据库结构变化，必须确认 migration 是否向后兼容。

---

## 25. 推荐上线顺序

```text
1. 安装 JDK 17 / Maven / Node / Nginx
2. 安装 PostgreSQL
3. 恢复已有 PostgreSQL schema + 数据
4. 安装 Redis
5. 安装 Elasticsearch
6. 初始化 Elasticsearch Trace 索引
7. 安装 Nacos
8. 安装 RocketMQ NameServer / Broker / Proxy
9. 创建 RocketMQ Topic + Group
10. 写 /etc/saa/saa-admin.env
11. Maven 打包后端
12. systemd 启动 Java 后端
13. 验证 /actuator/health
14. npm 构建前端
15. Nginx 发布静态文件 + API 反代
16. 验证登录和应用列表
17. 验证工作流编辑/发布
18. 验证 OpenAPI 非流式
19. 验证 OpenAPI SSE 流式
20. 验证多轮 conversation
21. 验证 workflow_trace / workflow_span
```

---

## 26. 当前仓库与旧文档的差异

| 项目 | 旧 README / local 配置 | 当前主配置 |
|---|---|---|
| 数据库 | MySQL | PostgreSQL |
| 后端 | 8080 | Spring Boot 默认 8080 |
| Trace | OTel/ES | 同时存在 PostgreSQL workflow_trace/workflow_span |
| OpenAPI | 旧接口说明 | `/api/v1/apps/...` |
| 前端连接 | dev proxy | 生产建议 Nginx 同源反代 |

生产部署不要直接照抄旧 README 中的 MySQL 或 Docker 启动步骤。

---

## 27. 结论

当前项目可以完全脱离 Docker 部署，推荐生产组合：

```text
JDK 17
+ PostgreSQL
+ Redis
+ Elasticsearch
+ Nacos
+ RocketMQ
+ Java Jar(systemd)
+ Umi 前端静态文件
+ Nginx
```

当前正式交付前最需要补齐的是：

**一份与当前 `main` 分支完全同步的 PostgreSQL 初始化 / migration 脚本。**

对于已有可运行 PostgreSQL 环境迁移，本文档可以直接使用；对于全新空库服务器，先补齐 PostgreSQL schema 后再部署。
