# AI Workbench

基于 [OpenCode](https://opencode.ai) 内核的多用户通用 AI 对话工作台。服务端部署,浏览器访问,多用户注册与会话隔离,文件操作与沙箱执行,MySQL 存储,管理员全局配置模型,面向内网私有化部署。

## 架构

```
浏览器 ──80──▶ nginx (Vue SPA + /api 反代) ──▶ Java Spring Boot 网关 (8080)
                                                │ HTTP + Basic Auth + SSE
                                                │ x-opencode-directory: /workspace/users/<uid>
                                                ▼
                                          opencode serve (127.0.0.1:4096)  ← AI 内核
                                                │
                                          MySQL 8 (用户/会话/消息/模型配置)
```

- **opencode** 作为独立常驻 HTTP 服务(supervisord 拉起),Java 后端通过 WebClient + SSE 调用
- **隔离**:每用户 `/workspace/users/<uid>/` 目录 + `x-opencode-directory` header
- **单 app 容器**(Java 网关 + opencode + nginx)+ MySQL 兄弟容器

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21 + Spring Boot 3.3 + MyBatis-Plus + Spring Security(JWT) |
| 前端 | Vue 3 + Vite + TypeScript + Pinia + Naive UI |
| 数据库 | MySQL 8 |
| 引擎 | opencode(独立 HTTP 服务) |
| 容器 | Docker multi-stage + supervisord + nginx |

> 注:容器内使用 Java 21(`eclipse-temurin:21-jre`),宿主机 Java 版本不影响构建与运行。

## 快速开始

```bash
cd ai-workbench/docker
docker compose up --build
```

- 浏览器打开 `http://localhost/`
- 默认首个注册用户自动成为管理员
- 管理员在「管理 → 模型配置」添加 provider/model 后点 Apply,即可开始对话

## 本地开发与验证

容器化部署是首选路径(见上)。如需本地构建/调试:

```bash
# 后端(需 JDK 21+、Maven 3.6+)
cd backend
mvn clean package -DskipTests        # 产物 target/app.jar

# 前端(需 Node 18+)
cd frontend
npm install
npm run build                        # 产物 dist/
npm run dev                          # 开发服务器,Vite 代理 /api → localhost:8080
```

> **镜像坑提示**:若本机 Maven 全局 `settings.xml` 配置了华为云镜像(`repo.huaweicloud.com`),部分 artifact(如 `spring-boot-starter-parent`)可能被返回 HTML 错误页而非真实 POM,导致 `Non-parseable POM`。改用阿里云镜像(`https://maven.aliyun.com/repository/public`)即可。

构建已验证:后端 62 个源文件编译 + 打包通过(`app.jar`),前端 `vite build` 通过。

## 配置(环境变量,见 `docker/docker-compose.yml`)

| 变量 | 说明 |
|---|---|
| `OPENCODE_SERVER_USERNAME` / `OPENCODE_SERVER_PASSWORD` | opencode HTTP Basic 认证(网关→opencode) |
| `JWT_SECRET` | JWT 签名密钥 |
| `APP_REGISTRATION_ENABLED` | 是否开放注册(true/false) |
| `MYSQL_*` | MySQL 连接 |
| `OPENCODE_VERSION` / `OPENCODE_DOWNLOAD_URL` | opencode 二进制版本/内网镜像地址 |

## 目录

```
ai-workbench/
├── docker/        Dockerfile + compose + supervisord + nginx + opencode 配置
├── backend/       Spring Boot 3 (Maven)
└── frontend/      Vue 3 + Vite + TS
```

详见 [多用户安全加固方案](.trae/documents/ai-workbench-per-user-isolation.md)。
