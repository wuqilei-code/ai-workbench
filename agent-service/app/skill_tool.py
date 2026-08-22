"""技能库工具:create_skill(创建/更新用户技能,经后端内部接口入库+落盘)。

设计:
- /skills 虚拟根对普通 write/edit/delete 只读(ReadOnlyBackend 包装),
  技能变更唯一入口是 create_skill 工具 → 盘与 user_skill 表不会漂移。
- 后端侧护栏:内置平台技能拒改、市场安装款只读、名称 slug 校验。
"""

from __future__ import annotations

import json
import logging

import httpx
from deepagents.backends.protocol import (
    DeleteResult,
    EditResult,
    FileUploadResponse,
    WriteResult,
)
from langchain_core.tools import tool

from app import config

logger = logging.getLogger(__name__)

_READONLY_MSG = "技能目录只读:请使用 create_skill 工具创建或修改技能"


class ReadOnlyBackend:
    """只读包装:读操作透传内部后端;写/改/删一律返回错误。"""

    def __init__(self, inner) -> None:
        self._inner = inner

    def _deny(self):
        raise PermissionError(_READONLY_MSG)

    # --- 读:透传 ---
    def ls(self, path: str):
        return self._inner.ls(path)

    async def als(self, path: str):
        return await self._inner.als(path)

    def read(self, path: str, offset: int = 0, limit: int | None = None):
        return self._inner.read(path, offset=offset, limit=limit)

    async def aread(self, path: str, offset: int = 0, limit: int | None = None):
        return await self._inner.aread(path, offset=offset, limit=limit)

    def grep(self, *args, **kwargs):
        return self._inner.grep(*args, **kwargs)

    async def agrep(self, *args, **kwargs):
        return await self._inner.agrep(*args, **kwargs)

    def glob(self, pattern: str, path: str | None = None):
        return self._inner.glob(pattern, path=path)

    async def aglob(self, pattern: str, path: str | None = None):
        return await self._inner.aglob(pattern, path=path)

    def download_files(self, paths):
        return self._inner.download_files(paths)

    async def adownload_files(self, paths):
        return await self._inner.adownload_files(paths)

    # --- 写:拒绝 ---
    def write(self, path: str, content) -> WriteResult:
        return WriteResult(error=_READONLY_MSG)

    async def awrite(self, path: str, content) -> WriteResult:
        return WriteResult(error=_READONLY_MSG)

    def edit(self, *args, **kwargs) -> EditResult:
        return EditResult(error=_READONLY_MSG)

    async def aedit(self, *args, **kwargs) -> EditResult:
        return EditResult(error=_READONLY_MSG)

    def delete(self, file_path: str) -> DeleteResult:
        return DeleteResult(error=_READONLY_MSG)

    async def adelete(self, file_path: str) -> DeleteResult:
        return DeleteResult(error=_READONLY_MSG)

    def upload_files(self, files) -> list[FileUploadResponse]:
        return [FileUploadResponse(path=p[0] if isinstance(p, (list, tuple)) else str(p), error=_READONLY_MSG)
                for p in files]

    async def aupload_files(self, files) -> list[FileUploadResponse]:
        return self.upload_files(files)


def build_skill_tools(user_id: str) -> list:
    """create_skill 工具(HITL 确认由 agent_factory interrupt_on 挂载)。"""
    base = config.BACKEND_INTERNAL_URL
    token = config.INTERNAL_TOKEN
    url = f"{base}/api/internal/users/{user_id}/skills/upsert"

    @tool
    def create_skill(name: str, description: str, content: str, files_json: str = "") -> str:
        """创建或更新用户技能库中的技能(SKILL.md 与附加文件一次保存,并进入用户的技能管理列表)。

        Args:
            name: 技能名,小写字母/数字/连字符,如 note-summarizer。已存在则为全量更新。平台内置技能(docx/pptx/xlsx/pdf/html-report/browser-operate/create-skill)不可修改。
            description: 一句话说明用途(展示在技能列表,帮助后续会话判断何时使用)。
            content: SKILL.md 正文(markdown 使用指南;无需 frontmatter,系统自动补 name/description)。
            files_json: 可选附加文件的 JSON 数组字符串,如 [{"path":"scripts/run.py","content":"print(1)"}];path 相对技能目录。传入即全量替换旧附加文件,不需要附加文件则留空。
        """
        files: list[dict] = []
        if files_json and files_json.strip():
            try:
                parsed = json.loads(files_json)
                if not isinstance(parsed, list):
                    return "保存失败:files_json 必须是 JSON 数组"
                for f in parsed:
                    if isinstance(f, dict) and f.get("path"):
                        files.append({"path": str(f["path"]), "content": str(f.get("content", ""))})
            except (ValueError, TypeError) as e:
                return f"保存失败:files_json 不是合法 JSON({e})"
        payload = {
            "name": name.strip(),
            "description": description or "",
            "content": content or "",
            "files": files,
        }
        try:
            resp = httpx.post(url, json=payload, headers={"X-Internal-Token": token}, timeout=30)
        except httpx.HTTPError as e:
            logger.warning("create_skill backend call failed: %s", e)
            return f"保存失败:无法连接服务({e.__class__.__name__}),请稍后重试"
        try:
            data = resp.json()
        except ValueError:
            return f"保存失败:HTTP {resp.status_code}"
        if resp.status_code != 200 or data.get("code") != 0:
            return f"保存失败:{data.get('message') or ('HTTP ' + str(resp.status_code))}"
        skill = data.get("data") or {}
        n_files = len(files)
        return (
            f"技能「{skill.get('name')}」已保存(id={skill.get('id')},"
            f"SKILL.md + {n_files} 个附加文件)。新会话将自动可加载该技能;"
            f"当前会话需在技能配置中勾选后于下一轮生效。"
        )

    return [create_skill]
