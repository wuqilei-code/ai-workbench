"""用户记忆管理工具（LangGraph Store 版）。

使用 PostgresStore 做跨会话持久记忆:
- namespace: (user_id, section) — 按用户+分区隔离
- key: 记忆标签（如 "用户职业"）
- value: {"value": "...", "date": "2024-01-01"}
- 自动压缩: 超过 4KB 时保留最近 20 条，旧条目摘要化
"""

from __future__ import annotations

import time
from typing import Any

from langchain_core.tools import tool
from langgraph.store.base import BaseStore

# 压缩阈值（字节）
COMPRESS_THRESHOLD = 4096
# 保留最近 N 条不压缩
KEEP_RECENT = 20
# 有效分区名
VALID_SECTIONS = ("用户偏好", "关键事实", "近期上下文")
# 默认分区
DEFAULT_SECTION = "关键事实"


def build_memory_tools(user_id: str, store: BaseStore):
    def _ns(section: str) -> tuple[str, ...]:
        return (user_id, section)

    def _read_all() -> list[dict[str, Any]]:
        """读取该用户所有记忆条目。"""
        items = store.search((user_id,))
        return [
            {
                "namespace": item.namespace,
                "key": item.key,
                "value": item.value.get("value", ""),
                "date": item.value.get("date", ""),
            }
            for item in items
        ]

    def _format_memory(items: list[dict]) -> str:
        """格式化记忆为可读文本。"""
        if not items:
            return ""
        # 按分区组织
        sections: dict[str, list[dict]] = {}
        for item in items:
            sec = item["namespace"][-1] if len(item["namespace"]) > 1 else "general"
            sections.setdefault(sec, []).append(item)

        parts = []
        for sec in VALID_SECTIONS:
            entries = sections.get(sec, [])
            if not entries:
                continue
            parts.append(f"## {sec}")
            for e in entries:
                parts.append(f"- **{e['key']}**: {e['value']} ({e['date']})")
        return "\n".join(parts)

    def _total_bytes(items: list[dict]) -> int:
        return len(_format_memory(items).encode("utf-8"))

    @tool("update_memory")
    def update_memory(key: str, value: str, section: str = DEFAULT_SECTION) -> str:
        """记录重要信息到用户记忆。

        Args:
            key: 信息的简短标签（如 "用户职业", "项目偏好"）
            value: 详细信息内容
            section: 记忆分区,可选值: "用户偏好"(喜好/习惯), "关键事实"(重要信息),
                     "近期上下文"(临时/会话相关), 默认 "关键事实"

        Returns:
            操作结果确认。
        """
        if section not in VALID_SECTIONS:
            section = DEFAULT_SECTION

        timestamp = time.strftime("%Y-%m-%d")
        store.put(_ns(section), key, {"value": value, "date": timestamp})

        # 检查是否需要压缩
        items = _read_all()
        if _total_bytes(items) > COMPRESS_THRESHOLD:
            _compress(items)
            return f"已记录并压缩记忆（超过 {COMPRESS_THRESHOLD} 字节阈值）: {key}"
        return f"已记录: {key}"

    @tool("read_memory")
    def read_memory() -> str:
        """读取当前用户记忆内容。"""
        items = _read_all()
        if not items:
            return "暂无记忆记录。"
        text = _format_memory(items)
        size = len(text.encode("utf-8"))
        return f"[记忆大小: {size} 字节]\n\n{text}"

    def _compress(items: list[dict]) -> None:
        """压缩：每个分区保留最近 KEEP_RECENT 条，删除旧条目。"""
        sections: dict[str, list[dict]] = {}
        for item in items:
            sec = item["namespace"][-1] if len(item["namespace"]) > 1 else "general"
            sections.setdefault(sec, []).append(item)

        for sec, entries in sections.items():
            if len(entries) <= KEEP_RECENT:
                continue
            # 按 date 排序，保留最近的
            entries.sort(key=lambda e: e.get("date", ""), reverse=True)
            to_delete = entries[KEEP_RECENT:]
            for e in to_delete:
                store.delete(tuple(e["namespace"]), e["key"])

    @tool("compress_memory")
    def compress_memory() -> str:
        """手动压缩用户记忆（摘要化旧条目）。"""
        items = _read_all()
        if not items:
            return "暂无记忆，无需压缩。"
        old_size = _total_bytes(items)
        _compress(items)
        new_items = _read_all()
        new_size = _total_bytes(new_items)
        if new_size < old_size:
            return f"压缩完成: {old_size} → {new_size} 字节（节省 {old_size - new_size} 字节）"
        return f"无需压缩（当前 {old_size} 字节）"

    @tool("clear_section")
    def clear_section(section: str) -> str:
        """清空指定记忆分区。"""
        items = store.search((user_id, section))
        if not items:
            return f"分区 '{section}' 为空或不存在。"
        for item in items:
            store.delete(item.namespace, item.key)
        return f"已清空分区: {section}"

    return [update_memory, read_memory, compress_memory, clear_section]
