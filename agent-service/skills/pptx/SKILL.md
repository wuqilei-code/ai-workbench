---
name: pptx
description: 创建/编辑/分析演示文稿(.pptx)时使用:布局、配色、图表、演讲备注,支持中文。
---

# PPT 演示文稿(pptx)

环境:Python3,已装 `python-pptx`、`matplotlib`、`Pillow`。中文字体统一 Noto Sans SC
(`/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf`)。文件写工作区,交付用 `[结果文件] <相对路径>`。

## 读取 / 分析
```python
from pptx import Presentation
prs = Presentation("a.pptx")
for i, slide in enumerate(prs.slides):
    for shape in slide.shapes:
        if shape.has_text_frame:
            print(i, shape.text_frame.text)
```

## 创建(推荐 16:9)
```python
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR

prs = Presentation()
prs.slide_width = Inches(13.333); prs.slide_height = Inches(7.5)  # 16:9

blank = prs.slide_layouts[6]
slide = prs.slides.add_slide(blank)
box = slide.shapes.add_textbox(Inches(0.6), Inches(0.5), Inches(12), Inches(1.2))
tf = box.text_frame
p = tf.paragraphs[0]
r = p.add_run(); r.text = "标题"
r.font.size = Pt(36); r.font.bold = True
r.font.color.rgb = RGBColor(0x1E,0x27,0x61)
# 中文字体:python-pptx 通过 font.name 设置;含中文时设 name="Noto Sans SC"
r.font.name = "Noto Sans SC"

from pptx.oxml.ns import qn  # 东亚字体
rPr = r._r.get_or_add_rPr()
ea = rPr.makeelement(qn("a:ea"), {"typeface": "Noto Sans SC"}); rPr.append(ea)

body = slide.shapes.add_textbox(Inches(0.6), Inches(2.0), Inches(12), Inches(5))
tf = body.text_frame; tf.word_wrap = True
para = tf.paragraphs[0]
run = para.add_run(); run.text = "正文内容,支持中文。"
run.font.size = Pt(18); run.font.name = "Noto Sans SC"

prs.save("output.pptx")
```

## 关键规则
- 每页都需要视觉元素(图片/图表/形状);连续两页纯文字后必须加图或表。
- 标题 ≥36pt;正文 14–16pt;正文左对齐。
- 图表用 matplotlib 生成 PNG(Noto Sans SC 字体,见下方),再 `add_picture` 插入。
- 配色稳定:页内/全文统一(深蓝 #1E2761 + 强调色方案任选一个即可)。
- 演讲备注:`slide.notes_slide.notes_text_frame.text = "备注"`。
- 编辑已有:打开→改文本/图片→另存。

## matplotlib 中文图表
```python
import matplotlib
matplotlib.use("Agg")
from matplotlib import font_manager
font_manager.fontManager.addfont("/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf")
import matplotlib.pyplot as plt
plt.rcParams["font.family"] = "Noto Sans SC"
plt.rcParams["axes.unicode_minus"] = False
fig, ax = plt.subplots(figsize=(6.5,4))
ax.bar(["Q1","Q2","Q3","Q4"],[120,135,142,158])
fig.tight_layout(); fig.savefig("chart.png", dpi=150); plt.close(fig)
```

## 完成后
- 用 python-pptx 重开验证页数与关键文本。
- 交付 `[结果文件] output.pptx`(及用到的图表可一并交付)。