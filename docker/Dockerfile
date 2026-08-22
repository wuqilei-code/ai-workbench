# 基镜像仓库:默认 docker.io;在 docker-compose.yml 用国内镜像(如 docker.m.daocloud.io)覆盖。
# 不使用 # syntax=docker/dockerfile:1 指令,避免 BuildKit 额外从 Docker Hub 拉取前端镜像。
ARG DOCKER_REGISTRY=docker.io

# ---------- Stage 1: frontend ----------
FROM ${DOCKER_REGISTRY}/node:22-bookworm-slim AS frontend-build
WORKDIR /web
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry ${NPM_REGISTRY} --global
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- Stage 2: backend ----------
FROM ${DOCKER_REGISTRY}/maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /build
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline -DfailOnMissingWebXml=false || true
COPY backend/src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- Stage 3: final runtime ----------
# 注:理想基镜像为 eclipse-temurin:21-jre-bookworm,但当前环境(国内镜像)未缓存该非 official 命名空间
# 镜像且无法回源。这里复用已拉取的 maven:3.9-eclipse-temurin-21(含 JDK 21,Ubuntu 22.04 基础,
# apt 源 archive.ubuntu.com 可达)作为运行时基镜像,确保烟测可构建。生产环境(Docker Hub 可达)请改回
# FROM ${DOCKER_REGISTRY}/eclipse-temurin:21-jre-bookworm 以获得更小的 JRE 镜像。
FROM ${DOCKER_REGISTRY}/maven:3.9-eclipse-temurin-21 AS runtime

# app 是纯 Java 服务(网关 + nginx + docker CLI 管理 agent 容器),不执行 AI 生成的 python 脚本,
# 故不装 python/文档处理依赖(那些只在 agent 镜像需要)。只装运行所需:nginx/supervisor/docker CLI/git/curl。
RUN apt-get update \
  && apt-get install -y --no-install-recommends \
       nginx supervisor curl ca-certificates git procps bash tzdata \
       unzip docker.io \
  && rm -rf /var/lib/apt/lists/* \
  # 清理附带的无关 bash-completion/文档,进一步瘦身
  && rm -rf /usr/share/doc /usr/share/man /usr/share/locale /usr/share/i18n \
  && rm -rf /var/lib/apt/lists/* \
  && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

# 后端 jar
COPY --from=backend-build /build/target/*.jar /app/app.jar

# 前端静态资源
COPY --from=frontend-build /web/dist /usr/share/nginx/html

# 运行时配置
COPY docker/supervisord.conf /etc/supervisord.conf
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh \
  && rm -f /etc/nginx/sites-enabled/default

ENV WORKSPACE_ROOT=/workspace \
    WORKSPACE_UID_BASE=100000 \
    HOME=/root \
    TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 80
VOLUME ["/workspace", "/data"]

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=10 \
  CMD curl -fsS http://127.0.0.1/api/health >/dev/null || exit 1

ENTRYPOINT ["/entrypoint.sh"]
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisord.conf"]
