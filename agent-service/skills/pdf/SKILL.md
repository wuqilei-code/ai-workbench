---
name: pdf
description: 处理 PDF 文档时使用:读取文本/表格、创建(PDF 报告)、合并拆分、加水印/密码。
---

# PDF 处理

环境:Python3,已装 `pypdf`、`pypdf2`、`pdfplumber`、`reportlab`、`matplotlib`。
中文字体 simhei.ttf(`/usr/share/fonts/truetype/simhei.ttf`,TrueType)。交付用 `[结果文件] <相对路径>`。

## 读取(文本 / 表格)
```python
from pypdf import PdfReader
reader = PdfReader("a.pdf")
for page in reader.pages:
    print(page.extract_text())

import pdfplumber
with pdfplumber.open("a.pdf") as pdf:
    for page in pdf.pages:
        for t in page.extract_tables() or []:
            for row in t: print(row)
```

## 长 PDF:分页提取 + 检索(避免一次性读全文)
1. 每页存 `extracted/pages/page_XXXX.txt`;
2. 用关键词/正则逐页命中后,把**命中页完整文本**作为上下文;
3. 命中在页首/页尾时带上相邻一页;去重已用过的页。

## 创建 PDF(reportlab + SimHei)
```python
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak
from reportlab.lib.units import cm
import os

font_path = "/usr/share/fonts/truetype/simhei.ttf"
pdfmetrics.registerFont(TTFont("SimHei", font_path))  # 中文必须注册字体

body = ParagraphStyle("body", fontName="SimHei", fontSize=11, leading=18,
                      wordWrap="CJK", spaceAfter=10)
title = ParagraphStyle("title", fontName="SimHei", fontSize=24, leading=30,
                       alignment=1, spaceAfter=20, wordWrap="CJK")

doc = SimpleDocTemplate("output.pdf", pagesize=A4,
                        leftMargin=2*cm, rightMargin=2*cm,
                        topMargin=2*cm, bottomMargin=2*cm)
story = [Paragraph("标题", title), Spacer(1, 12), Paragraph("正文段落…", body)]
doc.build(story)
```

### 文本规范化
reportlab 不支持部分 Unicode 破折号,写入前替换:–—―－ 等 -> `-`。

### 中文图表
matplotlib(SimHei,见 xlsx 技能)+ `savefig("chart.png")`,再 `Image` 插入 PDF。

## 合并 / 拆分
```python
from pypdf import PdfWriter, PdfReader
w = PdfWriter()
for f in ["a.pdf","b.pdf"]:
    for pg in PdfReader(f).pages: w.add_page(pg)
w.write("merged.pdf")
# 拆分:按 range 取 pages 写入新 writer
```

## 常见任务
- 水印:`page.merge_page(PdfReader("wm.pdf").pages[0])`。
- 加密:`writer.encrypt("user","owner")`。
- 旋转:`page.rotate(90)`。
- 扫描件 OCR:本环境无 tesseract,说明情况,建议上传文本版。

## 完成后
- 重开验证页数/文本;交付 `[结果文件] output.pdf`。