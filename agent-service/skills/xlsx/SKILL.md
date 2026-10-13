---
name: xlsx
description: 处理电子表格(.xlsx/.csv)时使用:读取、编辑、创建、公式、样式美化、图表、数据清洗。
---

# Excel 表格(xlsx)

环境:Python3,已装 `openpyxl`、`pandas`、`numpy`、`matplotlib`。文件写工作区,交付用 `[结果文件] <相对路径>`。

## 读取
```python
import openpyxl
wb = openpyxl.load_workbook("a.xlsx", data_only=True)  # data_only=True 读计算值
ws = wb["Sheet1"]
for row in ws.iter_rows(values_only=True):
    print(row)
# pandas 快速读
import pandas as pd
df = pd.read_excel("a.xlsx"); df.head()
```

## 创建(含公式与样式)
```python
import openpyxl
from openpyxl.styles import PatternFill, Border, Side, Font
from openpyxl.utils import get_column_letter

wb = openpyxl.Workbook()
ws = wb.active; ws.title = "数据"
ws.append(["月份", "销售额", "增长率"])
data = [["1月", 120, "=B2/B2-1"], ["2月", 135, "=(B3-B2)/B2"]]
for r in data: ws.append(r)
ws["B4"] = "=SUM(B2:B3)"          # 用公式,不要硬编码
ws["B4"].font = Font(bold=True)

# 斑马纹 + 细边框
zebra = [PatternFill(fill_type="solid", fgColor="FFFFFF"), PatternFill(fill_type="solid", fgColor="F7F9FC")]
thin = Side(style="thin", color="D9EED7"); border = Border(left=thin,right=thin,top=thin,bottom=thin)
for row in ws.iter_rows(min_row=1, max_row=4, max_col=3):
    for c in row:
        c.border = border
        if c.row > 1: c.fill = zebra[(c.row-1)%2]

# 列宽
for i in range(1,4): ws.column_dimensions[get_column_letter(i)].width = 14
wb.save("output.xlsx")
```

## 关键规则
- 数字格式:货币 `$#,##0`、百分比 `0.0%`、负数 `(123)`;设 `ws["A1"].number_format`。
- KPI 加粗 + 浅色填充;颜色语义统一(绿=正向/红=风险)。
- 公式构建:假设放独立单元格用引用(如 `=B2*$B$6`)。
- 零公式错误:保存前检查公式引用范围,避免 #REF!/#DIV/0!。
- openpyxl 索引从 1 开始(A1=row1,col1)。
- 数据清洗/批量:优先 pandas,回写用 openpyxl。

## 图表(pandas + matplotlib,Noto Sans SC)
```python
import matplotlib
matplotlib.use("Agg")
from matplotlib import font_manager
font_manager.fontManager.addfont("/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf")
import matplotlib.pyplot as plt
plt.rcParams["font.family"]="Noto Sans SC"; plt.rcParams["axes.unicode_minus"]=False
df.plot(kind="bar", x="月份", y="销售额")
plt.tight_layout(); plt.savefig("chart.png", dpi=150); plt.close()
```

## 完成后
- 重开验证公式、数值与样式;交付 `[结果文件] output.xlsx`。