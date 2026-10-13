---
name: docx
description: 创建/编辑/分析 Word(.docx)文档时使用:修订、批注、格式保持、文本提取,支持中文。
---

# Word 文档(docx)处理

环境:Python3,已装 `python-docx`。中文字体统一用 `Noto Sans SC`(字体名,由打开端渲染)。
所有文件写在工作区内,交付时必须用 `[结果文件] <相对路径>` 标记。

## 读取 / 分析
```python
from docx import Document
doc = Document("a.docx")
for p in doc.paragraphs:
    if p.text.strip(): print(p.text)
for t in doc.tables:
    for row in t.rows:
        print(" | ".join(c.text for c in row.cells))
```

## 创建新文档(用 python-docx)
```python
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn

doc = Document()
# 正文字体(含中文):必须同时设 ascii 与 eastAsia
style = doc.styles["Normal"]
style.font.name = "Arial"
style.font.size = Pt(12)
style.element.rPr.rFonts.set(qn("w:eastAsia"), "Noto Sans SC")

doc.add_heading("标题", level=1)          # 标题用内置样式
p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
run = p.add_run("正文内容")
run.bold = True

# 表格
table = doc.add_table(rows=2, cols=2, style="Table Grid")
table.cell(0,0).text = "单元格"

doc.save("output.docx")
```

## 关键规则
- 中文不乱码:每个用到中文的 run/style 都要设 `w:eastAsia` = Noto Sans SC(仅 font.name 不够)。
- 不用 `\n` 拼段落,用 `add_paragraph`;空行用 `add_paragraph()`。
- 图片:`doc.add_picture("img.png", width=Cm(12))`。
- 页边距/纸张:`section.left_margin = Cm(2.5)` 等。
- 列表直接用 `doc.add_paragraph(style="List Bullet"/"List Number")`。
- 编辑已有文档:打开→改段落/表格文本→另存为新文件(保留模板样式)。
- 交付:`[结果文件] output.docx`。

## 格式转换(可选)
- `.doc` : 本环境无 LibreOffice,告知用户需转成 .docx 再处理。
- 转 PDF : 若环境有 `soffice` 可 `soffice --headless --convert-to pdf a.docx`;没有则说明。

## 完成后
- 用 python-docx 重新打开一遍确认可读、无异常。
- 交付文件留工作区并 `[结果文件]` 标记。