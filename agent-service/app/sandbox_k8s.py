"""K8sSandbox:K8s 部署模式下每用户沙箱后端(替代 DockerSandbox 的 docker.sock 依赖)。

隔离边界与 docker 版一致,但资源模型变成 K8s 原生:
- 每个用户一个常驻沙箱 Pod(aiwb-sandbox-u<uid>),subPath 挂 workspace PVC 的
  users/<uid>/project 到容器内 /workspace —— shell 里用绝对路径也看不到其他用户目录。
- Pod 以非 root uid(AGENT_SANDBOX_UID)运行,capabilities 裁剪 + no-new-privileges,
  CPU/内存限额。
- 常驻入口 browser-entrypoint.sh 在 Pod 内拉起 chromium/CDP 转发/playwright-mcp;
  同名 headless Service 让网关在后端集群内按 `aiwb-sandbox-u<uid>:9223(8931)` DNS
  直连 —— 与 docker compose 的 aiwb-agents 桥网行为保持一致。
- execute 通过 K8s exec API;upload/download 直接读写 agent-service 自身挂载的同一
  workspace 卷(/shared-ws/users/<uid>/project,与沙箱 /workspace 指向同一份数据),
  无需 put/get_archive 往返。

实现 deepagents BaseSandbox:execute/upload_files/download_files/id。
文件工具(ls/grep/glob/read/edit)由基类自动转为沙箱内 shell 命令;write 走 upload_files。
"""

from __future__ import annotations

import logging
import os
import threading
import time
from pathlib import Path, PurePosixPath
from typing import Any

from deepagents.backends.protocol import (
    ExecuteResponse,
    FileDownloadResponse,
    FileUploadResponse,
)
from deepagents.backends.sandbox import BaseSandbox

from app import config
from app import workspace as ws

logger = logging.getLogger(__name__)

_MAX_OUTPUT_BYTES = 100_000

# K8s exec 返回的 shell 尾哨:解析命令真实退出码(kubernetes client 的 stream 不暴露 exit code)
_EC_MARKER = "__AIWB_EC__"


def _safe_rel(path: str) -> PurePosixPath:
    """与 DockerSandbox 同款校验:拒绝绝对路径与目录穿越;剥掉 /workspace 前缀。"""
    p = PurePosixPath(path.replace("\\", "/"))
    if p.is_absolute():
        s = str(p)
        for prefix in ("/workspace/", "/workspace"):
            if s.startswith(prefix):
                s = s[len(prefix):]
                break
        s = s.lstrip("/")
        if not s:
            raise ValueError(f"非法路径: {path!r}")
        p = PurePosixPath(s)
    if ".." in p.parts or str(p).startswith("~"):
        raise ValueError(f"非法路径: {path!r}")
    return p


class K8sSandbox(BaseSandbox):
    """一个 user_id 对应一个实例;Pod 按需创建/复用,空闲超时自动删除。"""

    def __init__(self, user_id: str) -> None:
        self.user_id = user_id
        self._user_uid = config.AGENT_K8S_SANDBOX_UID_BASE + int(user_id)
        # 保持与 docker 版完全一致的命名:网关 BrowserContainerManager/Service 名都按此推导
        self.name = self._pod_name(user_id)
        self._core = None
        self._namespace = config.AGENT_K8S_NAMESPACE
        self._last_used = time.monotonic()
        self._lock = threading.Lock()
        self._recent_changes: list[str] = []
        from app.sandbox import register_sandbox

        register_sandbox(self)

    @staticmethod
    def _pod_name(user_id: str) -> str:
        # DNS-1123:小写字母数字与连字符;user_id 通常为数字/短横线
        import re

        s = re.sub(r"[^a-z0-9-]", "-", user_id.lower()).strip("-")
        return f"aiwb-sandbox-u{s or 'x'}"

    # --- lifecycle -------------------------------------------------------

    def touch(self) -> None:
        self.last_used = time.monotonic()

    @property
    def last_used(self) -> float:
        return self._last_used

    @last_used.setter
    def last_used(self, value: float) -> None:
        self._last_used = value

    def _clients(self):
        if self._core is None:
            from kubernetes import client, config as k8s_config

            cfg = client.Configuration()
            try:
                k8s_config.load_incluster_config(client_configuration=cfg)
            except Exception:  # noqa: BLE001 非集群内(本地开发/CI)
                k8s_config.load_kube_config(client_configuration=cfg)
            # kubeconfig server 默认指向宿主视角的 127.0.0.1:port,
            # 容器内(compose)需要换成可达地址(host.docker.internal:port)。
            if config.AGENT_K8S_API_HOST:
                cfg.host = f"https://{config.AGENT_K8S_API_HOST}"
            self._core = client.CoreV1Api(api_client=client.ApiClient(configuration=cfg))
        return self._core

    def _pod_labels(self) -> list[tuple[str, str]]:
        return [("app", "ai-workbench"), ("aiwb.role", "agent-sandbox"), ("aiwb.user", self.user_id), ("user_id", self.user_id)]

    # --- Pod / Service 管理 ----------------------------------------------

    def _port_mod(self) -> int:
        """NodePort 索引:同一 uid 稳定唯一(mod 2000)。"""
        digits = set(self.user_id)
        if digits.issubset("0123456789"):
            return int(self.user_id) % 2000
        return sum(map(ord, self.user_id)) % 2000

    def _service_port_defs(self) -> list[dict]:
        """CDP/MCP 端口定义;NodePort 模式下分配集群 nodePort。
        公式与网关 Java(BrowserContainerManager)一致:
        CDP = base + uid%2000,MCP = base + 500 + uid%2000。
        """
        m = self._port_mod()
        if config.AGENT_K8S_SANDBOX_NODEPORT:
            base = config.AGENT_K8S_SANDBOX_NODEPORT_BASE
            return [
                {"name": "mcp", "port": 8931, "targetPort": 8931, "nodePort": base + 500 + m},
                {"name": "cdp", "port": 9223, "targetPort": 9223, "nodePort": base + m},
            ]
        return [
            {"name": "mcp", "port": 8931, "targetPort": 8931},
            {"name": "cdp", "port": 9223, "targetPort": 9223},
        ]

    def _ensure_service(self):
        """确保每用户 Service 存在(网关/浏览器经此直连 CDP/MCP)。

        NodePort 模式:type=NodePort,compose 侧经 <node_addr>:<nodePort> 访问。
        非 NodePort:headless Service,DNS aiwb-sandbox-u<uid> 解析 Pod,集群内可达。
        """
        core = self._clients()
        spec: dict[str, Any] = {"selector": dict(self._pod_labels()), "ports": self._service_port_defs()}
        if not config.AGENT_K8S_SANDBOX_NODEPORT:
            spec["clusterIP"] = "None"
        else:
            spec["type"] = "NodePort"
        body = {
            "apiVersion": "v1",
            "kind": "Service",
            "metadata": {
                "name": self.name,
                "namespace": self._namespace,
                "labels": dict(self._pod_labels()),
            },
            "spec": spec,
        }
        try:
            core.create_namespaced_service(namespace=self._namespace, body=body)
        except Exception:  # noqa: BLE001  已存在(跨 worker/回收竞态)
            pass
        return self.name

    @staticmethod
    def _container_port_defs() -> list[dict]:
        return [
            {"name": "cdp", "containerPort": 9223},
            {"name": "mcp", "containerPort": 8931},
        ]

    def _create_pod(self):
        core = self._clients()
        # 工作区挂载:hostpath 直挂 compose 卷目录(混合架构);pvc 用 subPath(集群存储)。
        if config.AGENT_K8S_SANDBOX_VOLUME_TYPE == "pvc":
            workspace_volume = {
                "name": "workspace",
                "persistentVolumeClaim": {"claimName": config.AGENT_K8S_WORKSPACE_PVC},
            }
            workspace_mount = {
                "name": "workspace",
                "mountPath": "/workspace",
                "subPath": f"users/{self.user_id}/project",
            }
        else:  # hostpath
            workspace_volume = {
                "name": "workspace",
                "hostPath": {
                    "path": f"{config.AGENT_K8S_SANDBOX_VOLUME_HOST_PATH.rstrip('/')}/users/{self.user_id}/project",
                    "type": "DirectoryOrCreate",
                },
            }
            workspace_mount = {"name": "workspace", "mountPath": "/workspace"}
        body = {
            "apiVersion": "v1",
            "kind": "Pod",
            "metadata": {
                "name": self.name,
                "namespace": self._namespace,
                "labels": dict(self._pod_labels()),
            },
            "spec": {
                # 沙箱不调用 K8s API:不挂 SA token(传空 → 用集群 default,但显式关掉自动注入)
                "serviceAccountName": config.AGENT_K8S_SANDBOX_SERVICE_ACCOUNT or None,
                "automountServiceAccountToken": False,
                "securityContext": {"fsGroup": self._user_uid},
                "restartPolicy": "Always",
                "volumes": [workspace_volume],
                "containers": [
                    {
                        "name": "sandbox",
                        "image": config.AGENT_SANDBOX_IMAGE,
                        "imagePullPolicy": config.AGENT_K8S_PULL_POLICY,
                        "command": ["/opt/aiworkbench/browser-entrypoint.sh"],
                        "env": [
                            {"name": "PATH", "value": "/usr/local/bin:/usr/bin:/bin"},
                            {"name": "HOME", "value": "/tmp"},
                            {"name": "TMPDIR", "value": "/tmp"},
                            {"name": "XDG_CACHE_HOME", "value": "/tmp/.cache"},
                        ],
                        "volumeMounts": [workspace_mount],
                        "workingDir": "/workspace",
                        "securityContext": {
                            "runAsUser": self._user_uid,
                            "runAsGroup": self._user_uid,
                            "runAsNonRoot": True,
                            "allowPrivilegeEscalation": False,
                            "capabilities": {"drop": ["ALL"]},
                        },
                        "resources": {
                            "requests": {
                                "cpu": config.AGENT_K8S_SANDBOX_CPU_REQUESTS,
                                "memory": config.AGENT_K8S_SANDBOX_MEM_REQUESTS,
                            },
                            "limits": {
                                "cpu": str(config.AGENT_SANDBOX_CPUS),
                                "memory": f"{config.AGENT_SANDBOX_MEM_MB}Mi",
                            },
                        },
                        "ports": self._container_port_defs(),
                    }
                ],
            },
        }

        # --- 调度策略 ---
        # 1. 拓扑分布:Pod 均匀散布到各节点(按 hostname)
        if config.AGENT_K8S_SCHEDULING_SPREAD:
            body["spec"]["topologySpreadConstraints"] = [
                {
                    "maxSkew": 1,
                    "topologyKey": "kubernetes.io/hostname",
                    "whenUnsatisfiable": "ScheduleAnyway",
                    "labelSelector": {"matchLabels": {"app": "ai-workbench"}},
                }
            ]

        # 2. 反亲和:同一用户的多个 Pod 不落同一节点
        if config.AGENT_K8S_SCHEDULING_AFFINITY:
            body["spec"]["affinity"] = {
                "podAntiAffinity": {
                    "preferredDuringSchedulingIgnoredDuringExecution": [
                        {
                            "weight": 100,
                            "podAffinityTerm": {
                                "labelSelector": {
                                    "matchExpressions": [
                                        {
                                            "key": "user_id",
                                            "operator": "In",
                                            "values": [self.user_id],
                                        }
                                    ]
                                },
                                "topologyKey": "kubernetes.io/hostname",
                            },
                        }
                    ]
                }
            }
        try:
            return core.create_namespaced_pod(namespace=self._namespace, body=body)
        except Exception as exc:  # noqa: BLE001  跨 worker 竞态:created by peer → 取回;真实失败原样抛出
            try:
                return core.read_namespaced_pod(name=self.name, namespace=self._namespace)
            except Exception:  # noqa: BLE001  pod 确实不存在(创建失败),保留真实创建异常
                raise exc from None

    def _ensure_pod(self):
        """确保沙箱 Pod 存在并 Running;返回 Pod 对象。"""
        core = self._clients()
        try:
            pod = core.read_namespaced_pod(name=self.name, namespace=self._namespace)
        except Exception:  # noqa: BLE001  NotFound → 创建
            pod = self._create_pod()
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                pod = core.read_namespaced_pod(name=self.name, namespace=self._namespace)
            except Exception:  # noqa: BLE001
                time.sleep(1)
                continue
            if _pod_running(pod):
                self.touch()
                return pod
            time.sleep(1)
        phase = getattr(pod.status, "phase", "?")
        raise RuntimeError(f"沙箱 Pod {self.name} 120s 内未就绪(phase={phase})")

    def ensure(self) -> Any:
        """确保沙箱 Pod + Service 已创建运行;供外部(sandbox_ensure 端点)调用。"""
        self._chown_workspace()
        self._ensure_service()
        pod = self._ensure_pod()
        # 探针:验证 subPath 挂载确实是该用户工作区(与 docker 版同语义)
        probe = ws.workspace_root(self.user_id) / ".sandbox-probe"
        probe.write_text(f"{self.name}:{time.time_ns()}", encoding="utf-8")
        out = self._run_shell("cat /workspace/.sandbox-probe", timeout=15)
        expected_prefix = f"{self.name}:"
        if out[0] != 0 or not out[1].strip().startswith(expected_prefix):
            raise RuntimeError(
                "沙箱工作区校验失败:Pod 内 /workspace 不是该用户的工作区。"
                "请检查挂载配置(pvc=AGENT_K8S_WORKSPACE_PVC / hostpath=AGENT_K8S_SANDBOX_VOLUME_HOST_PATH)"
                f"(当前模式: {config.AGENT_K8S_SANDBOX_VOLUME_TYPE})"
            )
        return pod

    def _chown_workspace(self) -> None:
        """沙箱以 uid 10001 运行,需要可读写工作区;agent-service 以 root 运行,
        把项目目录与其下文件 chown 到沙箱 uid(与 DockerSandbox._ensure_container 一致)。"""
        try:
            uid = self._user_uid
            root = ws.workspace_root(self.user_id)
            if not root.is_dir():
                return
            os.chown(root, uid, uid)
            for p in root.rglob("*"):
                try:
                    os.chown(p, uid, uid)
                except OSError:
                    pass
        except OSError as exc:
            logger.warning("[%s] chown 工作区失败(继续尝试): %s", self.user_id, exc)

    def ensure_browser(self) -> None:
        """浏览器栈由 Pod entrypoint 常驻,幂等;确保 Pod 已运行即可。"""
        self.ensure()
        self.touch()

    def stop(self) -> None:
        """停止沙箱:删除 Pod。K8s 无容器停止态,删后按需重建,等价释放 CPU/内存。"""
        with self._lock:
            try:
                self._clients().delete_namespaced_pod(name=self.name, namespace=self._namespace)
                logger.info("[%s] 沙箱 Pod 已删除", self.user_id)
            except Exception as exc:  # noqa: BLE001
                logger.debug("[%s] 删除沙箱 Pod 失败: %s", self.user_id, exc)

    def destroy(self) -> None:
        from app.sandbox import unregister_sandbox

        unregister_sandbox(self)
        self.stop()

    def running(self) -> bool:
        """沙箱 Pod 是否 Running(供网关 isReady 探测)。"""
        try:
            pod = self._clients().read_namespaced_pod(name=self.name, namespace=self._namespace)
            return _pod_running(pod)
        except Exception:  # noqa: BLE001
            return False

    # --- CDP 端点(浏览器工具/网关直连) ------------------------------------

    def cdp_endpoint(self) -> str:
        """返回 CDP 地址。

        NodePort 模式:compose 侧经 <node_addr>:<nodePort_cdp> 访问(节点已接入 compose 桥网)。
        非 NodePort:优先 Pod IP(绕开新版 Chromium 的 Host 头校验),退回 Service DNS。
        """
        if config.AGENT_K8S_SANDBOX_NODEPORT:
            svc = self._clients().read_namespaced_service(name=self.name, namespace=self._namespace)
            for p in svc.spec.ports:
                if p.name == "cdp" and p.node_port:
                    return f"http://{config.AGENT_K8S_SANDBOX_NODE_ADDR}:{p.node_port}"
            raise RuntimeError(f"沙箱 Service {self.name} 无 CDP nodePort")
        pod = self._ensure_pod()
        ip = getattr(pod.status, "pod_ip", None)
        if ip:
            return f"http://{ip}:9223"
        # 无 pod IP(headless Service 场景)退回 Service DNS
        return f"http://{self.name}:9223"

    def mcp_endpoint(self) -> str:
        """返回 playwright-mcp 地址(NodePort 模式被 compose 侧使用)。"""
        if config.AGENT_K8S_SANDBOX_NODEPORT:
            svc = self._clients().read_namespaced_service(name=self.name, namespace=self._namespace)
            for p in svc.spec.ports:
                if p.name == "mcp" and p.node_port:
                    return f"http://{config.AGENT_K8S_SANDBOX_NODE_ADDR}:{p.node_port}/mcp"
            raise RuntimeError(f"沙箱 Service {self.name} 无 MCP nodePort")
        return f"http://{self.name}:8931/mcp"

    # --- BaseSandbox 协议 -------------------------------------------------

    @property
    def id(self) -> str:  # type: ignore[override]
        return f"k8s:{self.name}"

    def _run_shell(self, command: str, timeout: int) -> tuple[int, str]:
        """沙箱内执行 shell 命令;返回 (exit_code, combined_output)。

        通过 shell 尾哨捕获真实退出码(kubernetes stream 不暴露 exit code),
        命令整体再包一层 timeout 保证命令级超时。
        """
        core = self._clients()
        pod = self._ensure_pod()
        # 命令原文经 sh -lc 执行;尾哨单独一行输出,避免与命令 stdout 混淆
        script = f"{{ {command}; }} 2>&1; __rc=$?; echo; echo {_EC_MARKER}$__rc"
        # 命令级超时:K8s exec 本身无超时,用容器内 timeout 命令包装(超时退出码 124/137)
        wrapped = ["timeout", "-k", "5", str(max(1, timeout)), "sh", "-lc", script]
        from kubernetes import stream as k8s_stream

        try:
            resp = k8s_stream.stream(
                core.connect_get_namespaced_pod_exec,
                pod.metadata.name,
                self._namespace,
                command=wrapped,
                container="sandbox",
                stderr=False,
                stdin=False,
                stdout=True,
                tty=False,
            )
            payload = resp if isinstance(resp, str) else b"".join(resp).decode("utf-8", "replace")
        except Exception as exc:  # noqa: BLE001
            return 1, f"exec 失败: {exc}"
        # 解析尾哨
        lines = payload.rstrip().split("\n")
        exit_code: int = 0
        out_lines: list[str] = []
        for line in lines:
            if line.startswith(_EC_MARKER):
                try:
                    exit_code = int(line[len(_EC_MARKER):].strip() or "0")
                except ValueError:
                    exit_code = 1
                continue
            out_lines.append(line)
        return exit_code, "\n".join(out_lines)

    def execute(
        self,
        command: str,
        *,
        timeout: int | None = None,
    ) -> ExecuteResponse:
        self.touch()
        effective = max(1, min(timeout or config.AGENT_SANDBOX_MAX_TIMEOUT, config.AGENT_SANDBOX_MAX_TIMEOUT))
        # 快照在本地卷做(os.walk),与沙箱看到的同一份数据
        before = self._file_snapshot_local()
        exit_code, text = self._run_shell(command, timeout=effective)
        if exit_code in (124, 137):
            text += f"\n[command timed out after {effective}s]"
        if len(text) > _MAX_OUTPUT_BYTES:
            text = text[:_MAX_OUTPUT_BYTES] + "\n[output truncated]"
        with self._lock:
            self._recent_changes = self._diff_snapshot_local(before)
        return ExecuteResponse(output=text, exit_code=exit_code, truncated=False)

    def pop_recent_changes(self) -> list[str]:
        with self._lock:
            changes = list(self._recent_changes)
            self._recent_changes = []
        return changes

    def _file_snapshot_local(self) -> dict[str, str]:
        snap: dict[str, str] = {}
        root = ws.workspace_root(self.user_id)
        if not root.is_dir():
            return snap
        for p in root.rglob("*"):
            if p.is_file():
                try:
                    st = p.stat()
                    snap[str(p.relative_to(root)).replace("\\", "/")] = f"{st.st_mtime} {st.st_size}"
                except OSError:
                    pass
        return snap

    def _diff_snapshot_local(self, before: dict[str, str]) -> list[str]:
        skip_prefixes = (".git/", ".opencode/", ".cache/")
        skip_exact = {".gitignore", ".sandbox-probe"}
        after = self._file_snapshot_local()
        changed: list[str] = []
        for rel in after:
            if rel.startswith(skip_prefixes) or rel in skip_exact:
                continue
            if before.get(rel) != after[rel]:
                changed.append(rel)
        return sorted(changed)

    async def aexecute(self, command: str, *, timeout: int | None = None) -> ExecuteResponse:
        import asyncio

        return await asyncio.to_thread(self.execute, command, timeout=timeout)

    def upload_files(self, files: list[tuple[str, bytes]]) -> list[FileUploadResponse]:
        """写文件直接落 agent-service 挂载的同一 workspace 卷(与沙箱 subPath 同源)。"""
        root = ws.workspace_root(self.user_id)
        self.touch()
        results: list[FileUploadResponse] = []
        for path, content in files:
            try:
                rel = _safe_rel(path)
                target = root.joinpath(*rel.parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
                self._chown_workspace()
                results.append(FileUploadResponse(path=path, error=None))
            except ValueError as exc:
                results.append(FileUploadResponse(path=path, error=str(exc)))
            except Exception as exc:  # noqa: BLE001
                results.append(FileUploadResponse(path=path, error=f"{type(exc).__name__}: {exc}"))
        return results

    def download_files(self, paths: list[str]) -> list[FileDownloadResponse]:
        """读文件直接从 agent-service 本地卷取(与沙箱 /workspace 同源)。"""
        root = ws.workspace_root(self.user_id)
        self.touch()
        results: list[FileDownloadResponse] = []
        for path in paths:
            try:
                rel = _safe_rel(path)
                target = root.joinpath(*rel.parts)
                if not target.is_file():
                    raise FileNotFoundError(rel)
                results.append(FileDownloadResponse(path=path, content=target.read_bytes()))
            except ValueError as exc:
                results.append(FileDownloadResponse(path=path, error=str(exc)))
            except FileNotFoundError:
                results.append(FileDownloadResponse(path=path, error="file_not_found"))
            except Exception as exc:  # noqa: BLE001
                results.append(FileDownloadResponse(path=path, error=f"{type(exc).__name__}: {exc}"))
        return results


def _pod_running(pod) -> bool:
    phase = getattr(pod.status, "phase", "")
    if phase != "Running":
        return False
    for c in pod.status.container_statuses or []:
        if c.ready:
            return True
    return False