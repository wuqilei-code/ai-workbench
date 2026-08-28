"""用户反问工具——agent 主动向用户提问以获取补充信息。

原理:调用 ask_user 触发 HITL interrupt,前端收到问题后展示给用户,
用户回答后 agent 拿到答案继续执行。
"""

from __future__ import annotations

from langchain_core.tools import tool


def build_question_tools():
    @tool
    def ask_user(question: str, options: str = "", multiple: str = "false") -> str:
        """向用户提问以获取补充信息或澄清需求。

        当用户请求信息不完整、需要确认细节、或有多种实现方式需要用户选择时使用。
        只在真正需要用户输入时才提问,不要用提问代替思考。

        Args:
            question: 向用户提出的问题,要具体明确。
            options: 可选项列表,用 | 分隔(如 "Python|JavaScript|都可以")。
                     为空则展示自由输入框。
            multiple: 是否允许多选,"true"或"false"(默认)。
                      多选时用户可勾选多个选项,最终以逗号分隔返回。

        Returns:
            用户的回答内容。多选时为逗号分隔的选项文本。
        """
        # 这个工具的实际执行由 interrupt_on 机制处理:
        # agent 调用此工具 → 触发 interrupt → 前端展示问题 → 用户回答 → agent 收到回答
        # 这里只是占位,实际不会被执行到(被 interrupt 拦截)
        return f"[需要用户回答] {question}"

    return [ask_user]
