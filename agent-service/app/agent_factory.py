"""创建/缓存基于 deepagents 的每用户 agent。

核心:一个进程内多 agent 实例,靠 LangGraph thread_id 区分并发会话;
每个 user_id 拥有独立 /workspace + 只读 /skills + 用户记忆。
"""

from __future__ import annotations

import hashlib
import logging
import threading
from pathlib import Path

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

_KNOWN_PREFIXES = ("/workspace/", "/skills/")


def _normalize_fs_path(path: str) -> str:
    """把 LLM 传入的路径规范化为 CompositeBackend 能正确路由的绝对路径。"""
    if not path:
        return "/workspace/"
    # "/" 或 "/workspace" (无尾斜杠) → 工作区根
    if path == "/" or path == "/workspace":
        return "/workspace/"
    # 已有已知前缀,原样返回
    for pfx in _KNOWN_PREFIXES:
        if path.startswith(pfx):
            return path
    # 裸绝对路径如 "/test.txt" → 补 /workspace/ 前缀
    if path.startswith("/"):
        return "/workspace" + path
    # 相对路径 → 补 /workspace/ 前缀
    return "/workspace/" + path


class _PathNormalizingProxy:
    """轻量代理:把 CompositeBackend 的路径操作统一规范化,其余方法直通。"""

    def __init__(self, inner: CompositeBackend) -> None:
        # 使用 object.__setattr__ 避免触发 __setattr__ 递归
        object.__setattr__(self, "_inner", inner)

    # --- 路径敏感操作:规范化后委托 ---
    def ls(self, path: str):
        return self._inner.ls(_normalize_fs_path(path))

    def als(self, path: str):
        return self._inner.als(_normalize_fs_path(path))

    def read(self, file_path: str, **kw):
        return self._inner.read(_normalize_fs_path(file_path), **kw)

    def aread(self, file_path: str, **kw):
        return self._inner.aread(_normalize_fs_path(file_path), **kw)

    def write(self, file_path: str, *a, **kw):
        return self._inner.write(_normalize_fs_path(file_path), *a, **kw)

    def awrite(self, file_path: str, *a, **kw):
        return self._inner.awrite(_normalize_fs_path(file_path), *a, **kw)

    def edit(self, file_path: str, *a, **kw):
        return self._inner.edit(_normalize_fs_path(file_path), *a, **kw)

    def aedit(self, file_path: str, *a, **kw):
        return self._inner.aedit(_normalize_fs_path(file_path), *a, **kw)

    def glob(self, pattern: str, *a, **kw):
        return self._inner.glob(_normalize_fs_path(pattern), *a, **kw)

    def aglob(self, pattern: str, *a, **kw):
        return self._inner.aglob(_normalize_fs_path(pattern), *a, **kw)

    def grep(self, pattern: str, path: str = "/", **kw):
        return self._inner.grep(pattern, _normalize_fs_path(path), **kw)

    def agrep(self, pattern: str, path: str = "/", **kw):
        return self._inner.agrep(pattern, _normalize_fs_path(path), **kw)

    def delete(self, file_path: str, *a, **kw):
        return self._inner.delete(_normalize_fs_path(file_path), *a, **kw)

    def adelete(self, file_path: str, *a, **kw):
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
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_openai import ChatOpenAI

from app import config
from app import workspace as ws
from app.browser import build_browser_tools
from app.skill_tool import ReadOnlyBackend, SelectedSkillsBackend, build_skill_tools
from app.widget_tool import build_widget_tools

# 平台内置技能(默认每个会话都加载;与镜像 agent-service/skills 下的目录名一致)
BUILTIN_SKILLS = {
    "browser-operate", "create-skill", "docx", "html-report", "pdf", "pptx", "xlsx",
    "dynamic-ui",
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
        return ReasoningChatOpenAI(
            model=model,
            base_url=config.AGENT_MODEL_BASE_URL,
            api_key=config.AGENT_MODEL_API_KEY or "not-needed",
            temperature=config.AGENT_MODEL_TEMPERATURE,
        )
    from langchain.chat_models import init_chat_model

    return init_chat_model(
        config.AGENT_MODEL,
        temperature=config.AGENT_MODEL_TEMPERATURE,
    )


def _build_backend(user_id: str, visible_skills: set[str] | None = None) -> CompositeBackend:
    """default 与 /workspace/ 路由共用同一个后端(execute 只走 default)。

    - docker(默认):DockerSandbox —— execute 与文件工具全部在 per-user 沙箱容器内
    - local:LocalShellBackend(agent-service 进程内直跑 shell,仅本机可信调试)
    /skills 走本地只读文件系统,根列表按会话勾选过滤(visible_skills);
    SkillsMiddleware 的 source 固定为虚拟目录 /skills(它只认 backend 虚拟路径,
    传宿主绝对路径会 path_not_found —— 曾导致技能加载为空)。
    """
    mode = config.AGENT_SANDBOX_BACKEND
    if mode == "docker":
        from app.sandbox import get_docker_sandbox

        workspace_backend = get_docker_sandbox(user_id)
    elif mode == "local":
        workspace_backend = LocalShellBackend(
            root_dir=str(ws.workspace_root(user_id)), virtual_mode=True
        )
    else:
        raise ValueError(f"非法 AGENT_SANDBOX_BACKEND: {mode!r}(可选 docker|local)")

    backend = CompositeBackend(
        default=workspace_backend,
        # artifacts_root:summarization 中间件把被压缩的旧历史/媒体卸载到
        # {artifacts_root}/conversation_history。默认 "/" 在容器内不可写
        # (PermissionError → "Older messages will not be recoverable"),
        # 且卸载失败会让每轮都全量重总结(351+ 条消息,流式前长时间静默)。
        # 指到 per-user 沙箱工作区,可写且按用户隔离。
        artifacts_root="/workspace",
        routes={
            "/workspace/": workspace_backend,
            # 技能目录只读:变更唯一入口是 create_skill 工具(经后端入库+落盘,防漂移)
            "/skills/": SelectedSkillsBackend(
                FilesystemBackend(root_dir=str(ws.skills_root(user_id)), virtual_mode=True),
                visible=visible_skills,
            ),
        },
    )
    return _PathNormalizingProxy(backend)


def _build_permissions() -> list[FilesystemPermission]:
    # 允许 agent 在 /skills(用户技能根)内**新建**技能(create-skill 技能用);
    # 平台内置技能若被同名覆盖,重播种会还原,风险可控。技能外目录默认无额外限制。
    return []


def _dangerous_command(req) -> bool:
    """execute 是否危险命令(when 判定):命中高危前缀才要求确认,避免每次 execute 都打扰。"""
    tool_call = getattr(req, "tool_call", None) or getattr(req, "request", None)
    args = (tool_call or {}).get("args", {}) or {}
    cmd = str(args.get("command") or "").lstrip()
    if not cmd:
        return False
    return cmd.startswith((
        "rm ", "rmdir ", "mv ", "chmod ", "chown ", "chgrp ", "touch -",
        "sudo", "su ", "apt", "apt-get", "pip", "pip3", "python% -m pip%",
        "curl", "wget", "git push", "git reset", "git clean", "git checkout",
        "mkfs", "dd ", "shutdown", "reboot", "kill ", "pkill", "killall",
        "chattr", "useradd", "userdel", "passwd",
    ))


def _build_interrupt_on() -> dict | None:
    """HITL 人工确认:仅对危险/敏感操作触发(execute 危险命令 + delete + 浏览器访问)。AGENT_HITL=false 时关闭。"""
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
    "\n  widgets/        — 内联可视化组件(pure_show_widget 自动存入,无需手动指定)"
    "\n  reports/        — HTML 报告、文档交付物(html-report/docx/pdf/xlsx/pptx)"
    "\n  charts/         — 图表 PNG/SVG 导出(matplotlib/seaborn 生成的图片)"
    "\n  scripts/        — 数据处理/生成脚本(.py)"
    "\n  data/           — 中间数据文件(.json/.csv/.xlsx 原始数据)"
    "\n  uploads/        — 用户上传的文件(保持原位)"
    "\n  tpl/            — 模板文件"
    "\n规则:生成文件前先创建目标目录(如 mkdir -p reports),不要把报告/脚本/数据/图表"
    "散放在根目录。pure_show_widget 的组件由工具自动存入 widgets/,不需要手动指定路径。"
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
    "平台内置技能(docx/pptx/xlsx/pdf/html-report/browser-operate/create-skill)不可修改。"
    "\n\n浏览器操作(与用户实时面板同一浏览器):需要时直接调用工具执行,不要先询问用户——"
    "打开网页(browser_navigate)系统会自动弹出确认框请用户允许,允许后继续;"
    "按需使用 browser_get_text(读页面文字)、browser_click/browser_click_text(点击)、"
    "browser_type(输入)、browser_scroll(滚动)、browser_go_back(返回)、"
    "browser_screenshot(截图,仅当用户要求截图时用;截图用 [结果文件] 标记交付)。默认不截图。"
)


def _fingerprint(user_id: str, skills: list[str], memory: list[str], system_prompt: str | None) -> str:
    parts = [user_id, system_prompt or _DEFAULT_SYSTEM_PROMPT] + skills + memory + ["browser_open_url"]
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
    ):
        ws.ensure_user_dirs(user_id)
        # SkillsMiddleware 只接受 backend 虚拟路径:source 固定 /skills(根),勾选过滤由
        # SelectedSkillsBackend 在 ls 时完成。这里仍先按宿主路径选出名字,用于过滤与缓存指纹。
        selected_paths = _select_skills(ws.list_skills(user_id) + ws.list_global_skills(), skills_override)
        skills = ["/skills"] if selected_paths else []
        memory = ws.memory_files(user_id)
        effective_prompt = system_prompt or _DEFAULT_SYSTEM_PROMPT
        key = _fingerprint(user_id, selected_paths, memory, effective_prompt)

        with self._lock:
            cached = self._cache.get(key)
            if cached is not None:
                return cached

        logger.info("[%s] agent build: skills=%s", user_id, [p.rsplit("/", 2)[-2] for p in selected_paths])
        backend = _build_backend(user_id, {_skill_name(p) for p in selected_paths})
        agent = create_deep_agent(
            model=_build_model(),
            tools=list(extra_tools or []) + build_browser_tools(user_id) + build_skill_tools(user_id)
            + build_widget_tools(user_id),
            system_prompt=effective_prompt,
            backend=backend,
            # 技能不走 deepagents 内置 skills 参数(它每线程只加载一次);改用强制重载中间件,
            # 会话中途改勾选/新建技能,下一条消息立即生效。
            middleware=[ReloadSkillsMiddleware(backend=backend, sources=["/skills"])] if skills else [],
            permissions=_build_permissions(),
            interrupt_on=_build_interrupt_on(),
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
        "configurable": {
            "user_id": user_id,
            "thread_id": thread_id,
            "checkpoint_id": None,
        }
    }