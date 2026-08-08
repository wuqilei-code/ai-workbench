---
name: document-generation
description: Use when the user asks to generate or produce office documents — Word (.docx), PowerPoint (.pptx), Excel (.xlsx), PDF, CSV, or report files. Encodes the sandbox's preinstalled Python libraries, CJK/Chinese font handling, intermediate-script cleanup, and deliverable declaration conventions so generated files render correctly.
license: MIT
compatibility: opencode
metadata:
  audience: all-users
  workflow: document-authoring
---

# Document Generation

Generate Word / PowerPoint / Excel / PDF / CSV deliverables in the sandbox using the **preinstalled** Python libraries. Never `pip install`.

## Environment facts (do not try to change)

- Preinstalled and importable directly: `python-docx`, `python-pptx`, `openpyxl`, `reportlab`, `PyPDF2`, `pdfplumber`, `markdown`, `numpy`, `pandas`, `matplotlib`, `requests`, `Pillow`, `lxml`.
- `pip` / `ensurepip` are removed. Do **NOT** run `pip install`, `python3 -m pip`, `python3 -m ensurepip`, or download `get-pip.py`. If a needed library is missing, tell the user and ask the admin to add it to the image.
- CJK fonts are installed via `fonts-noto-cjk`. Locate them dynamically before hardcoding a path:
  ```bash
  fc-list :lang=zh file family
  ```
  Typical default: `/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc` (family `Noto Sans CJK SC`). If `fc-list` finds something else, use that.
- CLI utilities available for inspection/conversion: `unzip` (inspect docx/pptx/xlsx internals), `pdftotext` / `pdftoppm` (poppler-utils — extract PDF text or render PDF pages to images).

## CJK font handling per format

- **docx (python-docx)**: set both the Latin and East-Asian font, otherwise Chinese text falls back to the viewer default. Use `SimHei` (standard on Windows, where users open files; matches the workbench's own exports):
  ```python
  from docx import Document
  from docx.oxml.ns import qn
  doc = Document()
  r = doc.add_paragraph().add_run("中文内容")
  r.font.name = "SimHei"                        # latin
  r._element.rPr.rFonts.set(qn("w:eastAsia"), "SimHei")
  doc.save("out.docx")
  ```
- **pptx (python-pptx)**: set `run.font.name = "SimHei"` on every run that may contain Chinese.
- **xlsx (openpyxl)**: no font step needed for Chinese to work; set column widths and number formats so tables are readable.
- **pdf (reportlab)**: the sandbox's Noto CJK fonts are PostScript/CFF outlines, which `reportlab.pdfbase.ttfonts.TTFont` does **not** support (raises `postscript outlines are not supported`). Use the bundled TrueType **SimHei** extracted from the app jar instead:
  ```python
  import zipfile
  from reportlab.pdfbase import pdfmetrics
  from reportlab.pdfbase.ttfonts import TTFont
  with zipfile.ZipFile("/app/app.jar") as z:
      z.extract("BOOT-INF/classes/fonts/simhei.ttf", "/tmp")
  pdfmetrics.registerFont(TTFont("SimHei", "/tmp/BOOT-INF/classes/fonts/simhei.ttf"))
  # then in styles: fontName="SimHei"
  ```
  Fallback if the jar is unavailable: `pdfmetrics.registerFont(UnicodeCIDFont("STSong-Light"))` — the viewer must supply that font.
- **charts with Chinese labels (matplotlib)**: see the `data-analysis` skill; fonts are configured the same way via `matplotlib.font_manager`.

## Workflow

1. Check what the user wants: format(s), structure (chapters/tables/columns), and target language.
2. Prefer **semantic structure over decoration**: headings, tables, lists, page breaks; keep fonts to one CJK family at consistent sizes.
3. Write the generator script, run it, and verify the output file exists and is non-empty. Open/inspect it when cheap (e.g. `docx`/`pptx`/`xlsx` are zips; unzip and eyeball the XML if a rendering detail is suspect).
4. Clean up: after the final file is generated and verified, delete intermediate generator scripts (per chat.md's "Intermediate script cleanup" rule — do not delete when the script itself is the deliverable).
5. Declare deliverables with one `[结果文件] <relative-path>` line per file at the end of the reply (per chat.md's "Deliverable declaration" rule).
6. When a reply produces **2 or more** deliverables, first create a `topic_date` subdirectory (e.g. `大模型发展报告_20260808`) and write everything into it so the bundled zip extracts as one folder.

## Rules

- Exactly one deliverable per task unless the user asked for a set; don't pad a single doc request into multiple files.
- Never write outside the current workspace. Never read config/secret files.
- If a requested format can't be produced cleanly (e.g. exotic fonts or watermark features), say so and offer the closest alternative instead of silently degrading.
- Keep the `[结果文件]` lines exact (no quotes/punctuation) — the UI parses them into downloadable cards.
