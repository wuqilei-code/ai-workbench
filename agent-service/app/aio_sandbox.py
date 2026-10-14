"""AioSandbox: 基于 AIO Sandbox (agent-infra/sandbox) 的 per-user 沙箱。

每个用户一个 AIO 容器,通过容器内 HTTP API 执行 shell/文件/浏览器操作;
容器自带:Chromium + VNC 实时画面、Shell/Python/Node 运行时、VSCode(code-server)、
文件系统 —— 全部共享同一文件系统(挂在 /home/gem/workspace)。

与原有 DockerSandbox 的差异:
- 不再自建浏览器栈(playwright + CDP 转发 + playwright-mcp),改用 AIO 内置浏览器,
  agent 通过 CDP 驱动、前端通过 VNC iframe 实时查看与接管。
- shell 执行 / 文件操作全部走 AIO HTTP API(/v1/shell/exec、/v1/file/*)。
- 工作区挂载保持不变:宿主 <AGENT_SANDBOX_VOLUME_ROOT>/<uid>/project
  → 容器内 /home/gem/workspace,对 agent 暴露为虚拟路径 /workspace。

实现 deepagents BaseSandbox:execute/upload_files/download_files/id,
并重写 ls/read/edit/grep/glob 走原生 File API。
"""

from __future__ import annotations

import base64
import hashlib
import logging
import os
import threading
import time
from pathlib import Path, PurePosixPath
from typing import Any

import httpx
from deepagents.backends.protocol import (
    ExecuteResponse,
    FileDownloadResponse,
    FileUploadResponse,
)
from deepagents.backends.sandbox import BaseSandbox

from app import config
from app import metrics
from app import workspace as ws

logger = logging.getLogger(__name__)

_MAX_OUTPUT_BYTES = 100_000
_AIO_STARTUP_WAIT = 30          # 等待 AIO server 就绪的最长秒数
_AIO_REQUEST_TIMEOUT = 120      # 单次 API 调用超时
_AIO_INNER_WORKSPACE = "/home/gem/workspace"   # AIO 容器内工作区
_AIO_HTTP_PORT = 8080                          # AIO server 容器内端口

# 开源中文字体(Noto Sans SC,SIL OFL 1.1):注入沙箱以替代镜像自带的专有 SimHei。
_FONT_SRC = Path(__file__).resolve().parent.parent / "assets" / "fonts" / "NotoSansSC-Regular.ttf"
_FONT_DEST_DIR = "/usr/share/fonts/truetype/noto"
_FONT_FILENAME = "NotoSansSC-Regular.ttf"


def _safe_rel(path: str) -> PurePosixPath:
    """校验并归一化路径(剥离 /workspace 前缀,拒绝目录穿越)。"""
    p = PurePosixPath(path.replace("\\", "/"))
    if p.is_absolute():
        s = str(p)
        for prefix in ("/workspace/", "/workspace"):
            if s.startswith(prefix):
                s = s[len(prefix):]
                break
        s = s.lstrip("/")
        if not s:
            return PurePosixPath(".")
        p = PurePosixPath(s)
    if ".." in p.parts or str(p).startswith("~"):
        raise ValueError(f"非法路径: {path!r}")
    return p


class AioSandbox(BaseSandbox):
    """一个 user_id 对应一个 AIO 容器;容器按需创建/复用,空闲超时回收。"""

    def __init__(self, user_id: str) -> None:
        self.user_id = user_id
        self.name = f"aio-sandbox-u{user_id}"
        self._client = None
        self._container = None
        self._lock = threading.Lock()
        self._base_url: str | None = None       # http://host.docker.internal:<port>
        self.last_used = time.monotonic()
        self._recent_changes: list[str] = []
        # (path, offset, limit) -> 该区间上次读到的内容摘要。用于识别"模型在原地打转":
        # 同一区间被逐字节重复读取时,在结果末尾追加提示,让它改用 offset 继续而不是反复重读。
        self._read_seen: dict[tuple[str, int, int], tuple[str, int]] = {}
        register_aio_sandbox(self)

    # --- lifecycle -------------------------------------------------------

    def touch(self) -> None:
        self.last_used = time.monotonic()

    def _docker(self):
        if self._client is None:
            try:
                import docker
            except ImportError as exc:  # pragma: no cover
                raise RuntimeError("docker SDK 未安装,无法管理 AIO 沙箱容器") from exc
            self._client = docker.from_env()
        return self._client

    def _ensure_container(self):
        """确保 AIO 容器运行,返回 (container, base_url)。"""
        with self._lock:
            cli = self._docker()
            import docker

            # 已有容器:刷新运行状态与端口(容器重建后端口可能变化)
            try:
                c = cli.containers.get(self.name)
                if c.status != "running":
                    c.start()
                    c.reload()
                host_port = self._host_port(c)
                if host_port:
                    base_url = f"http://host.docker.internal:{host_port}"
                    self._container = c
                    self._base_url = base_url
                    self._chown_workspace(c)
                    self._provision_cjk_font(c)
                    metrics.inc("aiwb_sandbox_reused_total")
                    return c, base_url
            except docker.errors.NotFound:
                pass

            # 用户 project 目录(与前端文件树同址),bind 到 AIO 容器工作区
            self._enforce_active_cap()
            host_dir = str(Path(config.AGENT_SANDBOX_VOLUME_ROOT) / self.user_id / "project")
            self._chown_host_workspace()

            c = cli.containers.create(
                image=config.AGENT_SANDBOX_IMAGE,
                name=self.name,
                ports={f"{_AIO_HTTP_PORT}/tcp": None},   # 随机宿主端口
                volumes={host_dir: {"bind": _AIO_INNER_WORKSPACE, "mode": "rw"}},
                environment={"TZ": "Asia/Shanghai"},
                nano_cpus=int(config.AGENT_SANDBOX_CPUS * 1e9),
                mem_limit=f"{config.AGENT_SANDBOX_MEM_MB}m",
                pids_limit=config.AGENT_SANDBOX_PIDS,
                shm_size="2g",
                security_opt=["seccomp=unconfined"],
                labels={"aiwb.role": "aio-sandbox", "aiwb.user": self.user_id},
                working_dir=_AIO_INNER_WORKSPACE,
                restart_policy={"Name": "unless-stopped"},
            )
            # 加入与网关同网,使 agent-service 可直连容器 IP:8080
            try:
                net = cli.networks.get(config.AGENT_SANDBOX_NETWORK_NAME)
                net.connect(c)
            except Exception as exc:  # noqa: BLE001
                logger.warning("[%s] AIO 加入网络 %s 失败: %s",
                               self.user_id, config.AGENT_SANDBOX_NETWORK_NAME, exc)
            logger.info("[%s] AIO 容器已创建: %s", self.user_id, self.name)

            c.start()
            c.reload()
            host_port = self._host_port(c)
            if not host_port:
                raise RuntimeError(f"AIO 容器 {self.name}: 未映射宿主端口")
            base_url = f"http://host.docker.internal:{host_port}"

            self._wait_ready(base_url)
            self._chown_workspace(c)
            self._provision_cjk_font(c)

            self._container = c
            self._base_url = base_url
            metrics.inc("aiwb_sandbox_created_total")
            logger.info("[%s] AIO 沙箱就绪: %s (port=%s)", self.user_id, self.name, host_port)
            return c, base_url

    def _enforce_active_cap(self) -> None:
        """并发沙箱上限保护:仅限制"新建"(复用已有容器不受限),防止资源耗尽。

        计数排除自身(本实例已在 __init__ 注册),因此 max=1 时仍允许为单个用户建容器,
        第二个用户才会被拒。
        """
        cap = config.AGENT_SANDBOX_MAX_ACTIVE
        if not cap or cap <= 0:
            return
        others = active_sandbox_count(exclude=self)
        if others >= cap:
            metrics.inc("aiwb_sandbox_rejected_total")
            logger.warning("[%s] 沙箱并发已达上限 %d(当前 %d),拒绝新建",
                           self.user_id, cap, others)
            raise RuntimeError(f"沙箱并发已达上限({cap}),请稍后重试或等待空闲回收")

    @staticmethod
    def _host_port(c) -> str | None:
        ports = (c.attrs.get("NetworkSettings", {}) or {}).get("Ports", {}) or {}
        for pval in (ports.get(f"{_AIO_HTTP_PORT}/tcp") or []):
            if isinstance(pval, dict) and pval.get("HostPort"):
                return pval["HostPort"]
        return None

    def _chown_host_workspace(self) -> None:
        """把宿主 workspace 目录 chown 给 gem(uid 1000),保证 AIO File API 可读写。"""
        uid = config.AGENT_SANDBOX_UID
        try:
            root = ws.workspace_root(self.user_id)
            os.chown(root, uid, uid)
            for p in root.rglob("*"):
                try:
                    os.chown(p, uid, uid)
                except OSError:
                    pass
        except OSError as exc:
            logger.warning("[%s] chown 工作区失败(继续): %s", self.user_id, exc)

    def _chown_workspace(self, c) -> None:
        """容器内再次递归 chown(兜底,处理宿主无权限的场景)。"""
        uid = config.AGENT_SANDBOX_UID
        try:
            c.exec_run(["chown", "-R", f"{uid}:{uid}", _AIO_INNER_WORKSPACE],
                       user="root")
        except Exception as exc:  # noqa: BLE001
            logger.debug("[%s] 容器内 chown 失败: %s", self.user_id, exc)
        self._ensure_browser_running(c)

    def _ensure_browser_running(self, c) -> None:
        """确保 AIO 的 agent-browser(Chromium)在运行。

        supervisord 对 agent-browser 不自动重启,一旦(如 profile 不可写)崩溃退出就不会自己恢复;
        这里探测 CDP 端口,未在跑则重试拉起,保证 VNC/CDP 可用。
        """
        try:
            probe = c.exec_run(["pgrep", "-f", "remote-debugging-port=9222"], user="root")
            if probe.exit_code == 0:
                return
            c.exec_run(["supervisorctl", "restart", "agent-browser"], user="root")
            logger.info("[%s] agent-browser 未运行,已触发重启", self.user_id)
        except Exception as exc:  # noqa: BLE001
            logger.debug("[%s] 检查/重启浏览器失败: %s", self.user_id, exc)

    def _provision_cjk_font(self, c) -> None:
        """把开源中文字体(Noto Sans SC)注入沙箱,替代镜像自带的专有 SimHei。

        沙箱镜像自带 simhei.ttf(专有授权);这里以 root 通过 docker put_archive 写入
        开源字体,使 docx/pdf/pptx/xlsx 技能引用的 /usr/share/fonts/truetype/noto/ 路径可用。
        幂等:已存在则跳过;失败仅告警,不影响沙箱主流程。
        """
        if not _FONT_SRC.exists():
            return
        dest = f"{_FONT_DEST_DIR}/{_FONT_FILENAME}"
        try:
            if c.exec_run(["test", "-f", dest], user="root").exit_code == 0:
                return
            c.exec_run(["mkdir", "-p", _FONT_DEST_DIR], user="root")
            import io
            import tarfile

            data = _FONT_SRC.read_bytes()
            buf = io.BytesIO()
            with tarfile.open(fileobj=buf, mode="w") as tar:
                info = tarfile.TarInfo(_FONT_FILENAME)
                info.size = len(data)
                info.mode = 0o644
                tar.addfile(info, io.BytesIO(data))
            buf.seek(0)
            c.put_archive(_FONT_DEST_DIR, buf)
            c.exec_run(["fc-cache", "-f", _FONT_DEST_DIR], user="root")
            logger.info("[%s] 已注入开源中文字体: %s", self.user_id, dest)
        except Exception as exc:  # noqa: BLE001
            logger.warning("[%s] 注入中文字体失败(继续): %s", self.user_id, exc)

    def _wait_ready(self, base_url: str) -> None:
        deadline = time.monotonic() + _AIO_STARTUP_WAIT
        last = None
        while time.monotonic() < deadline:
            try:
                r = httpx.get(f"{base_url}/v1/sandbox", timeout=3)
                if r.status_code == 200:
                    logger.info("[%s] AIO server 就绪: %s", self.user_id, base_url)
                    return
            except Exception as exc:  # noqa: BLE001
                last = exc
            time.sleep(1)
        raise RuntimeError(f"AIO server {_AIO_STARTUP_WAIT}s 内未就绪({base_url}): {last}")

    def _internal_base_url(self) -> str:
        """AIO 容器在 aiwb-agents 网络上的直连 URL(容器 IP:8080)。"""
        c = self.ensure()
        c.reload()
        nets = (c.attrs.get("NetworkSettings", {}) or {}).get("Networks", {}) or {}
        for net_meta in nets.values():
            ip = (net_meta or {}).get("IPAddress")
            if ip:
                return f"http://{ip}:{_AIO_HTTP_PORT}"
        # fallback:host.docker.internal 映射端口
        _, base = self._ensure_container()
        return base

    def public_base_url(self) -> str:
        """供前端访问的 base(把 host.docker.internal 换成 localhost)。"""
        _, base = self._ensure_container()
        return base.replace("host.docker.internal", "localhost")

    def _api(self, method: str, path: str, **kwargs) -> dict:
        """调用 AIO HTTP API,返回 data 字段。"""
        self.touch()
        url = f"{self._internal_base_url()}{path}"
        kwargs.setdefault("timeout", _AIO_REQUEST_TIMEOUT)
        r = getattr(httpx, method)(url, **kwargs)
        r.raise_for_status()
        data = r.json()
        if not data.get("success"):
            raise RuntimeError(f"AIO API 错误({path}): {data.get('message', r.status_code)}")
        return data.get("data") or {}

    def ensure(self):
        try:
            return self._ensure_container()[0]
        except Exception:
            metrics.inc("aiwb_sandbox_errors_total")
            raise

    def ensure_browser(self) -> None:
        """AIO 自带浏览器,无需额外启动;确保容器运行即可。"""
        self._ensure_container()

    def stop(self) -> None:
        with self._lock:
            c, self._container, self._base_url = self._container, None, None
        try:
            if c is None:
                c = self._docker().containers.get(self.name)
            if c is not None:
                c.stop(timeout=3)
                metrics.inc("aiwb_sandbox_stopped_total")
                logger.info("[%s] AIO 沙箱已停止", self.user_id)
        except Exception as exc:  # noqa: BLE001
            logger.debug("[%s] 停止 AIO 失败: %s", self.user_id, exc)

    def destroy(self) -> None:
        unregister_aio_sandbox(self)
        self.stop()
        try:
            self._docker().containers.get(self.name).remove(force=True)
            metrics.inc("aiwb_sandbox_destroyed_total")
        except Exception:  # noqa: BLE001
            pass

    # --- BaseSandbox 协议 -------------------------------------------------

    @property
    def id(self) -> str:  # type: ignore[override]
        return f"aio:{self.name}"

    def execute(self, command: str, *, timeout: int | None = None) -> ExecuteResponse:
        self.touch()
        before = self._file_snapshot()
        # 虚拟路径 /workspace → AIO 容器内真实路径
        translated = (command
                      .replace("/workspace/", _AIO_INNER_WORKSPACE + "/")
                      .replace("/workspace", _AIO_INNER_WORKSPACE))
        # AIO shell API 不支持 workdir,用 cd 前缀设定工作目录
        wrapped = f"cd {_AIO_INNER_WORKSPACE} && {translated}"
        if timeout and timeout > 0:
            wrapped = f"timeout -k 5 {int(timeout)} sh -c {self._shq(wrapped)}"
        try:
            data = self._api("post", "/v1/shell/exec", json={"command": wrapped})
            output = data.get("output") or ""
            exit_code = data.get("exit_code", -1)
            truncated = len(output) > _MAX_OUTPUT_BYTES
            if truncated:
                output = output[:_MAX_OUTPUT_BYTES] + "\n[输出已截断]"
        except Exception as exc:  # noqa: BLE001
            output = f"AIO 执行错误: {exc}"
            exit_code = -1
            truncated = False
        try:
            changes = self._diff_snapshot(before, self._file_snapshot())
        except Exception:  # noqa: BLE001
            changes = []
        with self._lock:
            self._recent_changes = changes
        return ExecuteResponse(output=output, exit_code=exit_code, truncated=truncated)

    @staticmethod
    def _shq(s: str) -> str:
        return "'" + s.replace("'", "'\\''") + "'"

    async def aexecute(self, command: str, *, timeout: int | None = None) -> ExecuteResponse:
        import asyncio

        return await asyncio.to_thread(self.execute, command, timeout=timeout)

    def pop_recent_changes(self) -> list[str]:
        with self._lock:
            changes = list(self._recent_changes)
            self._recent_changes = []
        return changes

    def upload_files(self, files: list[tuple[str, bytes]]) -> list[FileUploadResponse]:
        self.touch()
        results: list[FileUploadResponse] = []
        for path, content in files:
            try:
                rel = _safe_rel(path)
                aio_path = f"{_AIO_INNER_WORKSPACE}/{rel}"
                b64 = base64.b64encode(content).decode("ascii")
                self._api("post", "/v1/file/write",
                          json={"file": aio_path, "content": b64, "encoding": "base64"})
                results.append(FileUploadResponse(path=path, error=None))
            except ValueError as exc:
                results.append(FileUploadResponse(path=path, error=str(exc)))
            except Exception as exc:  # noqa: BLE001
                results.append(FileUploadResponse(path=path, error=f"{type(exc).__name__}: {exc}"))
        return results

    def download_files(self, paths: list[str]) -> list[FileDownloadResponse]:
        self.touch()
        results: list[FileDownloadResponse] = []
        base = self._internal_base_url()
        for path in paths:
            try:
                rel = _safe_rel(path)
                aio_path = f"{_AIO_INNER_WORKSPACE}/{rel}"
                r = httpx.get(f"{base}/v1/file/download",
                              params={"path": aio_path},
                              timeout=_AIO_REQUEST_TIMEOUT)
                if r.status_code == 200:
                    results.append(FileDownloadResponse(path=path, content=r.content))
                else:
                    results.append(FileDownloadResponse(path=path, error="file_not_found"))
            except ValueError as exc:
                results.append(FileDownloadResponse(path=path, error=str(exc)))
            except Exception as exc:  # noqa: BLE001
                results.append(FileDownloadResponse(path=path, error=f"{type(exc).__name__}: {exc}"))
        return results

    # --- 文件操作(原生 AIO File API) ------------------------------------

    def _translate_virtual_path(self, path: str) -> str:
        if path.startswith("/workspace/"):
            return _AIO_INNER_WORKSPACE + "/" + path[len("/workspace/"):]
        if path == "/workspace":
            return _AIO_INNER_WORKSPACE
        return path

    def _to_virtual(self, aio_path: str) -> str:
        return aio_path.replace(_AIO_INNER_WORKSPACE + "/", "/workspace/").replace(
            _AIO_INNER_WORKSPACE, "/workspace")

    def _aio_file_api(self, endpoint: str, **kwargs) -> dict:
        self.touch()
        url = f"{self._internal_base_url()}/v1/file/{endpoint}"
        kwargs.setdefault("timeout", _AIO_REQUEST_TIMEOUT)
        r = httpx.post(url, **kwargs)
        r.raise_for_status()
        data = r.json()
        if not data.get("success"):
            err = (data.get("data") or {}).get("error_type", "unknown")
            raise RuntimeError(f"AIO File API 错误({endpoint}): {err} - {data.get('message', '')}")
        return data.get("data") or {}

    def ls(self, path: str):
        from deepagents.backends.protocol import FileInfo, LsResult

        aio_path = self._translate_virtual_path(path)
        try:
            data = self._aio_file_api(
                "list",
                json={"path": aio_path, "recursive": False,
                      "show_hidden": True, "include_size": True},
            )
            entries = [
                FileInfo(path=self._to_virtual(item.get("path", "")),
                         is_dir=item.get("is_directory", False),
                         size=item.get("size"))
                for item in (data.get("files") or [])
            ]
            return LsResult(entries=entries)
        except Exception as exc:  # noqa: BLE001
            return LsResult(error=str(exc))

    def read(self, file_path: str, offset: int = 0, limit: int = 2000):
        from deepagents.backends.protocol import FileData, ReadResult

        aio_path = self._translate_virtual_path(file_path)
        try:
            data = self._aio_file_api("read", json={"file": aio_path})
            content = data.get("content", "") or ""
            lines = content.split("\n")
            total = data.get("line_count") or len(lines)
            if total == 0 and content:
                total = 1
            sliced = "\n".join(lines[offset:offset + limit])
            actual_start = max(1, offset + 1) if total > 0 else 1
            actual_end = max(actual_start, min(offset + limit, total)) if total > 0 else 1
            next_offset = min(offset + limit, total) if offset + limit < total else None

            # 原地打转保护:同一 (path, offset, limit) 区间被重复读取且内容逐字节相同
            # —— 实测模型会对同一个技能文件重复发几十次完全相同的 read_file
            # (args 不变、从不改 offset),因为它以为"再读一次就能拿到更多"。
            # 这里把提示追加到内容末尾,明确告诉它"内容没变 + 想看后面请用 offset",
            # 让它跳出循环。内容本身照常返回(只是多一行提示),不影响正常读取。
            key = (aio_path, offset, limit)
            digest = hashlib.sha256(sliced.encode("utf-8", "replace")).hexdigest()[:16]
            prev = self._read_seen.get(key)
            repeat = prev is not None and prev[0] == digest
            self._read_seen[key] = (digest, self._read_seen.get(key, (digest, 0))[1] + (1 if repeat else 0))
            # 只保留最近 200 条,避免长会话无限增长
            if len(self._read_seen) > 200:
                for k in list(self._read_seen.keys())[:100]:
                    self._read_seen.pop(k, None)

            text = sliced
            if repeat:
                times = self._read_seen[key][1]
                hint = (
                    f"\n\n[提示] 该区间(第 {actual_start}-{actual_end} 行)已重复读取 {times} 次,"
                    "内容与上次完全相同,重复读取不会得到新信息。"
                )
                if next_offset is not None:
                    hint += f"文件共 {total} 行,如需后续内容请用 offset={next_offset} 继续读取。"
                else:
                    hint += "该文件已读到末尾。"
                text += hint

            return ReadResult(
                file_data=FileData(content=text, encoding="utf-8"),
                total_lines=total,
                start_line=actual_start,
                end_line=actual_end,
                next_offset=next_offset,
            )
        except Exception as exc:  # noqa: BLE001
            return ReadResult(error=str(exc))

    def edit(self, file_path: str, old_string: str, new_string: str, replace_all: bool = False):
        from deepagents.backends.protocol import EditResult

        aio_path = self._translate_virtual_path(file_path)
        try:
            if replace_all:
                data = self._aio_file_api("read", json={"file": aio_path})
                content = data.get("content", "") or ""
                count = content.count(old_string)
                self._aio_file_api("write",
                                   json={"file": aio_path,
                                         "content": content.replace(old_string, new_string)})
                return EditResult(path=file_path, occurrences=count)
            self._aio_file_api("replace",
                               json={"file": aio_path, "old_str": old_string, "new_str": new_string})
            return EditResult(path=file_path, occurrences=1)
        except Exception as exc:  # noqa: BLE001
            return EditResult(error=str(exc))

    def write(self, file_path: str, content: str):
        """覆盖 BaseSandbox.write:其 _write_preflight 用 base64 编码路径经 shell 执行,
        无法被 execute 的虚拟路径替换翻译;这里改用原生 File API 并自行建父目录。"""
        from deepagents.backends.protocol import WriteResult

        try:
            rel = _safe_rel(file_path)
            parent = str(rel.parent)
            if parent not in (".", ""):
                self.execute(f"mkdir -p {_AIO_INNER_WORKSPACE}/{parent}")
            responses = self.upload_files([(file_path, content.encode("utf-8"))])
            if responses and responses[0].error:
                return WriteResult(error=f"写入失败: {responses[0].error}")
            return WriteResult(path=file_path)
        except Exception as exc:  # noqa: BLE001
            return WriteResult(error=str(exc))

    def grep(self, pattern: str, path: str | None = None, glob: str | None = None,
             *, max_count: int | None = None):
        from deepagents.backends.protocol import GrepMatch, GrepResult

        search_path = self._translate_virtual_path(path or "/workspace/")
        try:
            payload: dict = {"path": search_path, "pattern": pattern, "case_insensitive": False}
            if glob:
                payload["include"] = [glob]
            if max_count:
                payload["max_results"] = max_count
            data = self._aio_file_api("grep", json=payload)
            matches = [
                GrepMatch(path=self._to_virtual(m.get("path", "")),
                          line=m.get("line", 0),
                          text=m.get("text", ""))
                for m in (data.get("matches") or [])
            ]
            return GrepResult(matches=matches)
        except Exception as exc:  # noqa: BLE001
            return GrepResult(error=str(exc))

    def glob(self, pattern: str, path: str | None = None):
        from deepagents.backends.protocol import FileInfo, GlobResult

        search_path = self._translate_virtual_path(path or "/workspace/")
        try:
            data = self._aio_file_api("glob",
                                      json={"path": search_path, "pattern": pattern,
                                            "files_only": False})
            matches = [
                FileInfo(path=self._to_virtual(item.get("path", "")),
                         is_dir=item.get("is_directory", False),
                         size=item.get("size"))
                for item in (data.get("matches") or [])
            ]
            return GlobResult(matches=matches)
        except Exception as exc:  # noqa: BLE001
            return GlobResult(error=str(exc))

    # --- 浏览器扩展(AIO 内置浏览器) -------------------------------------

    def browser_info(self) -> dict:
        """浏览器连接信息(CDP URL / VNC URL / viewport)。"""
        try:
            return self._api("get", "/v1/browser/info")
        except Exception as exc:  # noqa: BLE001
            return {"error": str(exc)}

    def browser_screenshot(self) -> bytes | None:
        try:
            base = self._internal_base_url()
            r = httpx.get(f"{base}/v1/browser/screenshot", timeout=30)
            if r.status_code == 200 and r.headers.get("content-type", "").startswith("image/"):
                return r.content
        except Exception as exc:  # noqa: BLE001
            logger.warning("[%s] 浏览器截图失败: %s", self.user_id, exc)
        return None

    def cdp_url(self) -> str | None:
        """浏览器当前页面的 CDP WebSocket 地址(供 agent 工具驱动)。"""
        try:
            base = self._internal_base_url()
            r = httpx.get(f"{base}/cdp/json/list", timeout=10)
            r.raise_for_status()
            pages = r.json() or []
            for p in pages:
                if p.get("type") == "page":
                    ws_url = p.get("webSocketDebuggerUrl")
                    if ws_url:
                        return ws_url
            if pages:
                return pages[0].get("webSocketDebuggerUrl")
        except Exception as exc:  # noqa: BLE001
            logger.warning("[%s] 获取 CDP 页面地址失败: %s", self.user_id, exc)
        return None

    # --- 录屏 / 预览 ------------------------------------------------------

    def display_record(self, action: str, save_path: str | None = None) -> dict:
        payload: dict[str, Any] = {"action": action}
        if save_path:
            payload["save_path"] = self._translate_virtual_path(save_path)
        try:
            return self._api("post", "/v1/display/record", json=payload)
        except Exception as exc:  # noqa: BLE001
            return {"error": str(exc)}

    def get_preview_url(self, port: int) -> str:
        return f"{self.public_base_url()}/absproxy/{port}/"

    # --- file snapshot(与 DockerSandbox 同逻辑) ------------------------

    def _file_snapshot(self) -> dict[str, str]:
        try:
            data = self._api(
                "post", "/v1/shell/exec",
                json={"command":
                      f"find {_AIO_INNER_WORKSPACE} -type f -printf '%P %T@ %s\\0' 2>/dev/null"},
            )
            raw = data.get("output") or ""
            snap: dict[str, str] = {}
            for rec in raw.split("\x00"):
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
        skip_prefixes = (".git/", ".opencode/", ".cache/", ".playwright-mcp/", ".pytest_cache/")
        skip_exact = {".gitignore", ".sandbox-probe"}
        changed: list[str] = []
        for rel in after:
            if rel.startswith(skip_prefixes) or rel in skip_exact:
                continue
            if before.get(rel) != after[rel]:
                changed.append(rel)
        return sorted(changed)


# --- 注册表与空闲回收(与 DockerSandbox 同逻辑) -------------------------

_ACTIVE_AIO: dict[str, AioSandbox] = {}
_ACTIVE_AIO_LOCK = threading.Lock()
_REAPER_STARTED = False


def register_aio_sandbox(sb: AioSandbox) -> None:
    global _REAPER_STARTED
    with _ACTIVE_AIO_LOCK:
        _ACTIVE_AIO[sb.name] = sb
        metrics.set_gauge("aiwb_sandbox_active", len(_ACTIVE_AIO))
        if not _REAPER_STARTED:
            t = threading.Thread(target=_aio_reap_loop, name="aio-sandbox-reaper", daemon=True)
            t.start()
            _REAPER_STARTED = True


def unregister_aio_sandbox(sb: AioSandbox) -> None:
    with _ACTIVE_AIO_LOCK:
        _ACTIVE_AIO.pop(sb.name, None)
        metrics.set_gauge("aiwb_sandbox_active", len(_ACTIVE_AIO))


def active_sandbox_count(exclude: "AioSandbox | None" = None) -> int:
    """当前注册的沙箱数(可排除某个实例)。"""
    with _ACTIVE_AIO_LOCK:
        return sum(1 for sb in _ACTIVE_AIO.values() if sb is not exclude)


def get_aio_sandbox(user_id: str) -> AioSandbox:
    with _ACTIVE_AIO_LOCK:
        existing = _ACTIVE_AIO.get(f"aio-sandbox-u{user_id}")
    if existing is not None:
        return existing
    return AioSandbox(user_id)


def _aio_reap_loop() -> None:
    while True:
        time.sleep(60)
        cutoff = time.monotonic() - max(60, config.AGENT_SANDBOX_IDLE_TTL)
        with _ACTIVE_AIO_LOCK:
            stale = [sb for sb in _ACTIVE_AIO.values() if sb.last_used < cutoff]
        for sb in stale:
            try:
                logger.info("[%s] AIO 沙箱空闲回收(>%ss): %s",
                            sb.user_id, max(60, config.AGENT_SANDBOX_IDLE_TTL), sb.name)
                sb.destroy()
                metrics.inc("aiwb_sandbox_reaped_total")
            except Exception:  # noqa: BLE001
                logger.warning("[%s] AIO 沙箱回收失败", sb.user_id, exc_info=True)
