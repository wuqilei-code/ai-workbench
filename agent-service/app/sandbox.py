"""DockerSandbox:per-user 容器沙箱,execute 与 /workspace 文件工具全部在容器内执行。

隔离边界:
- 每个用户一个常驻沙箱容器(aiwb-sandbox-u<uid>),只 bind 挂载该用户的 project 目录
  到容器内 /workspace —— shell 里用绝对路径也看不到其他用户目录与 agent-service 自身。
- 默认禁网(AGENT_SANDBOX_NETWORK=true 可开)、cap_drop ALL、no-new-privileges、
  非 root uid 运行、CPU/内存/pids 限额、单命令超时 clamp。
- 容器环境变量只有 PATH,不含 PG DSN / 模型 key 等任何机密。

实现 deepagents BaseSandbox:execute/upload_files/download_files/id。
文件工具(ls/grep/glob/read/edit)由基类自动转为沙箱内 shell 命令;write 走 upload_files。
"""

from __future__ import annotations

import io
import logging
import os
import tarfile
import threading
import time
from pathlib import Path, PurePosixPath

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


def _safe_rel(path: str) -> PurePosixPath:
    """校验并归一化后端收到的相对路径(拒绝绝对路径与目录穿越)。
    兼容 agent 传入 /workspace/xxx 绝对路径——自动剥离前缀后按相对路径处理。"""
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


class DockerSandbox(BaseSandbox):
    """一个 user_id 对应一个实例;容器按需创建/复用,空闲超时自动停止。"""

    def __init__(self, user_id: str) -> None:
        self.user_id = user_id
        self.name = f"aiwb-sandbox-u{user_id}"
        self._client = None
        self._container = None
        self._lock = threading.Lock()
        self.last_used = time.monotonic()
        self._marker_verified = False
        self._recent_changes: list[str] = []
        register_sandbox(self)

    # --- lifecycle -------------------------------------------------------

    def touch(self) -> None:
        self.last_used = time.monotonic()

    def _docker(self):
        if self._client is None:
            try:
                import docker
            except ImportError as exc:  # pragma: no cover
                raise RuntimeError(
                    "docker SDK 未安装;请重建镜像或在 .env 设 AGENT_SANDBOX_BACKEND=local"
                ) from exc
            self._client = docker.from_env()
        return self._client

    def _ensure_container(self):
        with self._lock:
            cli = self._docker()
            import docker

            host_dir = str(Path(config.AGENT_SANDBOX_VOLUME_ROOT) / self.user_id / "project")

            # chown 工作区到沙箱 uid(root 权限来自 agent-service 自身容器)
            try:
                uid = config.AGENT_SANDBOX_UID
                root = ws.workspace_root(self.user_id)
                os.chown(root, uid, uid)
                for p in root.rglob("*"):
                    try:
                        os.chown(p, uid, uid)
                    except OSError:
                        pass
            except OSError as exc:
                logger.warning("[%s] chown 工作区失败(继续尝试): %s", self.user_id, exc)

            # 探针:用共享卷写 marker,再从沙箱内读,验证 bind 路径配置正确
            probe = ws.workspace_root(self.user_id) / ".sandbox-probe"
            probe.write_text(f"{self.name}:{time.time_ns()}", encoding="utf-8")

            try:
                c = cli.containers.get(self.name)
            except Exception:
                mounts = [
                    docker.types.Mount(
                        target="/workspace",
                        source=host_dir,
                        type="bind",
                    )
                ]
                try:
                    c = cli.containers.create(
                        image=config.AGENT_SANDBOX_IMAGE,
                        name=self.name,
                        entrypoint=["/opt/aiworkbench/browser-entrypoint.sh"],
                        mounts=mounts,
                        user=str(config.AGENT_SANDBOX_UID),
                        # HOME/XDG_CACHE_HOME 必须指向非挂载的临时目录:
                        # 若指向 /workspace,fontconfig(chromium 出 PDF、pango 转档等)
                        # 会把字体缓存写进用户项目(.cache/fontconfig/*-le64.cache-8),
                        # 污染工作区文件树与 git。
                        environment={
                            "PATH": "/usr/local/bin:/usr/bin:/bin",
                            "HOME": "/tmp",
                            "TMPDIR": "/tmp",
                            "XDG_CACHE_HOME": "/tmp/.cache",
                        },
                        network_disabled=not config.AGENT_SANDBOX_NETWORK,
                        nano_cpus=int(config.AGENT_SANDBOX_CPUS * 1e9),
                        mem_limit=f"{config.AGENT_SANDBOX_MEM_MB}m",
                        pids_limit=config.AGENT_SANDBOX_PIDS,
                        cap_drop=["ALL"],
                        security_opt=["no-new-privileges:true"],
                        labels={"aiwb.role": "agent-sandbox", "aiwb.user": self.user_id},
                        working_dir="/workspace",
                        tty=False,
                        stdin_open=False,
                    )
                except docker.errors.APIError:
                    # 跨 worker 竞态:另一进程刚以同名创建成功,改为取回现成的
                    c = cli.containers.get(self.name)
                # 并入自定义网络供网关节流面板直连(docker SDK 部分版本不支持 create(networks=),
                # 采用 create 后 connect 的方式)
                if config.AGENT_SANDBOX_NETWORK:
                    try:
                        cli.networks.get(config.AGENT_SANDBOX_NETWORK_NAME).connect(c)
                    except Exception as exc:  # noqa: BLE001
                        logger.warning("[%s] 沙箱加入网络 %s 失败: %s",
                                       self.user_id, config.AGENT_SANDBOX_NETWORK_NAME, exc)
                logger.info("[%s] 沙箱容器已创建: %s", self.user_id, self.name)

            if c.status != "running":
                c.start()

            res = c.exec_run(["cat", "/workspace/.sandbox-probe"])
            out = (res.output or b"").decode("utf-8", "replace").strip() if not isinstance(res.output, tuple) else ""
            expected_prefix = f"{self.name}:"
            if res.exit_code != 0 or not out.startswith(expected_prefix):
                c.remove(force=True)
                raise RuntimeError(
                    "沙箱 bind 路径校验失败:容器内 /workspace 不是该用户的工作区。"
                    "请检查 AGENT_SANDBOX_VOLUME_ROOT 是否为 daemon 视角的卷数据根"
                    f"(当前: {config.AGENT_SANDBOX_VOLUME_ROOT})"
                )
            self._probe_ok = True
            self._container = c
            return c

    def ensure(self) -> Any:
        """确保沙箱容器已创建并运行(execute + 浏览器 daemon);供外部按需调用。"""

        return self._ensure_container()

    def ensure_browser(self) -> None:
        """按需拉起共享浏览器栈(chromium + CDP 转发 + playwright-mcp),幂等。

        已就绪时脚本秒回;未就绪时启动并等待(典型 3~8s)。失败抛错给调用方。
        """
        c = self._ensure_container()
        self.touch()
        res = c.exec_run(["sh", "-lc", "/opt/aiworkbench/ensure-browser.sh"])
        code = res.exit_code if isinstance(res.exit_code, int) else None
        if code != 0:
            out = res.output if isinstance(res.output, bytes) else b"".join(res.output or ())
            raise RuntimeError(
                f"浏览器启动失败(exit={code}): {out.decode('utf-8', 'replace')[-500:]}"
            )

    def stop(self) -> None:
        with self._lock:
            c, self._container = self._container, None
        try:
            if c is None:
                # agent-service 重启后注册表为空:按名字兜底,保证「关闭浏览器」总能生效
                c = self._docker().containers.get(self.name)
            if c is not None:
                c.stop(timeout=3)
                logger.info("[%s] 沙箱已停止", self.user_id)
        except Exception as exc:  # noqa: BLE001
            logger.debug("[%s] 停止沙箱失败: %s", self.user_id, exc)

    def destroy(self) -> None:
        unregister_sandbox(self)
        self.stop()
        try:
            self._docker().containers.get(self.name).remove(force=True)
        except Exception:
            pass

    # --- BaseSandbox 协议 -------------------------------------------------

    @property
    def id(self) -> str:  # type: ignore[override]
        return f"docker:{self.name}"

    def execute(
        self,
        command: str,
        *,
        timeout: int | None = None,
    ) -> ExecuteResponse:
        c = self._ensure_container()
        self.touch()
        effective = min(timeout or config.AGENT_SANDBOX_MAX_TIMEOUT, config.AGENT_SANDBOX_MAX_TIMEOUT)
        effective = max(1, effective)
        # 执行前快照项目文件(shell 写文件时也能拿到"输出物"路径)
        before = self._file_snapshot(c)
        wrapped = ["timeout", "-k", "5", str(effective), "sh", "-lc", command]
        res = c.exec_run(wrapped, workdir="/workspace")
        output_b = res.output if isinstance(res.output, bytes) else b"".join(res.output or ())
        truncated = False
        if len(output_b) > _MAX_OUTPUT_BYTES:
            output_b = output_b[:_MAX_OUTPUT_BYTES]
            truncated = True
        text = output_b.decode("utf-8", "replace")
        if truncated:
            text += "\n[output truncated]"
        exit_code = res.exit_code if isinstance(res.exit_code, int) else None
        if exit_code in (124, 137):
            text += f"\n[command timed out after {effective}s]"
        # 执行后 diff:新增/变更文件 = 本次 execute 的输出物(相对 /workspace,排除系统/隐藏文件)
        try:
            changes = self._diff_snapshot(before, self._file_snapshot(c))
        except Exception:  # noqa: BLE001
            changes = []
        with self._lock:
            self._recent_changes = changes
        return ExecuteResponse(output=text, exit_code=exit_code, truncated=truncated)

    def pop_recent_changes(self) -> list[str]:
        """取走最近一次 execute 新增/变更的文件(相对 /workspace),供事件层作为结构化输出物;
        取走后清空,避免重复上报。加锁防止与并发 execute 写互相覆盖。"""
        with self._lock:
            changes = list(self._recent_changes)
            self._recent_changes = []
        return changes

    def _file_snapshot(self, c) -> dict[str, str]:
        """容器内 /workspace 的文件快照:相对路径 -> "mtime size"(每记录以 \0 结尾,便于解析);
        失败返回空。"""
        try:
            _, out = c.exec_run(
                ["sh", "-lc",
                 "find /workspace -type f -printf '%P %T@ %s\\0' 2>/dev/null"],
                workdir="/workspace",
            )
            raw = out if isinstance(out, bytes) else b"".join(out or ())
            snap: dict[str, str] = {}
            for rec in raw.decode("utf-8", "replace").split("\x00"):
                rec = rec.strip()
                if not rec:
                    continue
                parts = rec.rsplit(" ", 2)
                if len(parts) == 3:
                    snap[parts[0]] = f"{parts[1]} {parts[2]}"
            return snap
        except Exception:  # noqa: BLE001
            return {}

    def _diff_snapshot(self, before: dict[str, str], after: dict[str, str]) -> list[str]:
        skip_prefixes = (".git/", ".opencode/", ".cache/")
        skip_exact = {".gitignore", ".sandbox-probe"}
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
        c = self._ensure_container()
        self.touch()
        results: list[FileUploadResponse] = []
        for path, content in files:
            try:
                rel = _safe_rel(path)
                parent = str(rel.parent) if str(rel.parent) not in (".", "") else "."
                c.exec_run(["mkdir", "-p", parent], workdir="/workspace")
                buf = io.BytesIO()
                with tarfile.open(fileobj=buf, mode="w") as tf:
                    data = tarfile.TarInfo(name=rel.name)
                    data.size = len(content)
                    data.mtime = int(time.time())
                    tf.addfile(data, io.BytesIO(content))
                ok = c.put_archive(path=f"/workspace/{parent}", data=buf.getvalue())
                results.append(FileUploadResponse(path=path, error=None if ok else "put_archive failed"))
            except ValueError as exc:
                results.append(FileUploadResponse(path=path, error=str(exc)))
            except Exception as exc:  # noqa: BLE001
                results.append(FileUploadResponse(path=path, error=f"{type(exc).__name__}: {exc}"))
        return results

    def download_files(self, paths: list[str]) -> list[FileDownloadResponse]:
        c = self._ensure_container()
        self.touch()
        results: list[FileDownloadResponse] = []
        for path in paths:
            try:
                rel = _safe_rel(path)
                bits, stat = c.get_archive(f"/workspace/{rel}")
                data = b"".join(bits)
                content: bytes | None = None
                with tarfile.open(fileobj=io.BytesIO(data), mode="r") as tf:
                    for member in tf.getmembers():
                        if member.isfile():
                            fh = tf.extractfile(member)
                            if fh is not None:
                                content = fh.read()
                                break
                if content is None:
                    results.append(FileDownloadResponse(path=path, error="file_not_found"))
                else:
                    results.append(FileDownloadResponse(path=path, content=content))
            except ValueError as exc:
                results.append(FileDownloadResponse(path=path, error=str(exc)))
            except Exception:  # noqa: BLE001
                results.append(FileDownloadResponse(path=path, error="file_not_found"))
        return results


# --- 注册表与空闲回收 -----------------------------------------------------

# docker/k8s 两种沙箱共用该注册表;K8sSandbox 在 sandbox_k8s.py 按需 import。
try:
    from app.sandbox_k8s import K8sSandbox  # noqa: F401  启动即暴露类型(供 type hint/工厂)
except Exception:  # noqa: BLE001  kubernetes 未装时(docker-only 镜像)不强求
    K8sSandbox = type("K8sSandbox", (), {})  # type: ignore[assignment,misc]

_ACTIVE: dict[str, DockerSandbox | K8sSandbox] = {}
_ACTIVE_LOCK = threading.Lock()
_REAPER_STARTED = False


def register_sandbox(sb: DockerSandbox | K8sSandbox) -> None:
    global _REAPER_STARTED
    with _ACTIVE_LOCK:
        _ACTIVE[sb.name] = sb
        if not _REAPER_STARTED:
            t = threading.Thread(target=_reap_loop, name="sandbox-reaper", daemon=True)
            t.start()
            _REAPER_STARTED = True


def unregister_sandbox(sb: DockerSandbox | K8sSandbox) -> None:
    with _ACTIVE_LOCK:
        _ACTIVE.pop(sb.name, None)


def get_docker_sandbox(user_id: str) -> DockerSandbox:
    with _ACTIVE_LOCK:
        existing = _ACTIVE.get(f"aiwb-sandbox-u{user_id}")
    if existing is not None:
        return existing
    return DockerSandbox(user_id)


def get_sandbox(user_id: str) -> DockerSandbox | K8sSandbox:
    """按 AGENT_SANDBOX_BACKEND 返回对应沙箱实现(docker|k8s)。

    - docker:每用户 Docker 容器(DockerSandbox)
    - k8s:每用户 K8s Pod + headless Service(K8sSandbox),不再依赖 docker.sock
    注册表与空闲回收两者共用(_reap_loop 按 last_used/destroy 统一回收)。
    """
    mode = config.AGENT_SANDBOX_BACKEND
    if mode == "docker":
        return get_docker_sandbox(user_id)
    if mode == "k8s":
        from app.sandbox_k8s import K8sSandbox

        target = K8sSandbox._pod_name(user_id)
        with _ACTIVE_LOCK:
            existing = _ACTIVE.get(target)
        if existing is not None:
            return existing
        return K8sSandbox(user_id)
    raise ValueError(f"非法 AGENT_SANDBOX_BACKEND: {mode!r}(可选 docker|k8s|local)")


def _reap_loop() -> None:
    while True:
        time.sleep(60)
        cutoff = time.monotonic() - max(60, config.AGENT_SANDBOX_IDLE_TTL)
        with _ACTIVE_LOCK:
            stale = [sb for sb in _ACTIVE.values() if sb.last_used < cutoff]
        for sb in stale:
            # 回收:注销注册表 + 停容器 + 移除容器,释放 DockerClient 与内存/句柄,
            # 避免注册表只增不减。后续 ensure / 浏览器工具会按需重建。
            try:
                sb.destroy()
            except Exception:  # noqa: BLE001
                logger.warning("[%s] 沙箱回收失败", sb.user_id, exc_info=True)
