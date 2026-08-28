ARG DOCKER_REGISTRY=docker.m.daocloud.io

# 复用旧 opencode 版 agent 镜像里已烘焙的 playwright + chromium(版本与 python playwright 1.62.0 对齐,
# chromium 修订号 1234),避免重复下载浏览器。
FROM ai-workbench-agent AS browser-stage

FROM ${DOCKER_REGISTRY}/library/python:3.13-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

WORKDIR /app

# python playwright 固定与旧镜像 chromium 修订号(1234)匹配的版本;install-deps 只装系统库,不下载浏览器
RUN sed -i 's|deb.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources \
  && apt-get update \
  && pip install --no-cache-dir "playwright==1.62.0" \
  && python3 -m playwright install-deps chromium \
  && apt-get clean && rm -rf /var/lib/apt/lists/*

# 从旧镜像复制浏览器二进制(headless shell)+ 系统依赖不变
COPY --from=browser-stage /usr/local/share/ms-playwright /ms-playwright

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY skills /app/skills
RUN find /app -name '*.py' -exec sed -i 's/\r$//' {} +

EXPOSE 8000

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${AGENT_PORT:-8000} --workers ${AGENT_WORKERS:-4}"]