"""创建/缓存基于 deepagents 的每用户 agent。

核心:一个进程内多 agent 实例,靠 LangGraph thread_id 区分并发会话;
每个 user_id 拥有独立 /workspace + 只读 /skills + 用户记忆。
"""

from __future__ import annotations

import hashlib
import logging
import threading
from pathlib import Path

from app import mcp_client

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Path-normalising wrapper
# ---------------------------------------------------------------------------
# deepagents CompositeBackend 路由规则:
#   /workspace/xxx → sandbox,  /skills/xxx → skills,  其余 → default(sandbox)
# 当 LLM 传 "/" 或不含已知前缀的裸路径时,default 后端会把 "/" 当沙箱根目录,
# 导致 ls 返回系统目录、read/write 找不到文件。此 wrapper 在所有文件操作入口
# 强制规范化路径,确保 "/" → "/workspace/",裸路径补 "/workspace/" 前缀。
# ---------------------------------------------------------------------------

_KNOWN_PREFIXES = ("/workspace/", "/skills/", "/skills")


def _normalize_fs_path(path: str) -> str:
    """把 LLM 传入的路径规范化为 CompositeBackend 能正确路由的绝对路径。"""
    if not path:
        return "/workspace/"
    # "/" 或 "/workspace" (无尾斜杠) → 工作区根
    if path == "/" or path == "/workspace":
        return "/workspace/"
    # "/skills" (无尾斜杠) → 技能根
    if path == "/skills":
        return "/skills/"
    # 已有已知前缀,原样返回
    for pfx in _KNOWN_PREFIXES:
        if path.startswith(pfx):
            return path
    # 裸绝对路径如 "/test.txt" → 补 /workspace/ 前缀
    if path.startswith("/"):
        return "/workspace" + path
    # 相对路径 → 补 /workspace/ 前缀
    return "/workspace/" + path


def _assert_writable(path: str) -> None:
    """平台模板目录 /workspace/tpl 只读:写入/编辑/删除一律拒绝。

    原用 deepagents 的 filesystem permissions 实现,但其与可执行后端(SandboxBackendProtocol)
    不兼容(构建 agent 即抛 NotImplementedError),故改为在后端写入路径上硬拦截。"""
    norm = _normalize_fs_path(path)
    if norm == "/workspace/tpl" or norm.startswith("/workspace/tpl/"):
        raise PermissionError("/workspace/tpl 为平台模板只读目录,不可写入或删除")


class _PathNormalizingProxy:
    """轻量代理:把 CompositeBackend 的路径操作统一规范化,其余方法直通。"""

    def __init__(self, inner: CompositeBackend) -> None:
        # 使用 object.__setattr__ 避免触发 __setattr__ 递归
        object.__setattr__(self, "_inner", inner)

    # 让 isinstance(proxy, CompositeBackend) 返回 True,
    # 否则 deepagents is_backend_sandbox_capable() 检查失败,
    # execute 工具不可用("Execution not available...SandboxBackendProtocol")。
    @property
    def __class__(self):
        return CompositeBackend

    @property
    def default(self):
        return self._inner.default

    # --- 路径敏感操作:规范化后委托 ---
    def ls(self, path: str):
        norm = _normalize_fs_path(path)
        return self._inner.ls(norm)

    def als(self, path: str):
        norm = _normalize_fs_path(path)
        return self._inner.als(norm)

    def read(self, file_path: str, **kw):
        norm = _normalize_fs_path(file_path)
        return self._inner.read(norm, **kw)

    def aread(self, file_path: str, **kw):
        norm = _normalize_fs_path(file_path)
        return self._inner.aread(norm, **kw)

    def write(self, file_path: str, *a, **kw):
        _assert_writable(file_path)
        norm = _normalize_fs_path(file_path)
        return self._inner.write(norm, *a, **kw)

    def awrite(self, file_path: str, *a, **kw):
        _assert_writable(file_path)
        norm = _normalize_fs_path(file_path)
        return self._inner.awrite(norm, *a, **kw)

    def edit(self, file_path: str, *a, **kw):
        _assert_writable(file_path)
        norm = _normalize_fs_path(file_path)
        return self._inner.edit(norm, *a, **kw)

    def aedit(self, file_path: str, *a, **kw):
        _assert_writable(file_path)
        norm = _normalize_fs_path(file_path)
        return self._inner.aedit(norm, *a, **kw)

    def glob(self, pattern: str, *a, **kw):
        return self._inner.glob(_normalize_fs_path(pattern), *a, **kw)

    def aglob(self, pattern: str, *a, **kw):
        return self._inner.aglob(_normalize_fs_path(pattern), *a, **kw)

    def grep(self, pattern: str, path: str = "/", **kw):
        return self._inner.grep(pattern, _normalize_fs_path(path), **kw)

    def agrep(self, pattern: str, path: str = "/", **kw):
        return self._inner.agrep(pattern, _normalize_fs_path(path), **kw)

    def delete(self, file_path: str, *a, **kw):
        _assert_writable(file_path)
        return self._inner.delete(_normalize_fs_path(file_path), *a, **kw)

    def adelete(self, file_path: str, *a, **kw):
        _assert_writable(file_path)
        return self._inner.adelete(_normalize_fs_path(file_path), *a, **kw)

    # --- 非路径操作:直通 ---
    def execute(self, *a, **kw):
        return self._inner.execute(*a, **kw)

    def aexecute(self, *a, **kw):
        return self._inner.aexecute(*a, **kw)

    def upload_files(self, *a, **kw):
        return self._inner.upload_files(*a, **kw)

    def aupload_files(self, *a, **kw):
        return self._inner.aupload_files(*a, **kw)

    def download_files(self, *a, **kw):
        return self._inner.download_files(*a, **kw)

    def adownload_files(self, *a, **kw):
        return self._inner.adownload_files(*a, **kw)

    # --- 兜底:未知属性直通 inner ---
    def __getattr__(self, name: str):
        return getattr(self._inner, name)

    def __repr__(self) -> str:
        return f"PathNormalizingProxy({self._inner!r})"




from deepagents import FilesystemPermission, create_deep_agent
from deepagents.backends import CompositeBackend, FilesystemBackend, LocalShellBackend
from deepagents.middleware.skills import SkillsMiddleware
from deepagents.middleware.summarization import (
    SummarizationToolMiddleware,
    create_summarization_middleware,
    create_summarization_tool_middleware,
)
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_openai import ChatOpenAI
from langchain.agents.middleware import AgentMiddleware, TodoListMiddleware

from app import config
from app import workspace as ws
from app.browser import build_browser_tools
from app.memory_tool import build_memory_tools
from app.question_tool import build_question_tools
from app.skill_tool import ReadOnlyBackend, SelectedSkillsBackend, build_skill_tools
from app.widget_tool import build_widget_tools


class _DeepSeekFileScrub(AgentMiddleware):
    """DeepSeek 不支持 file 内容块(base64 编码文件),deepagents 的 FilesystemMiddleware
    误判 ChatOpenAI(DeepSeek 用的)支持非 PDF 文件导致 scrub 失效。此中间件在模型调用前
    移除 ToolMessage 中的 file 块,避免 400 错误。"""

    def wrap_model_call(self, request, handler):
        from langchain_core.messages import ToolMessage
        scrubbed = []
        for msg in request.messages:
            if isinstance(msg, ToolMessage) and isinstance(msg.content, list):
                new_blocks = [b for b in msg.content if not (isinstance(b, dict) and b.get("type") == "file")]
                if len(new_blocks) != len(msg.content):
                    msg = msg.model_copy(update={"content": new_blocks})
            scrubbed.append(msg)
        if scrubbed != list(request.messages):
            request = request.model_copy(update={"messages": scrubbed})
        return handler(request)

    async def awrap_model_call(self, request, handler):
        from langchain_core.messages import ToolMessage
        scrubbed = []
        for msg in request.messages:
            if isinstance(msg, ToolMessage) and isinstance(msg.content, list):
                new_blocks = [b for b in msg.content if not (isinstance(b, dict) and b.get("type") == "file")]
                if len(new_blocks) != len(msg.content):
                    msg = msg.model_copy(update={"content": new_blocks})
            scrubbed.append(msg)
        if scrubbed != list(request.messages):
            request = request.model_copy(update={"messages": scrubbed})
        return await handler(request)


class ContextUsageMiddleware(AgentMiddleware):
    """用 deepagents 压缩中间件同款口径统计上下文占用,经 custom 流通道实时回传前端。

    前端圆环此前按"字符数/4"估算,漏掉 system prompt、工具 schema、tool_calls repr、
    每消息 +3 等隐藏开销,与压缩触发判断用的数字差距可达数万 token。这里复用
    SummarizationMiddleware 自己的 _get_effective_messages(应用历史压缩事件重建
    有效消息列表)与 _count_tokens(count_tokens_approximately + tools schema),
    保证圆环显示的就是 deepagents 判断"是否触发压缩"所用的同一个数。

    阈值取自实际挂载的默认压缩中间件配置(无 profile 模型=170K;有 profile=
    max_input_tokens×fraction),不写死。经 runtime.stream_writer 走 stream_mode
    "custom" 通道发出,不落 state、不查库;非流式调用(writer 不可用)时静默跳过。
    """

    def __init__(self, summarization, threshold: int | None) -> None:
        super().__init__()
        self._sm = summarization
        self._threshold = threshold

    def _emit(self, request) -> None:
        try:
            writer = getattr(request.runtime, "stream_writer", None)
            if writer is None:
                return
            msgs = list(request.messages)
            # 若本请求已被(外层)压缩中间件改写为首条总结消息,直接采用,防止二次应用事件
            first_ak = getattr(msgs[0], "additional_kwargs", None) or {} if msgs else {}
            if first_ak.get("lc_source") == "summarization":
                effective = msgs
            else:
                effective = self._sm._get_effective_messages(request)
            tokens = self._sm._count_tokens(effective, request.system_message, request.tools)
            writer({"usage": {"tokens": tokens, "limit": self._threshold}})
        except Exception:  # noqa: BLE001
            # 统计失败不能影响主链路
            logger.debug("context usage emit failed", exc_info=True)

    def wrap_model_call(self, request, handler):
        self._emit(request)
        return handler(request)

    async def awrap_model_call(self, request, handler):
        self._emit(request)
        return await handler(request)


def _compression_threshold(sm) -> int | None:
    """从压缩中间件的 trigger 子句解析出 token 阈值(与 _should_summarize 同口径)。"""
    for clause in sm._lc_helper._trigger_clauses or []:
        if "tokens" in clause:
            return int(clause["tokens"])
        if "fraction" in clause:
            limits = sm._lc_helper._get_profile_limits()
            if limits:
                return max(1, int(limits * float(clause["fraction"])))
    return None

# 平台内置技能(默认每个会话都加载;与镜像 agent-service/skills 下的目录名一致)
BUILTIN_SKILLS = {
    "browser-operate", "create-skill", "docx", "html-report", "pdf", "pptx", "xlsx",
    "dynamic-ui", "excalidraw", "mermaid",
}


def _skill_name(skill_path: str) -> str:
    return Path(skill_path).parent.name


class ReloadSkillsMiddleware(SkillsMiddleware):
    """每轮消息都按最新勾选/最新技能文件重载技能元数据。

    deepagents 原版把 skills_metadata 缓存进线程 state(存在即跳过,哪怕为空),
    导致会话中途改勾选、create_skill 新建技能、以及修复前残留的空列表都不会刷新。
    这里在进入前主动丢弃缓存键,强制走一遍重载(ls + 读 SKILL.md 开销极小)。
    """

    def before_agent(self, state, runtime, config):
        state = dict(state or {})
        state.pop("skills_metadata", None)
        return super().before_agent(state, runtime, config)

    async def abefore_agent(self, state, runtime, config):
        state = dict(state or {})
        state.pop("skills_metadata", None)
        return await super().abefore_agent(state, runtime, config)


def _select_skills(all_skill_paths: list[str], skills_override: list[str] | None) -> list[str]:
    """内置技能默认加载;自定义技能仅加载被勾选(skills_override 里列名的)。"""
    picked = set(skills_override or [])
    builtins = [p for p in all_skill_paths if _skill_name(p) in BUILTIN_SKILLS]
    custom = [p for p in all_skill_paths if p not in builtins and _skill_name(p) in picked]
    return sorted(set(builtins + custom))

_runtime = None
_RUNTIME_LOCK = threading.Lock()


def _get_runtime():
    global _runtime
    if _runtime is None:
        with _RUNTIME_LOCK:
            if _runtime is None:
                from app.persistence import get_runtime

                _runtime = get_runtime()
    return _runtime


class ReasoningChatOpenAI(ChatOpenAI):
    """透传 OpenAI 兼容接口的 reasoning_content(如 deepseek 推理段)到 additional_kwargs。"""

    def _convert_chunk_to_generation_chunk(self, chunk, default_chunk_class, base_generation_info):
        gen = super()._convert_chunk_to_generation_chunk(chunk, default_chunk_class, base_generation_info)
        if gen is not None:
            choices = chunk.get("choices") or []
            delta = (choices[0].get("delta") or {}) if choices else {}
            rc = delta.get("reasoning_content")
            if rc:
                ak = getattr(gen.message, "additional_kwargs", None)
                if isinstance(ak, dict):
                    ak["reasoning_content"] = rc
        return gen


def _build_model() -> BaseChatModel:
    if config.AGENT_MODEL_BASE_URL:
        # 支持 deepseek/ollama/自建网关等 OpenAI 兼容接口:AGENT_MODEL 里剥掉 provider: 前缀
        model = config.AGENT_MODEL.split(":", 1)[-1]
        # DeepSeek V4 思维链控制:关闭时可减少输出长度与延迟/成本
        extra_body: dict = {}
        if config.AGENT_MODEL_THINKING:
            extra_body["thinking"] = {"type": "enabled"}
        else:
            extra_body["thinking"] = {"type": "disabled"}
        extra_body["reasoning_effort"] = config.AGENT_MODEL_REASONING_EFFORT
        return ReasoningChatOpenAI(
            model=model,
            base_url=config.AGENT_MODEL_BASE_URL,
            api_key=config.AGENT_MODEL_API_KEY or "not-needed",
            temperature=config.AGENT_MODEL_TEMPERATURE,
            extra_body=extra_body,
        )
    from langchain.chat_models import init_chat_model

    return init_chat_model(
        config.AGENT_MODEL,
        temperature=config.AGENT_MODEL_TEMPERATURE,
    )


def _build_backend(user_id: str, visible_skills: set[str] | None = None) -> CompositeBackend:
    """default 与 /workspace/ 路由共用同一个后端(execute 只走 default)。

    - aio(默认):AioSandbox —— execute 与文件工具全部在 per-user AIO 容器内
      (自带浏览器/VNC/VSCode/shell)
    - local:LocalShellBackend(agent-service 进程内直跑 shell,仅本机可信调试)
    /skills 走本地只读文件系统,根列表按会话勾选过滤(visible_skills);
    SkillsMiddleware 的 source 固定为虚拟目录 /skills(它只认 backend 虚拟路径,
    传宿主绝对路径会 path_not_found —— 曾导致技能加载为空)。
    """
    mode = config.AGENT_SANDBOX_BACKEND
    if mode == "aio":
        from app.aio_sandbox import get_aio_sandbox

        workspace_backend = get_aio_sandbox(user_id)
    elif mode == "local":
        workspace_backend = LocalShellBackend(
            root_dir=str(ws.workspace_root(user_id)), virtual_mode=True
        )
    else:
        raise ValueError(f"非法 AGENT_SANDBOX_BACKEND: {mode!r}(可选 aio|local)")

    # 不要把 /workspace 注册成路由(aio 模式):CompositeBackend 会把命中的路由前缀
    # 剥掉,而 AioSandbox 被实现成 deepagents BaseSandbox,自身负责把虚拟路径
    # /workspace/... 翻译成 AIO 容器内 /home/gem/workspace/...。剥掉前缀会让路径变成
    # 裸 /scripts/x.py,AioSandbox 无法翻译 → 落到容器根目录,写文件 PermissionError。
    # 而 /workspace 路由与 default 本就是同一后端,故 aio 下不注册,让 /workspace/...
    # 原样落到 default(路径保持完整,由 AioSandbox 翻译)。
    # local 模式(LocalShellBackend virtual_mode)路径是相对 root_dir 的虚拟路径,
    # 需要靠 /workspace 路由剥前缀才能正确映射,因此 local 下仍保留该路由。
    routes = {
        # 技能目录只读:变更唯一入口是 create_skill 工具(经后端入库+落盘,防漂移)
        "/skills/": SelectedSkillsBackend(
            FilesystemBackend(root_dir=str(ws.skills_root(user_id)), virtual_mode=True),
            visible=visible_skills,
        ),
    }
    if mode == "local":
        routes["/workspace/"] = workspace_backend
    backend = CompositeBackend(
        default=workspace_backend,
        # artifacts_root:summarization 中间件把被压缩的旧历史/媒体卸载到
        # {artifacts_root}/conversation_history。默认 "/" 在容器内不可写
        # (PermissionError → "Older messages will not be recoverable"),
        # 且卸载失败会让每轮都全量重总结。指到 per-user 沙箱工作区,可写且按用户隔离。
        artifacts_root="/workspace",
        routes=routes,
    )
    return _PathNormalizingProxy(backend)


def _build_permissions() -> list[FilesystemPermission]:
    # 平台内置模板目录 /workspace/tpl 只读:agent 只能读「平台下发模板」,不能改。
    # (用户「做同款」产出的模板 HTML 落在 /workspace/templates,可写)
    return [
        FilesystemPermission(operations=["write"], paths=["/workspace/tpl", "/workspace/tpl/**"], mode="deny"),
    ]


_DANGEROUS_LEAD_TOKENS = {
    "rm", "rmdir", "mv", "chmod", "chown", "chgrp", "sudo", "su", "apt", "apt-get",
    "pip", "pip3", "curl", "wget", "git", "mkfs", "dd", "shutdown", "reboot",
    "kill", "pkill", "killall", "chattr", "useradd", "userdel", "passwd",
    # 系统/服务/挂载/定时/内核模块/网络外联等高风险命令,一律要求确认
    "systemctl", "service", "mount", "umount", "crontab", "modprobe", "insmod",
    "nc", "ncat", "socat", "iptables", "nft", "setfacl", "usermod", "groupadd",
}


def _dangerous_command(req) -> bool:
    """execute 是否危险命令(when 判定):命中高危命令/复合命令才要求确认,避免每次 execute 都打扰。

    用 token/词法判定而非前缀匹配,堵住 `ls; rm -rf /`、`bash -c 'sudo ...'` 等绕过。
    命中以下任一情形即视为危险:
      - 复合命令(`;` / `&&` / `||` / `|` / 反引号 / `$()` )——无法可靠判定整体语义,一律确认;
      - 首命令词属于高危集合(rm/mv/sudo/pip/curl/git push 等);
    """
    tool_call = getattr(req, "tool_call", None) or getattr(req, "request", None)
    args = (tool_call or {}).get("args", {}) or {}
    cmd = str(args.get("command") or "").strip()
    if not cmd:
        return False
    # 复合命令 / 命令替换 / 反引号:无法静态判定行为,一律要求确认
    if any(ch in cmd for ch in (";", "&&", "||", "|")):
        return True
    if "`" in cmd or "$(" in cmd:
        return True
    try:
        import shlex

        tokens = shlex.split(cmd)
    except Exception:  # noqa: BLE001
        return True  # 解析失败(引号不闭合等)按危险处理
    if not tokens:
        return False
    lead = tokens[0].lower()
    if lead in _DANGEROUS_LEAD_TOKENS:
        # git 仅特定子命令危险(push/reset/clean/checkout 会变更远端/历史)
        if lead == "git":
            sub = tokens[1].lower() if len(tokens) > 1 else ""
            return sub in {"push", "reset", "clean", "checkout", "rebase", "commit", "tag"}
        return True
    return False


def _build_interrupt_on(agent_type: str = "chat") -> dict | None:
    """HITL 人工确认:仅对危险/敏感操作触发(execute 危险命令 + delete + 浏览器访问)。

    - chat(默认):AGENT_HITL=false 时关闭;开启时按下方策略要求批准 / 提问。
    - task(无人值守):**始终关闭所有中断**——定时任务没有人应答,任何提问/授权都会卡死。
    """
    if agent_type == "task":
        return None
    if not config.AGENT_HITL:
        return None
    return {
        "execute": {
            "allowed_decisions": ["approve", "reject"],
            "description": "该命令被判定为危险操作,需用户允许后才执行。",
            "when": _dangerous_command,
        },
        "delete": {"allowed_decisions": ["approve", "reject"]},
        "browser_navigate": {
            "allowed_decisions": ["approve", "reject"],
            "description": "打开浏览器访问网页,需用户允许后才执行。",
            "when": lambda req: True,
        },
        # create_skill 会持久修改用户技能库,每次都需确认
        "create_skill": {
            "allowed_decisions": ["approve", "reject"],
            "description": "创建或更新用户技能库(写入 SKILL.md 并入库),需用户允许后执行。",
            "when": lambda req: True,
        },
        # agent 向用户提问以获取补充信息
        "ask_user": {
            "allowed_decisions": ["respond"],
            "description": "agent 需要向您提问以获取更多信息,请回答后继续。",
            "when": lambda req: True,
        },
    }


_DEFAULT_SYSTEM_PROMPT = (
    "你是用户的 AI 工作助手。所有用户文件都放在 /workspace 下,"
    "请在该目录内处理与生成文件,不要写入其他位置。"
    "可用技能介绍见 /skills 目录,需要时才读取对应的 SKILL.md。"
    "只能使用 /skills 目录下实际存在的技能;不确定时先列出该目录确认,"
    "不要声称拥有或调用目录里不存在的技能。"
    "你拥有 execute 工具,可在隔离沙箱的 shell 里运行命令;当前工作目录"
    "就是 /workspace 对应的真实目录,所以在命令行里请用相对路径读写文件"
    "(例如 python3 gen.py 生成 report.docx);write_file/read_file 的 "
    "/workspace/xxx 与命令行相对路径指向同一个位置,不要混用 /workspace/xxx "
    "做 shell 路径。重要:ls/read_file/write_file/edit_file 的路径必须以"
    "/workspace/ 开头(如 ls(\"/workspace/\")),绝对不要用 \"/\" 作为 ls 路径,"
    "否则会列出沙箱系统根目录而非用户工作区。沙箱默认无法访问外部网络与工作区之外的文件,"
    "预装了 python3 及常用文档/数据处理库(python-docx/pptx/openpyxl/"
    "reportlab/pandas/matplotlib 等),不要尝试 pip install。"
    "\n\n文件分类规范(生成文件时必须遵守):"
    "\n  widgets/        — 内联可视化组件(pure_show_widget 自动存入,文件名用业务名)"
    "\n  reports/        — HTML 报告、文档交付物(html-report/docx/pdf/xlsx/pptx)"
    "\n  charts/         — 图表 PNG/SVG 导出(matplotlib/seaborn 生成的图片)"
    "\n  scripts/        — 数据处理/生成脚本(.py)"
    "\n  data/           — 中间数据文件(.json/.csv/.xlsx 原始数据)"
    "\n  uploads/        — 用户上传的文件(保持原位)"
    "\n  templates/      — 「做同款」引用的模板 HTML 附件(可写)"
    "\n  tpl/            — 平台内置模板(只读,不要修改)"
    "\n规则:生成文件前先创建目标目录(如 mkdir -p reports),不要把报告/脚本/数据/图表"
    "散放在根目录。pure_show_widget 的组件由工具自动存入 widgets/,组件名(name 参数)"
    "必须用业务描述命名(如 widgets/2024年销售趋势分析-xxxx.html),"
    "同一轮会出多张图时更要用不同业务名区分,禁止用 \"widget/chart/图表1\" 这类泛化名;"
    "\n\n生成或修改文件后,必须在回复末尾以 `[结果文件] <相对路径>` 单独列出"
    "交付的文件(每个文件一行,路径相对 /workspace,如 `[结果文件] reports/report.html`、"
    "`[结果文件] scripts/process_data.py`)。不要带 /workspace 前缀,不要放在代码块里。"
    "如果用户没有要求生成文件,则不要输出该标记。该标记会被前端渲染成\"结果文件\""
    "卡片,方便用户点击下载/预览。"
    "\n\n创建/写入文件请优先使用 write_file 或 edit_file 工具(它们会登记输出物路径,"
    "前端结果文件卡片更可靠),不要用 shell 里的 echo/cat 重定向去创建文件;"
    "只有内容需要程序动态生成(如运行脚本产出报告)时才用 execute。"
    "\n\n技能目录 /skills 对普通写文件工具只读:当用户要求创建新技能、完善/修改已有技能时,"
    "使用 create_skill 工具保存(会弹出确认框,并自动进入用户的技能管理列表)。"
    "平台内置技能(docx/pptx/xlsx/pdf/html-report/browser-operate/create-skill/excalidraw/mermaid)不可修改。"
    "\n\n浏览器操作(与用户实时面板同一浏览器):需要时直接调用工具执行,不要先询问用户——"
    "打开网页(browser_navigate)系统会自动弹出确认框请用户允许,允许后继续;"
    "按需使用 browser_get_text(读页面文字)、browser_click/browser_click_text(点击)、"
    "browser_type(输入)、browser_scroll(滚动)、browser_go_back(返回)、"
    "browser_screenshot(截图,仅当用户要求截图时用;截图用 [结果文件] 标记交付)。默认不截图。"
    "\n\n用户记忆管理:使用 update_memory 记录用户的重要信息(偏好、习惯、关键事实),"
    "记忆会跨会话持久化。当用户提到重要偏好/习惯/需求时主动记录。"
    "记忆超过 4KB 时自动压缩(保留最近 20 条,旧条目摘要化)。"
    "可用 read_memory 查看当前记忆,compress_memory 手动压缩,clear_section 清空分区。"
    "\n\n任务规划(必须遵守):凡是需要两步以上才能完成的任务——生成文档/报告/图表/网页、"
    "先查资料再产出、浏览器操作后写文件、多文件产出、跑脚本验证等——在开始动手前,必须先用 "
    "write_todos 工具列出任务清单(每项一句具体描述,如\"用 mermaid 技能绘制诉讼流程图\"),"
    "一次只把一项设为 in_progress;每完成一项立即把该项标为 completed 并把下一项设为 in_progress;"
    "全部完成后才开始写最终回复。"
    "更新清单时,优先把 write_todos 与下一步动作的工具调用合并在同一次发起(write_todos 可以"
    "和其它工具并行,只禁止一次发多个 write_todos),不要为更新计划单独消耗一轮;也可以阶段性"
    "批量更新多项状态。"
    "前端有任务规划卡片实时展示进度,这是用户可见的承诺清单,不要省略。"
    "只有单步即可完成的小任务(直接回答、只写一个小文件且无后续步骤)才允许不用 write_todos。"
    "\n\n不要主动创建测试/示例文件(如 hello.docx、test.py、demo 等),除非用户明确要求。"
    "首次对话时直接回应用户需求,不要用生成文件来\u201c演示能力\u201d。"
)

# chat(交互)专属:向用户提问段——任何提问都会触发确认/等待,仅交互场景可用。
ASK_USER_PROMPT = (
    "\n\n向用户提问:当用户请求信息不完整、需要确认细节、或有多种实现方式需要选择时,"
    "使用 ask_user 工具向用户提问。提问要具体明确。"
    "如果有明确的几个选项,用 options 参数传入选项(用 | 分隔,如 options=\"Python|JavaScript|都可以\")。"
    "需要用户选多个时设 multiple=\"true\"。没有固定选项时留空 options,用户自由输入。"
    "只在真正需要用户输入时才提问,不要用提问代替思考。"
)

# task(无人值守)专属:任务在无人值守下运行,绝不能向用户提问或等待确认。
AUTONOMOUS_PROMPT = (
    "\n\n【无人值守任务模式】你在无人值守的定时任务中运行:没有用户在场,"
    "任何提问、确认或等待授权的动作都会导致任务卡死失败。因此:"
    "\n1. 严禁向用户提问、严禁调用 ask_user、严禁输出「请告诉我/请确认/你希望…」这类需要用户回答的语句;"
    "\n2. 信息不完整或存在多种方案时,自行选择最合理的一种并直接执行到底,"
    "在结果里说明你采用的假设与选择即可;"
    "\n3. 不要请求授权:需要执行的动作直接完成(包括打开浏览器、创建/更新技能等);"
    "\n4. 输出即最终交付:直接给出可直接使用的结果(报告/文件/结论),不要以问题结尾。"
)


def _build_system_prompt(agent_type: str) -> str:
    """按 agent 类型组装系统提示词:chat 含可提问段;task 换成无人值守段。"""
    if agent_type == "task":
        return _DEFAULT_SYSTEM_PROMPT + AUTONOMOUS_PROMPT
    return _DEFAULT_SYSTEM_PROMPT + ASK_USER_PROMPT


def _fingerprint(user_id: str, skills: list[str], memory: list[str], system_prompt: str | None, mcp_servers=None, agent_type: str = "chat") -> str:
    # memory 已迁移至 PostgresStore(运行时读取),不再参与 agent 编译指纹;
    # 保留参数签名兼容,但忽略 memory 文件列表。
    mcp_sig = mcp_client.mcp_signature(mcp_servers or [])
    parts = [user_id, agent_type, system_prompt or _DEFAULT_SYSTEM_PROMPT] + skills + ["browser_open_url", mcp_sig]
    return hashlib.sha256("\x1f".join(parts).encode()).hexdigest()


class AgentManager:
    """每用户编译后的 agent 缓存;技能/记忆文件被平台更新后自动失效重建。"""

    def __init__(self) -> None:
        self._cache: dict[str, object] = {}
        self._lock = threading.Lock()

    def get_or_create(
        self,
        user_id: str,
        *,
        system_prompt: str | None = None,
        extra_tools: list | None = None,
        skills_override: list[str] | None = None,
        mcp_servers: list | None = None,
        agent_type: str = "chat",
    ):
        ws.ensure_user_dirs(user_id)
        # SkillsMiddleware 只接受 backend 虚拟路径:source 固定 /skills(根),勾选过滤由
        # SelectedSkillsBackend 在 ls 时完成。这里仍先按宿主路径选出名字,用于过滤与缓存指纹。
        selected_paths = _select_skills(ws.list_skills(user_id) + ws.list_global_skills(), skills_override)
        skills = ["/skills"] if selected_paths else []
        memory = ws.memory_files(user_id)
        effective_prompt = system_prompt or _build_system_prompt(agent_type)
        key = _fingerprint(user_id, selected_paths, memory, effective_prompt, mcp_servers, agent_type)

        with self._lock:
            cached = self._cache.get(key)
            if cached is not None:
                return cached

        logger.info("[%s] agent build: type=%s skills=%s", user_id, agent_type, [_skill_name(p) for p in selected_paths])
        backend = _build_backend(user_id, {_skill_name(p) for p in selected_paths})
        # 逐个加载用户配置的远程 MCP server 工具;任一失败只跳过该 server,不影响主链路。
        mcp_tools = mcp_client.build_mcp_tools(mcp_servers or [])
        model = _build_model()
        # 无人值守(task)不挂 ask_user:任务无人应答,提问只会卡死。
        question_tools = build_question_tools() if agent_type != "task" else []
        # 上下文用量计数器:独立构造一个压缩中间件实例(不挂执行链,零副作用),
        # 借它的 _get_effective_messages/_count_tokens 与 trigger 配置,得到与自动压缩
        # 判断完全一致的 token 数与阈值。create_summarization_middleware 与
        # create_deep_agent 默认挂载的自动压缩走同一构造路径,配置随模型 profile 变。
        usage_counter = create_summarization_middleware(model, backend)
        agent = create_deep_agent(
            model=model,
            tools=list(extra_tools or []) + build_browser_tools(user_id) + build_skill_tools(user_id)
            + build_widget_tools(user_id) + build_memory_tools(user_id, _get_runtime().store) + question_tools
            + mcp_tools,
            system_prompt=effective_prompt,
            backend=backend,
            # 技能不走 deepagents 内置 skills 参数(它每线程只加载一次);改用强制重载中间件,
            # 会话中途改勾选/新建技能,下一条消息立即生效。
            # compact_conversation:官方按需压缩工具,让模型在上下文变长/切换任务时
            # 主动压缩历史(阈值 50% 以下会拒绝,防过早丢历史);与 create_deep_agent
            # 默认的自动压缩中间件共享 _summarization_event,前台 summary 提示同样生效。
            # ContextUsageMiddleware 置于 scrub 之后、压缩之前(外层):每次模型调用前
            # 用同款口径统计占用并经 custom 流回传,供前端圆环与压缩阈值对齐。
            # 任务规划:挂载 langchain TodoListMiddleware,提供 write_todos 工具,
            # AI 处理复杂任务前先列计划、随进度更新状态;前端任务卡片实时渲染。
            middleware=[_DeepSeekFileScrub(),
                        TodoListMiddleware(),
                        ContextUsageMiddleware(usage_counter, _compression_threshold(usage_counter)),
                        create_summarization_tool_middleware(model, backend)]
            + ([ReloadSkillsMiddleware(backend=backend, sources=["/skills"])] if skills else []),
            # 注意:deepagents 的 filesystem permissions 与「支持命令执行的后端」
            # (SandboxBackendProtocol,即我们的 aio/local 沙箱)不兼容,传入会直接抛
            # NotImplementedError 导致 agent 构建失败(任务/对话全部 500)。
            # /workspace/tpl 只读改由 _PathNormalizingProxy 在写入路径上拦截。
            interrupt_on=_build_interrupt_on(agent_type),
            memory=memory,
            checkpointer=_get_runtime().checkpointer,
            store=_get_runtime().store,
        )

        with self._lock:
            self._cache[key] = agent
            # 容量保护:超过 64 个用户的上线缓存直接清空,交由下次按需重建
            if len(self._cache) > 64:
                self._cache.clear()
        return agent


_manager: AgentManager | None = None
_MANAGER_LOCK = threading.Lock()


def get_manager() -> AgentManager:
    global _manager
    if _manager is None:
        with _MANAGER_LOCK:
            if _manager is None:
                _manager = AgentManager()
    return _manager


def config_for(user_id: str, thread_id: str) -> dict:
    return {
        # recursion_limit:硬性步数上限,防止单个请求无限跑模型调用(烧钱/死循环)。
        # deepagents 内部默认 9999,这里用 AGENT_MAX_TURNS 收紧。
        "recursion_limit": config.AGENT_MAX_TURNS,
        "configurable": {
            "user_id": user_id,
            "thread_id": thread_id,
            "checkpoint_id": None,
        },
    }