"""内联可视化组件工具(dynamic-ui 技能配套)。

模型调用 pure_show_widget 把 widget_code 落到工作区 widgets/ 目录;
前端识别该路径后用沙箱 iframe 内联渲染(见 frontend WidgetFrame.vue)。
widget_code 本身不回传模型(可能几十 KB),只返回落盘路径确认。
"""

import hashlib
import re
import time

from langchain_core.tools import tool

from app import workspace as ws

# 合法性粗检:技能契约要求根元素带 data-dynamic-ui-widget 标记
_ROOT_MARKER = re.compile(r"data-dynamic-ui-widget")
_FORBIDDEN = ("<!doctype", "<html", "<head>", "</html>")
# 文件名单个字符非法集(含空格,避免文件名被 [WIDGET_FILE] 的正则 \S+ 截断)
_FILE_SAFE = re.compile(r'[\\/:*?"<>|\s]+')


def _widget_slug(name: str) -> str:
    """把业务名转成安全的文件名片段(小写、短横线分隔、最长 60 字符)。"""
    s = _FILE_SAFE.sub("-", (name or "").strip()).strip("-._")
    s = re.sub(r"-{2,}", "-", s)
    return s[:60]


def build_widget_tools(user_id: str):
    @tool("pure_show_widget")
    def pure_show_widget(name: str, widget_code: str) -> str:
        """渲染一个内联可视化组件(图表/架构图/交互 demo),穿插展示在回答文字之间。

        粒度硬规则:一次调用只表达一个重点(一张图 / 一个对比 / 一个流程 / 一组关键数字)。
        禁止把仪表盘、多板块汇总、KPI 墙塞进单个组件。
        需要呈现多个方面时:拆成多次调用,每次调用前用一两句文字解读刚出的图,再出下一张。
        聊天内被要求"统计/分析/总结数据"时,必须用本工具穿插作答,而不是生成 HTML 报告页面
        (只有用户明确要报告/文档/文件交付物时才走 html-report)。
        SVG 优先:图表默认用声明式内联 SVG/CSS 绘制(rect 柱、polyline 折线、
        stroke-dasharray 环形),这样代码流式到达时即可逐块渲染;
        仅当交互确实必需时才允许 Canvas/Chart.js(canvas 内容要到生成结束才可见)。

        Args:
            name: 组件对应的业务名称,用简短描述表达图表/组件表达的内容
                (如 "2024年销售趋势分析"、"竞品参数对比"、"订单处理流程"),
                会用作落盘文件名以便在文件树中区分,不要用泛化的 "widget/chart/图表1"。
            widget_code: 完整的组件代码,按 dynamic-ui 技能契约生成:
                <style> → 内容 HTML/SVG → <script>(仅交互时需要),
                根元素必须带 data-dynamic-ui-widget 属性。
                不要包含 <!DOCTYPE>/<html>/<head>/<body> 等页面外壳。

        Returns:
            渲染结果与交付文件路径(相对工作区)。
        """
        code = (widget_code or "").strip()
        if not code:
            return "渲染失败:widget_code 为空。"
        if len(code) > 400_000:
            return "渲染失败:widget_code 超过 400KB 上限,请精简内容。"
        low = code.lower()
        if any(tag in low for tag in _FORBIDDEN):
            return "渲染失败:不要包含 <!DOCTYPE>/<html>/<head>/</html> 页面外壳,直接输出 <style>+内容+<script>。"
        if not _ROOT_MARKER.search(code):
            return (
                '渲染失败:根元素缺少 data-dynamic-ui-widget 属性,'
                '例如 <div data-dynamic-ui-widget data-template="chart-card">…</div>。'
            )

        slug = _widget_slug(name)
        digest = hashlib.sha1(code.encode("utf-8")).hexdigest()[:8]
        if slug:
            rel = f"widgets/{slug}-{digest}.html"
        else:
            # name 未提供/无法 sanitize:回退为时间去重命名,保证不与业务命名混淆
            rel = f"widgets/widget-{int(time.time())}-{digest}.html"
        path = ws.workspace_root(user_id) / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(code, encoding="utf-8")

        return f"组件已生成并渲染:[WIDGET_FILE] {rel}"

    return [pure_show_widget]
