# Spring AI Alibaba Admin 生产部署文档

## 1. 本地准备文件

后端上传这个文件：

```text
spring-ai-alibaba-admin-server-start/
└── target/
    └── spring-ai-alibaba-admin-server-start.jar
```


前端上传构建后的：

```text
frontend/packages/main/dist/
```

---

## 2. 服务器创建目录

服务器分配目录：

```text
/app/saa/
```

创建：

```bash
mkdir -p /app/saa/backend
mkdir -p /app/saa/frontend
mkdir -p /app/saa/logs
```

最终目录：

```text
/app/saa/
├── backend/
│   └── spring-ai-alibaba-admin-server-start.jar
├── frontend/
│   └── dist/
└── logs/
```


## 3. 配置后端连接信息

创建：

```bash
vim /app/saa/saa-admin.env
```

填写：

```bash
SERVER_PORT=8080

SPRING_DATASOURCE_URL=jdbc:postgresql://POSTGRES_IP:5432/saa
SPRING_DATASOURCE_USERNAME=POSTGRES_USER
SPRING_DATASOURCE_PASSWORD=POSTGRES_PASSWORD

SPRING_REDIS_HOST=REDIS_IP
SPRING_REDIS_PORT=6379
SPRING_REDIS_DATABASE=3

SPRING_DATA_REDIS_PASSWORD=REDIS_PASSWORD
```

如果 Redis 没有密码：

```bash
SPRING_DATA_REDIS_PASSWORD=
```

设置权限：

```bash
chmod 600 /app/saa/saa-admin.env
```

---

## 4. 先手工启动后端测试

```bash
set -a
source /app/saa/saa-admin.env
set +a

cd /app/saa/backend

java -jar spring-ai-alibaba-admin-server-start.jar
```

另开终端检查：

```bash
ss -lntp | grep 8080
```

正常后按：

```text
Ctrl + C
```

停止。

---

## 5. 配置 systemd


创建服务：

```bash
sudo vim /etc/systemd/system/saa-admin.service
```

填写：

```ini
[Unit]
Description=Spring AI Alibaba Admin
After=network.target

[Service]
Type=simple

User=你的Linux用户名
WorkingDirectory=/app/saa/backend
EnvironmentFile=/app/saa/saa-admin.env

ExecStart=/usr/bin/java -Xms1g -Xmx2g -XX:+UseG1GC -jar /app/saa/backend/spring-ai-alibaba-admin-server-start.jar

Restart=always
RestartSec=5
SuccessExitStatus=143
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

把：

```text
User=你的Linux用户名
```

替换成：

```bash
whoami
```

输出的用户名。

加载：

```bash
sudo systemctl daemon-reload
```

设置开机启动：

```bash
sudo systemctl enable saa-admin
```

启动：

```bash
sudo systemctl start saa-admin
```

查看：

```bash
sudo systemctl status saa-admin
```

实时日志：

```bash
sudo journalctl -u saa-admin -f
```

检查端口：

```bash
ss -lntp | grep 8080
```

---

## 6. 配置 Nginx

创建：

```bash
sudo vim /etc/nginx/conf.d/saa.conf
```

填写：

```nginx
server {
    listen 80;
    server_name _;

    root /app/saa/frontend/dist;
    index index.html;

    client_max_body_size 500m;

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
        proxy_cache off;
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
}
```

检查：

```bash
sudo nginx -t
```

重新加载：

```bash
sudo systemctl reload nginx
```

---

## 7. 设置前端目录权限

```bash
chmod 755 /app
chmod 755 /app/saa
chmod 755 /app/saa/frontend

find /app/saa/frontend/dist -type d -exec chmod 755 {} \;
find /app/saa/frontend/dist -type f -exec chmod 644 {} \;
```

---

## 8. 正式启动

启动后端：

```bash
sudo systemctl start saa-admin
```

重新加载 Nginx：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

检查：

```bash
sudo systemctl status saa-admin
sudo systemctl status nginx
```

检查端口：

```bash
ss -lntp | grep -E ':80|:8080'
```

浏览器访问：

```text
http://服务器IP
```

---

## 9. 查看日志

后端实时日志：

```bash
sudo journalctl -u saa-admin -f
```

最近 300 行：

```bash
sudo journalctl -u saa-admin -n 300 --no-pager
```

Nginx 错误日志：

```bash
sudo tail -f /var/log/nginx/error.log
```

---

## 10. 后端常用命令

启动：

```bash
sudo systemctl start saa-admin
```

停止：

```bash
sudo systemctl stop saa-admin
```

重启：

```bash
sudo systemctl restart saa-admin
```

状态：

```bash
sudo systemctl status saa-admin
```

---

## 11. 更新后端 JAR

停止：

```bash
sudo systemctl stop saa-admin
```

备份：

```bash
cp   /app/saa/backend/spring-ai-alibaba-admin-server-start.jar   /app/saa/backend/spring-ai-alibaba-admin-server-start.jar.bak
```

上传新的：

```text
spring-ai-alibaba-admin-server-start.jar
```

覆盖：

```text
/app/saa/backend/spring-ai-alibaba-admin-server-start.jar
```

启动：

```bash
sudo systemctl start saa-admin
```

检查：

```bash
sudo systemctl status saa-admin
sudo journalctl -u saa-admin -n 100 --no-pager
```

---

## 12. 更新前端 dist

备份：

```bash
rm -rf /app/saa/frontend/dist.bak
mv /app/saa/frontend/dist /app/saa/frontend/dist.bak
```

上传新的：

```text
dist
```

到：

```text
/app/saa/frontend/dist
```

设置权限：

```bash
find /app/saa/frontend/dist -type d -exec chmod 755 {} \;
find /app/saa/frontend/dist -type f -exec chmod 644 {} \;
```

加载：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

---

## 13. 回滚

后端：

```bash
sudo systemctl stop saa-admin

cp   /app/saa/backend/spring-ai-alibaba-admin-server-start.jar.bak   /app/saa/backend/spring-ai-alibaba-admin-server-start.jar

sudo systemctl start saa-admin
```

前端：

```bash
rm -rf /app/saa/frontend/dist
mv /app/saa/frontend/dist.bak /app/saa/frontend/dist

sudo systemctl reload nginx
```
