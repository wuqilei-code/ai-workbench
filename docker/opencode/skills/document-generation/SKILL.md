---
name: document-generation
description: 当用户要求生成办公文档——Word(.docx)、PowerPoint(.pptx)、Excel(.xlsx)、PDF、CSV 或报告文件时使用。固化了沙箱预装的 Python 文档库、各格式中文字体处理、中间脚本清理与交付声明规范,确保生成的文件中文正常渲染。
license: MIT
compatibility: opencode
metadata:
  audience: all-users
  workflow: document-authoring
---

# 文档生成

在沙箱里用**已预装**的 Python 库生成 Word / PowerPoint / Excel / PDF / CSV 交付物。**绝不要 `pip install`。**

## 环境事实(不可改变)

- 可直接 import: `python-docx`、`python-pptx`、`openpyxl`、`reportlab`、`PyPDF2`、`pdfplumber`、`markdown`、`numpy`、`pandas`、`matplotlib`、`requests`、`Pillow`、`lxml`。
- `pip` / `ensurepip` 已移除,不可安装;缺库时告诉用户并请管理员加进镜像。
- 中文字体是内置 TrueType `simhei.ttf`,固定路径 `/usr/share/fonts/truetype/simhei.ttf`(reportlab 和 matplotlib 都能直接加载)。
- 命令行工具:`unzip`(检查 docx/pptx/xlsx 内部 XML)、`pdftotext` / `pdftoppm`(提取 PDF 文本或把 PDF 页渲染成图)。

## 各格式的中文字体处理

- **docx(python-docx)**:同时设置 Latin 和 East-Asian 字体,否则中文会回退到查看器默认字体(Windows 上打开用 SimHei 与工作台导出保持一致):
  ```python
  from docx import Document
  from docx.oxml.ns import qn
  doc = Document()
  r = doc.add_paragraph().add_run("中文内容")
  r.font.name = "SimHei"                          # 西文
  r._element.rPr.rFonts.set(qn("w:eastAsia"), "SimHei")  # 东亚
  doc.save("out.docx")
  ```
- **pptx(python-pptx)**:每个可能含中文的 run 都要 `run.font.name = "SimHei"`。
- **xlsx(openpyxl)**:中文无需特殊字体步骤;但要设置列宽(`ws.column_dimensions["A"].width = ...`)和数字格式,让表格可读。
- **pdf(reportlab)**:沙箱的 Noto CJK 是 CFF 轮廓,`TTFont` 会报 `postscript outlines are not supported`,**必须用内置 SimHei**:
  ```python
  from reportlab.pdfbase import pdfmetrics
  from reportlab.pdfbase.ttfonts import TTFont
  pdfmetrics.registerFont(TTFont("SimHei", "/usr/share/fonts/truetype/simhei.ttf"))
  # 样式中 fontName="SimHei"
  ```
- **含中文的图表(matplotlib)**:见 `data-analysis` 技能,字体配置相同(`font_manager.addfont` + `rcParams`)。

## 工作流

1. 先确认需求:格式、结构(章节/表格/列)、目标语言。
2. **语义结构优先于装饰**:标题分级、表格、列表、分页;字体统一为一个中文字族、字号层次一致;不要堆花哨样式。
3. 写生成脚本 → 运行 → 验证输出文件存在且非空。必要时检查内部(如 docx/pptx/xlsx 是 zip,`unzip -l` 看结构)。
4. 清理:最终文件生成并验证后,删除中间脚本(chat.md「中间脚本清理」规则;脚本本身是交付物时不删)。
5. 末尾每个文件一行 `[结果文件] <相对路径>` 声明(chat.md「结果文件声明」规则)。
6. 一次产出 **2 个及以上** 交付物时,先建 `deliverables/<主题>_<日期>/` 子目录(如 `deliverables/大模型发展报告_20260808`),全部放进去,保证打包 zip 解压成一个目录。

## 各格式常见要点

- **docx**:用 `add_heading` 建标题层级;表格用 `add_table` + 表头加粗;长文档可加页码/目录(注意 python-docx 对目录支持有限,复杂版式要提前告知限制)。
- **pptx**:每页一个主题,要点式呈现不要大段文字;多页用 `slide_layouts`;标题和正文字号拉开层级。
- **xlsx**:多 sheet 用 `pd.ExcelWriter`;表头冻结(`freeze_panes`)便于滚动;数字列统一格式。
- **pdf(reportlab)**:用 `platypus` 的 Paragraph/Table 排版;中文必须注册 SimHei 字体。
- **CSV**:用 `utf-8-sig`(Excel 打开不乱码):`df.to_csv("x.csv", index=False, encoding="utf-8-sig")`。

## 规则

- 一次只交付一个文档,除非用户明确要一套;不要把单个文档请求拆成多个文件。
- 绝不写工作区以外的路径,绝不读配置/密钥文件。
- 某种格式做不干净(如特殊字体、水印)时,如实说明并给最接近的替代方案,不要悄悄降级。
- `[结果文件]` 行保持精确(不加引号/标点),UI 会解析成可下载卡片。
