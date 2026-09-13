"""agent-service 运行配置:全部来自环境变量,零硬编码。"""

from __future__ import annotations

import os
from pathlib import Path


def _bool(name: str, default: bool = False) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, str(default)))
    except ValueError:
        return default


# --- 模型 ---
# LangChain model 标识,如: openai:gpt-4o-mini / anthropic:claude-sonnet-4-6
# 若提供 AGENT_MODEL_BASE_URL,则按 OpenAI 兼容协议构造(可对接 DeepSeek/Ollama/自建网关)。
AGENT_MODEL: str = os.environ.get("AGENT_MODEL", "openai:gpt-4o-mini")
AGENT_MODEL_BASE_URL: str | None = os.environ.get("AGENT_MODEL_BASE_URL") or None
AGENT_MODEL_API_KEY: str | None = os.environ.get("AGENT_MODEL_API_KEY") or None
AGENT_MODEL_TEMPERATURE: float = float(os.environ.get("AGENT_MODEL_TEMPERATURE", "0.0"))
# DeepSeek V4 思维链开关:false 时请求体带 thinking.type=disabled,减少输出长度与延迟/成本
#(部分场景关闭思考可能影响工具选择质量,可先观察再开关)。
AGENT_MODEL_THINKING: bool = _bool("AGENT_MODEL_THINKING", False)
# 推理强度(low/medium/high/max);仅 thinking 开启时生效。
AGENT_MODEL_REASONING_EFFORT: str = os.environ.get("AGENT_MODEL_REASONING_EFFORT", "high")

# --- 工作区 ---
# 所有用户工作区/技能目录的根。多租户主机上按 <root>/<user_id>/ 隔离。
AGENT_WS_ROOT: Path = Path(os.environ.get("AGENT_WS_ROOT", "/data/users"))

# --- 持久化 ---
# AGENT_PG_DSN 形如 postgresql://user:pass@host:5432/db;为空则退化为内存 checkpointer。
AGENT_PG_DSN: str | None = os.environ.get("AGENT_PG_DSN") or None
# 并发加固:checkpointer/store 改用连接池(多线程/多 worker 并发 checkpoint 安全)。
AGENT_PG_POOL_MIN: int = _int("AGENT_PG_POOL_MIN", 2)
AGENT_PG_POOL_MAX: int = _int("AGENT_PG_POOL_MAX", 10)

# --- 安全 ---
# 打开后所有 write/edit/execute 需人工审批(deepagents interrupt_on)。
AGENT_HITL: bool = _bool("AGENT_HITL", False)

# 内部令牌:网关/agent-service 互相调用的共享凭证(X-Internal-Token)。
# 生产必须设置随机值;缺失即启动失败(fail-fast),不落公开默认值,防默认令牌被滥用。
INTERNAL_TOKEN: str = os.environ.get("INTERNAL_TOKEN", "")
if not INTERNAL_TOKEN:
    raise RuntimeError(
        "INTERNAL_TOKEN 未设置。请在环境变量中配置随机令牌,并与后端 app.internal-token 保持一致。"
    )

# --- 执行沙箱 ---
# AGENT_SANDBOX_BACKEND:
#   docker = execute 与 /workspace 文件工具全部在 per-user 沙箱容器内执行(推荐,默认)
#   local  = 退回 LocalShellBackend(agent-service 进程内直接跑 shell,仅限本机可信调试)
AGENT_SANDBOX_BACKEND: str = os.environ.get("AGENT_SANDBOX_BACKEND", "docker").strip().lower()
# 沙箱镜像(含 python3 + 文档依赖 + node/playwright/chromium + 浏览器入口;见 agent-service/Dockerfile.sandbox)
AGENT_SANDBOX_IMAGE: str = os.environ.get("AGENT_SANDBOX_IMAGE", "ai-workbench-sandbox:latest")
# 沙箱容器挂入的自定义网络(与网关同网,按容器名直连 CDP/MCP);compose 网络名 aiwb-agents
AGENT_SANDBOX_NETWORK_NAME: str = os.environ.get("AGENT_SANDBOX_NETWORK_NAME", "aiwb-agents")


def _resolve_volume_root() -> str:
    """解析 workspace 卷的真实宿主路径,供沙箱 bind mount 用。

    优先使用环境变量 AGENT_SANDBOX_VOLUME_ROOT(手动指定)。
    未指定时自动查询 Docker daemon 解析 <compose项目>_workspace 卷的宿主挂载点,
    避免默认路径在容器内不存在导致 bind mount 失败。
    """
    env_val = os.environ.get("AGENT_SANDBOX_VOLUME_ROOT", "").strip()
    if env_val:
        return env_val
    try:
        import docker as _docker

        cli = _docker.from_env()
        # compose 卷命名规则: <project>_workspace
        vol_name = os.environ.get("COMPOSE_PROJECT_NAME", "docker") + "_workspace"
        vol = cli.volumes.get(vol_name)
        mount_point = vol.attrs["Mountpoint"]  # 如 /var/lib/docker/volumes/docker_workspace/_data
        return mount_point + "/users"
    except Exception:
        # 回退:常见 Linux Docker 默认路径
        return "/var/lib/docker/volumes/docker_workspace/_data/users"


# 宿主(daemon 视角)workspace 卷下 users 根:用于按用户 bind 子目录到沙箱 /workspace。
AGENT_SANDBOX_VOLUME_ROOT: str = _resolve_volume_root()
# 沙箱内运行 uid(非 root);agent-service 会先把该用户 project 目录 chown 成此 uid。
AGENT_SANDBOX_UID: int = _int("AGENT_SANDBOX_UID", 10001)
AGENT_K8S_SANDBOX_UID_BASE: int = _int("AGENT_K8S_SANDBOX_UID_BASE", 100000)
# 是否允许沙箱访问网络(合并浏览器后需开网,execute 也随之可联网)
AGENT_SANDBOX_NETWORK: bool = _bool("AGENT_SANDBOX_NETWORK", True)
AGENT_SANDBOX_MEM_MB: int = _int("AGENT_SANDBOX_MEM_MB", 1024)
AGENT_SANDBOX_CPUS: float = float(os.environ.get("AGENT_SANDBOX_CPUS", "1.0"))
AGENT_SANDBOX_PIDS: int = _int("AGENT_SANDBOX_PIDS", 256)
# 空闲沙箱容器回收(秒)
AGENT_SANDBOX_IDLE_TTL: int = _int("AGENT_SANDBOX_IDLE_TTL", 1800)
# 单条命令超时上限(秒),deepagents 传入的 timeout 会被 clamp 到该值
AGENT_SANDBOX_MAX_TIMEOUT: int = _int("AGENT_SANDBOX_MAX_TIMEOUT", 120)

# --- K8s 调度策略 ---
# CPU/内存 requests(调度器据此判断节点余量,应接近实际常用值)
AGENT_K8S_SANDBOX_CPU_REQUESTS: str = os.environ.get("AGENT_K8S_SANDBOX_CPU_REQUESTS", "500m")
AGENT_K8S_SANDBOX_MEM_REQUESTS: str = os.environ.get("AGENT_K8S_SANDBOX_MEM_REQUESTS", "512Mi")
# 拓扑分布:Pod 均匀散布到各节点(防止单节点过载)
AGENT_K8S_SCHEDULING_SPREAD: bool = _bool("AGENT_K8S_SCHEDULING_SPREAD", True)
# 反亲和:同一用户的多个 Pod 不落同一节点
AGENT_K8S_SCHEDULING_AFFINITY: bool = _bool("AGENT_K8S_SCHEDULING_AFFINITY", True)

# --- K8s 部署模式(AGENT_SANDBOX_BACKEND=k8s) ---
# 集群内加载 kubeconfig;走 incluster 时从 POD 内 ServiceAccount 自动发现
AGENT_K8S_NAMESPACE: str = os.environ.get("AGENT_K8S_NAMESPACE", "ai-workbench")
# workspace RWX PVC 名:沙箱 Pod 用 subPath users/<uid>/project 挂到 /workspace,
# 与 agent-service 自身 /shared-ws 与网关 app /workspace 指向同一份数据。
# (仅 AGENT_K8S_SANDBOX_VOLUME_TYPE=pvc 时使用;hostpath 模式忽略)
AGENT_K8S_WORKSPACE_PVC: str = os.environ.get("AGENT_K8S_WORKSPACE_PVC", "workspace")
# 沙箱 Pod 用 ServiceAccount(可选;缺省复用 agent-service 自身 SA)
AGENT_K8S_SANDBOX_SERVICE_ACCOUNT: str = os.environ.get("AGENT_K8S_SANDBOX_SERVICE_ACCOUNT", "")
# 沙箱 Pod 镜像拉取策略(本地导入镜像用 IfNotPresent)
AGENT_K8S_PULL_POLICY: str = os.environ.get("AGENT_K8S_PULL_POLICY", "IfNotPresent")
# 沙箱工作区挂载模式:
#   hostpath = kind/docker-desktop 节点经 extraMounts 直挂 compose workspace 卷(本机混合架构默认)
#   pvc      = 集群内 PVC + subPath users/<uid>/project(集群自持存储时使用)
AGENT_K8S_SANDBOX_VOLUME_TYPE: str = os.environ.get(
    "AGENT_K8S_SANDBOX_VOLUME_TYPE", "hostpath"
).strip().lower()
# hostpath 模式的卷根:kind 节点内 extraMounts 把 compose workspace 卷挂到这里,
# 沙箱挂 users/<uid>/project → 与 app /workspace 与 agent-service /shared-ws 同一份数据。
AGENT_K8S_SANDBOX_VOLUME_HOST_PATH: str = os.environ.get(
    "AGENT_K8S_SANDBOX_VOLUME_HOST_PATH", "/mnt/compose-workspace"
)
# kind 节点在 compose 桥网上的主机名(网关/agent-service 按此 DNS 直连 NodePort)。
# 若使用 Docker Desktop 内置 K8s(desktop-control-plane)不适用,需按实际节点名调整,或改为 IP。
AGENT_K8S_SANDBOX_NODE_ADDR: str = os.environ.get(
    "AGENT_K8S_SANDBOX_NODE_ADDR", "aiwb-control-plane"
)
# 是否给每用户沙箱创建一个 NodePort Service,把 CDP(9223)/MCP(8931)暴露到 node 上,
# 供 compose 侧(网关/agent-service)经 <node_addr>:<nodePort> 直连。关闭时退回 Pod IP 直连
# (仅集群内可达,compose 侧无法连)。
AGENT_K8S_SANDBOX_NODEPORT: bool = _bool("AGENT_K8S_SANDBOX_NODEPORT", True)
# NodePort 基数:CDP = base + uid%2000,MCP = base + 500 + uid%2000(与网关 Java 公式一致)。
AGENT_K8S_SANDBOX_NODEPORT_BASE: int = _int("AGENT_K8S_SANDBOX_NODEPORT_BASE", 30000)
# 可选:覆盖 kubeconfig 里的 apiserver 地址(如 host.docker.internal:<port>)。
# kubeconfig 默认写 127.0.0.1(宿主导向),agent-service 在容器内需换成可达地址。
AGENT_K8S_API_HOST: str = os.environ.get("AGENT_K8S_API_HOST", "")

# --- 服务 ---
AGENT_HOST: str = os.environ.get("AGENT_HOST", "0.0.0.0")
AGENT_PORT: int = _int("AGENT_PORT", 8000)
# 并发加固:多个 uvicorn worker 各自处理请求(进程级并行,配合 PG 连接池)。
AGENT_WORKERS: int = max(1, _int("AGENT_WORKERS", 4))
AGENT_MAX_TURNS: int = _int("AGENT_MAX_TURNS", 50)
AGENT_LOG_LEVEL: str = os.environ.get("AGENT_LOG_LEVEL", "info")

# --- 后端内部接口(create_skill 工具回写技能库;令牌需与后端 app.internal-token 一致) ---
BACKEND_INTERNAL_URL: str = os.environ.get("BACKEND_INTERNAL_URL", "http://app:8080").rstrip("/")