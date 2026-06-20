# RepoGuard Docker Compose 极简部署

这份文档只保留单机 Docker Compose 发布所需步骤。完整生产变更、备份、回滚和审计要求仍以 `08-企业级上线部署流程说明书.md` 为准。

## 1. 服务器准备

服务器只需要安装：

```bash
docker --version
docker compose version
```

也可以直接运行初始化脚本：

```bash
sudo APP_DIR=/opt/repoguard PRODUCTION_ORIGIN=http://<SERVER_IP> sh scripts/bootstrap-docker-server.sh
```

建议部署目录：

```bash
sudo mkdir -p /opt/repoguard/scripts
sudo chown -R "$USER":"$USER" /opt/repoguard
cd /opt/repoguard
```

## 2. 上传文件

把仓库里的这几个文件放到服务器 `/opt/repoguard`：

```text
docker-compose.prod.yml
.env.prod.example
scripts/deploy-prod.sh
```

目录结构：

```text
/opt/repoguard/docker-compose.prod.yml
/opt/repoguard/.env
/opt/repoguard/scripts/deploy-prod.sh
```

## 3. 配置 .env

```bash
cp .env.prod.example .env
vim .env
```

必须修改：

```text
BACKEND_IMAGE=你的后端镜像
FRONTEND_IMAGE=你的前端镜像
MYSQL_ROOT_PASSWORD=强密码
MYSQL_PASSWORD=强密码
RABBITMQ_DEFAULT_PASS=强密码
APP_CORS_ALLOWED_ORIGINS=http://你的服务器IP
REPOGUARD_SECURITY_ENCRYPTION_KEY=强随机密钥
REPOGUARD_SECURITY_ENCRYPTION_KEY_ID=prod-001
REPOGUARD_AUTH_TOKEN_SECRET=强随机密钥
REPOGUARD_ADMIN_API_KEY=强随机密钥
```

如果暂时没有域名，`APP_CORS_ALLOWED_ORIGINS` 可以填：

```text
http://<SERVER_IP>
```

## 4. 启动

```bash
cd /opt/repoguard
sh scripts/deploy-prod.sh
```

脚本会执行：

```bash
docker compose --env-file .env -f docker-compose.prod.yml pull backend frontend
docker compose --env-file .env -f docker-compose.prod.yml up -d
curl -fsS http://127.0.0.1/actuator/health
```

## 5. 访问

```text
http://<SERVER_IP>/
http://<SERVER_IP>/actuator/health
```

## 6. 日常更新

CI 推送新镜像后，只需要修改 `.env` 里的镜像 tag，或让 CI 传入新镜像，然后执行：

```bash
cd /opt/repoguard
BACKEND_IMAGE=<new-backend-image> FRONTEND_IMAGE=<new-frontend-image> sh scripts/deploy-prod.sh
```

## 7. 常用排查

```bash
docker compose --env-file .env -f docker-compose.prod.yml ps
docker compose --env-file .env -f docker-compose.prod.yml logs -f backend
docker compose --env-file .env -f docker-compose.prod.yml logs -f frontend
docker compose --env-file .env -f docker-compose.prod.yml logs -f mysql
docker compose --env-file .env -f docker-compose.prod.yml logs -f rabbitmq
```

## 8. 简单回滚

把 `.env` 中的镜像 tag 改回上一个版本，然后执行：

```bash
cd /opt/repoguard
sh scripts/deploy-prod.sh
```

注意：如果新版本已经执行了不兼容的 Flyway 迁移，不能直接假设旧镜像一定能回连新数据库。上线前至少保留一次数据库备份。
