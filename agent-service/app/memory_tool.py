"""用户记忆管理工具。

提供结构化记忆读写 + 自动压缩:
- 记忆分三类: 用户偏好、关键事实、近期上下文
- 超过阈值(默认 4KB)时自动压缩: 保留近期条目,旧条目摘要化
- 压缩策略: 保留最近 N 条 + 旧条目合并为摘要段
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

from langchain_core.tools import tool

from app import workspace as ws

# 记忆文件路径
MEMORY_FILE = "AGENTS.md"
# 压缩阈值(字节)
COMPRESS_THRESHOLD = 4096
# 保留最近 N 条不压缩
KEEP_RECENT = 20
# 最大记忆大小(字节) - 超过强制压缩
MAX_MEMORY_SIZE = 8192


def _memory_path(user_id: str) -> Path:
    return ws.memory_root(user_id) / MEMORY_FILE


def _read_memory(user_id: str) -> str:
    path = _memory_path(user_id)
    if path.exists():
        return path.read_text(encoding="utf-8")
    return ""


def _write_memory(user_id: str, content: str) -> None:
    path = _memory_path(user_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _parse_sections(content: str) -> dict[str, list[str]]:
    """解析 AGENTS.md 为结构化 sections。"""
    sections = {}
    current_section = "general"
    sections[current_section] = []

    for line in content.split("\n"):
        if line.startswith("## "):
            current_section = line[3:].strip()
            sections[current_section] = []
        elif line.strip():
            sections[current_section].append(line)

    return sections


def _compress_memory(content: str) -> str:
    """压缩记忆: 保留最近条目,旧条目摘要化。"""
    sections = _parse_sections(content)

    compressed_parts = []
    for section_name, lines in sections.items():
        if len(lines) <= KEEP_RECENT:
            # 条目不多,原样保留
            if section_name == "general":
                compressed_parts.extend(lines)
            else:
                compressed_parts.append(f"## {section_name}")
                compressed_parts.extend(lines)
        else:
            # 超过阈值:保留最近 N 条,旧的压缩为摘要
            recent = lines[-KEEP_RECENT:]
            old = lines[:-KEEP_RECENT]

            # 生成旧条目摘要
            summary_items = []
            for item in old[:10]:  # 最多取 10 条代表性内容
                clean = item.strip().lstrip("- ").lstrip("* ")
                if len(clean) > 50:
                    clean = clean[:50] + "..."
                summary_items.append(clean)

            if section_name == "general":
                if summary_items:
                    compressed_parts.append(f"[历史摘要] {'; '.join(summary_items[:5])}")
                compressed_parts.extend(recent)
            else:
                compressed_parts.append(f"## {section_name}")
                if summary_items:
                    compressed_parts.append(f"[历史摘要] {'; '.join(summary_items[:5])}")
                compressed_parts.extend(recent)

    return "\n".join(compressed_parts)


def build_memory_tools(user_id: str):
    @tool("update_memory")
    def update_memory(key: str, value: str, section: str = "关键事实") -> str:
        """记录重要信息到用户记忆。

        Args:
            key: 信息的简短标签(如 "用户职业", "项目偏好")
            value: 详细信息内容
            section: 记忆分区,可选值: "用户偏好"(喜好/习惯), "关键事实"(重要信息),
                     "近期上下文"(临时/会话相关), 默认 "关键事实"

        Returns:
            操作结果确认。
        """
        if section not in ("用户偏好", "关键事实", "近期上下文"):
            section = "关键事实"

        content = _read_memory(user_id)
        timestamp = time.strftime("%Y-%m-%d")

        # 构造新条目
        new_entry = f"- **{key}**: {value} ({timestamp})"

        # 检查是否已存在同 key 的条目,更新而非重复添加
        lines = content.split("\n") if content else []
        updated = False
        for i, line in enumerate(lines):
            if line.strip().startswith(f"- **{key}**:"):
                lines[i] = new_entry
                updated = True
                break

        if not updated:
            # 找到对应 section 并追加
            section_header = f"## {section}"
            found = False
            for i, line in enumerate(lines):
                if line.strip() == section_header:
                    # 在 section 末尾追加(找到下一个 ## 或文件末尾)
                    insert_pos = i + 1
                    while insert_pos < len(lines) and not lines[insert_pos].startswith("## "):
                        insert_pos += 1
                    lines.insert(insert_pos, new_entry)
                    found = True
                    break

            if not found:
                # section 不存在,创建新的
                if lines and lines[-1].strip():
                    lines.append("")
                lines.append(section_header)
                lines.append(new_entry)

        new_content = "\n".join(lines)

        # 检查是否需要压缩
        if len(new_content.encode("utf-8")) > COMPRESS_THRESHOLD:
            new_content = _compress_memory(new_content)
            _write_memory(user_id, new_content)
            return f"已记录并压缩记忆(超过 {COMPRESS_THRESHOLD} 字节阈值): {key}"
        else:
            _write_memory(user_id, new_content)
            return f"已记录: {key}"

    @tool("read_memory")
    def read_memory() -> str:
        """读取当前用户记忆内容。

        Returns:
            记忆文件的完整内容。
        """
        content = _read_memory(user_id)
        if not content:
            return "暂无记忆记录。"
        size = len(content.encode("utf-8"))
        return f"[记忆大小: {size} 字节]\n\n{content}"

    @tool("compress_memory")
    def compress_memory() -> str:
        """手动压缩用户记忆(摘要化旧条目)。

        Returns:
            压缩结果。
        """
        content = _read_memory(user_id)
        if not content:
            return "暂无记忆,无需压缩。"

        old_size = len(content.encode("utf-8"))
        compressed = _compress_memory(content)
        new_size = len(compressed.encode("utf-8"))

        if new_size < old_size:
            _write_memory(user_id, compressed)
            saved = old_size - new_size
            return f"压缩完成: {old_size} → {new_size} 字节 (节省 {saved} 字节)"
        else:
            return f"无需压缩(当前 {old_size} 字节)"

    @tool("clear_section")
    def clear_section(section: str) -> str:
        """清空指定记忆分区。

        Args:
            section: 要清空的分区名称

        Returns:
            操作结果。
        """
        content = _read_memory(user_id)
        if not content:
            return "暂无记忆。"

        sections = _parse_sections(content)
        if section not in sections:
            return f"分区 '{section}' 不存在。可用分区: {', '.join(sections.keys())}"

        sections[section] = []
        # 重建文件
        parts = []
        for name, lines in sections.items():
            if name == "general":
                parts.extend(lines)
            else:
                if lines:
                    parts.append(f"## {name}")
                    parts.extend(lines)

        _write_memory(user_id, "\n".join(parts))
        return f"已清空分区: {section}"

    return [update_memory, read_memory, compress_memory, clear_section]
