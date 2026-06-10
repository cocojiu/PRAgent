# PRAgent

RepoGuard Agent 是面向 GitHub Pull Request 的代码审查辅助系统，包含 Spring Boot 后端与 Vue 3 管理台。

## 项目结构

- `repoguard-backend/`：Spring Boot 后端，提供审查任务、配置、GitHub PR 拉取、LLM/规则审查、评论回写等 API。
- `repoguard-frontend/`：Vue 3 + Vite + TypeScript 前端管理台。
- `docs/`：产品、测试和发布相关文档。

## 本地环境

- Node.js：建议使用 Node 22，最低要求 `>=20.19.0`。仓库根目录提供 `.nvmrc`。
- JDK：26。
- Maven：3.9+。

## 常用命令

后端测试：

```bash
cd repoguard-backend
mvn test
```

前端构建：

```bash
cd repoguard-frontend
npm run build
```

前端开发服务默认通过 Vite `/api` 代理访问后端 `http://localhost:8081`。

