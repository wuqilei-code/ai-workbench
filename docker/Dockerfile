# 基镜像仓库:默认 docker.io;在 docker-compose.yml 用国内镜像(如 docker.m.daocloud.io)覆盖。
# 不使用 # syntax=docker/dockerfile:1 指令,避免 BuildKit 额外从 Docker Hub 拉取前端镜像。
ARG DOCKER_REGISTRY=docker.io

# ---------- Stage 1: frontend ----------
FROM ${DOCKER_REGISTRY}/node:22-bookworm-slim AS frontend-build
WORKDIR /web
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

ARG OPENCODE_VERSION=1.18.8
ARG OPENCODE_DOWNLOAD_URL=
# 默认从 sst/opencode 官方 release 下载;GitHub 不可达时,在 docker-compose.yml 用
# OPENCODE_DOWNLOAD_URL 指向代理(如 ghfast.top)或内部镜像

RUN apt-get update \
  && apt-get install -y --no-install-recommends \
       nginx supervisor curl ca-certificates git procps bash \
       python3 python3-pip python3-venv \
       fonts-noto-cjk \
       unzip poppler-utils \
       docker.io \
  && rm -rf /var/lib/apt/lists/*

# Python 常用依赖:文档操作 / API / 数据处理(系统级安装,所有 uid 可读;AI 生成的脚本直接 import 即可)
# pip 用 --break-system-packages:容器内装到系统 site-packages,per-user uid 运行时无需 venv 即可 import。
# 默认用阿里云 PyPI 镜像加速构建;内网构建用 --build-arg PIP_INDEX_URL 指向内网 PyPI。
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
RUN pip3 install --no-cache-dir --break-system-packages \
      --index-url ${PIP_INDEX_URL} \
      python-docx python-pptx openpyxl reportlab PyPDF2 pdfplumber markdown \
      numpy pandas matplotlib \
      requests httpx aiohttp \
      flask fastapi uvicorn pydantic \
      beautifulsoup4 lxml Pillow \
 && python3 -c "import docx, pptx, openpyxl, reportlab, PyPDF2, pdfplumber, markdown, numpy, pandas, matplotlib, requests, httpx, aiohttp, flask, fastapi, uvicorn, pydantic, bs4, lxml, PIL; print('python deps ok')"

# 运行时禁止 AI 自行 pip install 下载依赖(方案 B:删 pip + ensurepip)。
# 构建期已装好预装依赖(系统 site-packages),AI 运行时只需 import,无需 pip。
# 1) 卸载 python3-pip 包 + 清理 pip 可执行文件与模块 → `pip install` / `python3 -m pip` 不可用
# 2) 删 ensurepip 模块 → 防止 `python3 -m ensurepip` 重装 pip;且 `python3 -m venv` 创建的虚拟环境不再带 pip
# 预装依赖不受影响(已落系统 site-packages),下方验证 import 仍可用。
RUN apt-get purge -y python3-pip \
  && apt-get autoremove -y \
  && rm -rf /usr/lib/python3*/ensurepip \
  && rm -f /usr/bin/pip /usr/bin/pip3 \
  && rm -rf /usr/lib/python3/dist-packages/pip \
  && python3 -c "import sys; print('python ok')" \
  && python3 -c "import docx, pptx, openpyxl, pandas, requests; print('preinstalled deps still ok')"

# 下载 opencode 二进制
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
      amd64) asset="opencode-linux-x64-baseline.tar.gz" ;; \
      arm64) asset="opencode-linux-arm64.tar.gz" ;; \
      *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
    esac; \
    url="${OPENCODE_DOWNLOAD_URL:-https://github.com/sst/opencode/releases/download/v${OPENCODE_VERSION}/${asset}}"; \
    tmpdir="$(mktemp -d)"; \
    curl -fsSL "$url" -o "$tmpdir/$asset"; \
    tar -xzf "$tmpdir/$asset" -C "$tmpdir"; \
    bin="$(find "$tmpdir" -type f -name opencode | head -n1)"; \
    test -n "$bin"; \
    install -m 0755 "$bin" /usr/local/bin/opencode; \
    rm -rf "$tmpdir"; \
    opencode --version

# 后端 jar
COPY --from=backend-build /build/target/*.jar /app/app.jar

# 前端静态资源
COPY --from=frontend-build /web/dist /usr/share/nginx/html

# 运行时配置
COPY docker/supervisord.conf /etc/supervisord.conf
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/entrypoint.sh /entrypoint.sh
COPY docker/opencode/agents/chat.md /opt/aiworkbench/agents/chat.md
COPY docker/opencode/opencode.json.tpl /opt/aiworkbench/opencode.json.tpl
COPY docker/opencode/skills /opt/aiworkbench/skills
RUN chmod +x /entrypoint.sh \
  && rm -f /etc/nginx/sites-enabled/default

ENV OPENWORK_WORKSPACE=/workspace \
    OPENCODE_DATA_DIR=/data/opencode \
    HOME=/root \
    OPENCODE_SERVER_USERNAME=opencode \
    OPENCODE_SERVER_PASSWORD=changeme \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 80
VOLUME ["/workspace", "/data"]

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=10 \
  CMD curl -fsS http://127.0.0.1/api/health >/dev/null || exit 1

ENTRYPOINT ["/entrypoint.sh"]
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisord.conf"]

# ---------- Stage 4: agent-runtime(每用户 agent 容器专用精简镜像) ----------
# 基础镜像 python:3.11-slim-bookworm(daocloud 官方命名空间可拉取,198MB)。
# 只含 agent 运行所需:opencode 二进制 + python 文档/数据处理依赖 + git + 基础工具。
# 不含 java/nginx/supervisord/docker CLI/app jar/前端 —— 镜像更小、启动更快、每容器内存更低。
# python 依赖仍在构建期经 PIP_INDEX_URL(默认阿里云,内网构建指内网 PyPI)烘焙进镜像,
# 运行期内网无网也能 import(docx/pptx/xlsx/pdf/图表等文档处理能力与 app 镜像完全一致)。
# 本镜像不声明 VOLUME /workspace /data,避免 agent 容器被自动创建匿名卷(挂载由网关按用户子目录 bind)。
FROM ${DOCKER_REGISTRY}/python:3.11-slim-bookworm AS agent-runtime

ARG OPENCODE_VERSION=1.18.8
ARG OPENCODE_DOWNLOAD_URL=
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/

RUN sed -i 's|deb.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources \
  && apt-get update \
  && apt-get install -y --no-install-recommends \
       curl ca-certificates git procps mawk bash \
       unzip poppler-utils \
       fonts-noto-cjk \
  && rm -rf /var/lib/apt/lists/*

# Python 常用依赖(与 runtime 阶段同清单):文档操作 / 数据处理 / API / 图表
RUN pip3 install --no-cache-dir --break-system-packages \
      --index-url ${PIP_INDEX_URL} \
      python-docx python-pptx openpyxl reportlab PyPDF2 pdfplumber markdown \
      numpy pandas matplotlib \
      requests httpx aiohttp \
      flask fastapi uvicorn pydantic \
      beautifulsoup4 lxml Pillow \
 && python3 -c "import docx, pptx, openpyxl, reportlab, PyPDF2, pdfplumber, markdown, numpy, pandas, matplotlib, requests, httpx, aiohttp, flask, fastapi, uvicorn, pydantic, bs4, lxml, PIL; print('agent python deps ok')"

# 运行时禁止 AI 自行 pip install(与 runtime 阶段一致):移除 pip/ensurepip,预装依赖不受影响。
RUN rm -f /usr/local/bin/pip /usr/local/bin/pip3 /usr/local/bin/pip3.11 \
  && rm -rf /usr/local/lib/python3.11/site-packages/pip* \
  && rm -rf /usr/local/lib/python3.11/ensurepip /usr/lib/python3*/ensurepip \
  && python3 -c "import docx, openpyxl, pandas, matplotlib; print('preinstalled deps still ok')"

# 下载 opencode 二进制(与 runtime 阶段同源同版本)
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
      amd64) asset="opencode-linux-x64-baseline.tar.gz" ;; \
      arm64) asset="opencode-linux-arm64.tar.gz" ;; \
      *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
    esac; \
    url="${OPENCODE_DOWNLOAD_URL:-https://github.com/sst/opencode/releases/download/v${OPENCODE_VERSION}/${asset}}"; \
    tmpdir="$(mktemp -d)"; \
    curl -fsSL "$url" -o "$tmpdir/$asset"; \
    tar -xzf "$tmpdir/$asset" -C "$tmpdir"; \
    bin="$(find "$tmpdir" -type f -name opencode | head -n1)"; \
    test -n "$bin"; \
    install -m 0755 "$bin" /usr/local/bin/opencode; \
    rm -rf "$tmpdir"; \
    opencode --version

# 网关创建容器时会显式 --entrypoint opencode serve ...;此处仅保证直接 docker run 可运行
ENTRYPOINT ["opencode"]
