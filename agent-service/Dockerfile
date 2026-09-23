ARG DOCKER_REGISTRY=docker.m.daocloud.io

# agent-service 只负责调度 deepagents + 通过 HTTP 驱动 AIO 沙箱;
# 浏览器/Chromium/VNC 等全部由 AIO 沙箱镜像提供,agent-service 镜像不再内置浏览器。
FROM ${DOCKER_REGISTRY}/library/python:3.13-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY skills /app/skills
RUN find /app -name '*.py' -exec sed -i 's/\r$//' {} +

EXPOSE 8000

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${AGENT_PORT:-8000} --workers ${AGENT_WORKERS:-4}"]
