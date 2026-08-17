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
# 国内网络:容器内 Maven 无法直连 repo.maven.apache.org,改用阿里云镜像
COPY docker/settings.xml /root/.m2/settings.xml
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
       unzip \
       docker.io \
  && rm -rf /var/lib/apt/lists/* \
  # 清理 docker CLI 附带的无关 bash-completion/文档,进一步瘦身
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
COPY docker/opencode/agents /opt/aiworkbench/agents
COPY docker/opencode/opencode.json.tpl /opt/aiworkbench/opencode.json.tpl
COPY docker/opencode/skills /opt/aiworkbench/skills
RUN chmod +x /entrypoint.sh \
  && rm -f /etc/nginx/sites-enabled/default

ENV OPENWORK_WORKSPACE=/workspace \
    OPENCODE_DATA_DIR=/data/opencode \
    HOME=/root \
    TZ=Asia/Shanghai \
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
       curl ca-certificates git procps mawk bash tzdata \
       unzip poppler-utils \
       openssh-client sshpass libnss-wrapper \
  && rm -rf /var/lib/apt/lists/* \
  && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

ENV TZ=Asia/Shanghai

# 中文字体:Noto CJK 是 CFF/TTC 轮廓,reportlab 无法加载且体积大(89MB)。
# 统一改用后端内置的 simhei.ttf(TrueType 黑体,9.7MB),reportlab 与 matplotlib 均可直接加载。
# 技能文档(document-generation / data-analysis)从该固定路径注册字体,不再依赖 app.jar。
COPY backend/src/main/resources/fonts/simhei.ttf /usr/share/fonts/truetype/simhei.ttf

# Python 常用依赖(与 runtime 阶段同清单):文档操作 / 数据处理 / API 客户端 / 图表 + 测试框架(pytest/coverage)
# 注意:不引入 flask/fastapi/uvicorn/pydantic 等 Web 服务端框架——预览服务已弃用,agent 只做生成/创建任务。
RUN pip3 install --no-cache-dir --break-system-packages \
      --index-url ${PIP_INDEX_URL} \
      python-docx python-pptx openpyxl reportlab PyPDF2 pdfplumber markdown \
      numpy pandas matplotlib \
      requests httpx aiohttp \
      beautifulsoup4 lxml Pillow \
      pytest coverage \
 && python3 -c "import docx, pptx, openpyxl, reportlab, PyPDF2, pdfplumber, markdown, numpy, pandas, matplotlib, requests, httpx, aiohttp, bs4, lxml, PIL, pytest, coverage; print('agent python deps ok')" \
 # 同一层内清掉字节码缓存(跨层删除不回收镜像体积)
 && find /usr/local/lib/python3.11 -name '__pycache__' -type d -prune -exec rm -rf {} + \
 && find /usr/local/lib/python3.11 -name '*.pyc' -delete

# 运行时禁止 AI 自行 pip install(与 runtime 阶段一致):移除 pip/ensurepip,预装依赖不受影响。
RUN rm -f /usr/local/bin/pip /usr/local/bin/pip3 /usr/local/bin/pip3.11 \
  && rm -rf /usr/local/lib/python3.11/site-packages/pip* \
  && rm -rf /usr/local/lib/python3.11/ensurepip /usr/lib/python3*/ensurepip \
  && python3 -c "import docx, openpyxl, pandas, matplotlib; print('preinstalled deps still ok')"

# ---------- Node.js 前端支持(agent 容器) ----------
# 官方 Node 22 LTS tarball(gz 格式,tar 直接解压,不依赖额外 xz 工具)。
# apt 源只有已 EOL 的 node 18,故用官方 tarball 保证较新运行时。
ARG NODE_VERSION=22.14.0
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
      amd64) nodearch="linux-x64" ;; \
      arm64) nodearch="linux-arm64" ;; \
      *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
    esac; \
    url="https://npmmirror.com/mirrors/node/v${NODE_VERSION}/node-v${NODE_VERSION}-${nodearch}.tar.gz"; \
    tmpdir="$(mktemp -d)"; \
    curl -fsSL "$url" -o "$tmpdir/node.tar.gz"; \
    tar -xzf "$tmpdir/node.tar.gz" -C /usr/local --strip-components=1; \
    rm -rf "$tmpdir"; \
    node --version && npm --version

# npm 国内镜像 + 全局前端 CLI(vite 构建/开发、typescript、tsx 运行 TS)+ MySQL MCP server(agent 查库用,默认只读)
# 保留 npm(前端项目常需 install 依赖;内网/国内可达用 npmmirror 镜像)。
RUN npm config set registry ${NPM_REGISTRY} --global \
  && npm install -g --no-audit --no-fund vite typescript tsx @benborla29/mcp-server-mysql \
  && npm config get registry \
  && npm ls -g --depth=0 \
  # 同层清理:npm 缓存 + JS 源码映射(运行期不需要;跨层删不缩体积)
  && npm cache clean --force \
  && rm -rf /root/.npm \
  && find /usr/local/lib/node_modules -name '*.map' -delete

# ---------- Browser automation(Playwright MCP):通用网页自动化/研究 ----------
# agent 容器以 --user 非 root 运行(/root 不可读),故浏览器二进制放共享可读路径 /usr/local/share/ms-playwright
# (构建期 root 安装,0755/0644 子树对任意 uid 只读可执行);NODE_PATH 使 node -e 能 require 全局包。
# 浏览器下载走 npmmirror 镜像(PLAYWRIGHT_DOWNLOAD_HOST),与 npm registry 同源内网可达。
ENV PLAYWRIGHT_BROWSERS_PATH=/usr/local/share/ms-playwright \
    NODE_PATH=/usr/local/lib/node_modules
RUN npm install -g --no-audit --no-fund playwright @playwright/mcp \
  && PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright npx playwright install --with-deps chromium chrome \
  && command -v playwright-mcp \
  && node -e "const {chromium}=require('playwright');(async()=>{const b=await chromium.launch({headless:true,args:['--no-sandbox']});const p=await b.newPage();await p.goto('data:text/html,<h1>ok</h1>');console.log('playwright chromium smoke ok');await b.close()})().catch(e=>{console.error('playwright smoke failed:',e);process.exit(1)})" \
  # 同层清理:npm 缓存 + 源码映射(同上)
  && npm cache clean --force \
  && rm -rf /root/.npm \
  && find /usr/local/lib/node_modules -name '*.map' -delete

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

# 每用户 agent 容器入口 wrapper:后台常驻 playwright-mcp 共享浏览器 daemon 后 exec opencode serve
# (网关创建容器时显式 --entrypoint /opt/aiworkbench/agent-entrypoint.sh;见 AgentContainerManager)
COPY docker/opencode/agent-entrypoint.sh /opt/aiworkbench/agent-entrypoint.sh
RUN chmod +x /opt/aiworkbench/agent-entrypoint.sh

# 网关创建容器时会显式 --entrypoint opencode serve ...;此处仅保证直接 docker run 可运行
ENTRYPOINT ["opencode"]
