---
name: data-analysis
description: Use when the user asks to analyze data, load a dataset (xlsx/csv/json), compute statistics, build charts/plots, or produce a data report. Encodes the sandbox's preinstalled pandas/matplotlib stack, Chinese-font plotting setup, and figure output conventions so charts render Chinese correctly instead of tofu boxes.
license: MIT
compatibility: opencode
metadata:
  audience: all-users
  workflow: data-analysis
---

# Data Analysis

Analyze tabular data with the **preinstalled** `pandas` / `numpy` / `matplotlib` stack and produce figures or data reports. Never `pip install`.

## Environment facts

- Preinstalled and importable directly: `pandas`, `numpy`, `matplotlib`, `openpyxl`, `lxml`, `Pillow`, `scipy` is **not** installed — do not import it.
- `pip` / `ensurepip` are removed. Do not attempt to install anything.
- CJK fonts come from `fonts-noto-cjk`. Locate before hardcoding:
  ```bash
  fc-list :lang=zh file family
  ```
  Default path: `/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc` (family `Noto Sans CJK SC`).

## Reading data

- `.xlsx` / `.csv` / `.json`: `pd.read_excel(...)`, `pd.read_csv(...)`, `pd.read_json(...)`.
- Always check the shape and columns first: `print(df.shape, df.columns.tolist())` and `df.head()` — do not assume column names or encoding.
- Handle missing values explicitly (`df.isna().sum()`); tell the user what you dropped or filled and why.

## Chinese-font plotting (required for any Chinese text in figures)

Configure at the top of every plotting script, otherwise labels render as tofu boxes:

```python
import matplotlib
matplotlib.use("Agg")  # headless
from matplotlib import font_manager
font_manager.fontManager.addfont("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")
matplotlib.rcParams["font.sans-serif"] = ["Noto Sans CJK SC"]
matplotlib.rcParams["axes.unicode_minus"] = False  # minus sign renders correctly
```

If `fc-list` shows a different CJK family/path, use that instead. Verify Chinese actually renders before delivering: render the figure, then check it is non-empty; when in doubt, re-open the PNG and inspect.

## Chart conventions

- `fig, ax = plt.subplots(figsize=(8, 5))`; set `dpi=150`; use `fig.tight_layout()`.
- Prefer the chart type that matches the question: time series → line, categories → bar, distribution → histogram/box, share/ratio → pie only for few slices, correlation → scatter.
- Label axes and add a title in the user's language; put units in axis labels.
- Save with `fig.savefig("...png", bbox_inches="tight")`. PNG for chat/reports; also accept SVG when the user wants editable figures.

## Reporting

- Numbers: round to a sensible precision, show counts and percentages together (e.g. `1234 (12.3%)`).
- Answer the user's question explicitly with the key findings, then support with tables/figures — don't dump raw output.
- Declare every produced file (figure / data / report) with one `[结果文件] <relative-path>` line per file at the end of the reply.
- When a reply produces **2 or more** deliverables, first create a `topic_date` subdirectory and write everything into it.

## Rules

- Never write outside the current workspace; never read config/secret files.
- Intermediate analysis scripts: delete after the final figures/report are generated and verified (keep when the script is itself the deliverable).
- If the data needs a library that is not preinstalled, tell the user and ask the admin to add it — do not attempt to install.
