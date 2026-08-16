---
name: data-analysis
description: 当用户要求分析数据、加载数据集(xlsx/csv/json)、做统计、画图、生成数据分析报告时使用。固化了沙箱预装的 pandas/matplotlib 技术栈、中文字体绘图设置与图表输出规范,确保图表中文不显示为方块。
license: MIT
compatibility: opencode
metadata:
  audience: all-users
  workflow: data-analysis
---

# 数据分析

用沙箱**已预装**的 `pandas` / `numpy` / `matplotlib` 技术栈分析表格数据并产出图表或报告。**绝不要 `pip install`。**

## 环境事实(不可改变)

- 可直接 import 的库:`pandas`、`numpy`、`matplotlib`、`openpyxl`、`lxml`、`Pillow`、`requests`。
- **没有** `scipy`、`sklearn`、`seaborn`、`plotly`,不要 import,也不要尝试安装。
- `pip` / `ensurepip` 已被移除,装不了任何包;缺库时明确告诉用户,让管理员加进镜像。
- 中文字体用内置的 TrueType **SimHei**(`/usr/share/fonts/truetype/simhei.ttf`)。Noto CJK 的 `.ttc` 是 CFF 轮廓,matplotlib 用不了,不要注册。

## 数据读取

- `.xlsx` → `pd.read_excel(...)`;`.csv` → `pd.read_csv(...)`(注意编码,常是 `encoding="utf-8-sig"` 或 `gbk`,先用 `pd.read_csv(path, nrows=5)` 探测);`.json` → `pd.read_json(...)`。
- **先摸清结构再动手**:`print(df.shape, df.dtypes, df.columns.tolist())` + `df.head()`,不要假设列名/编码/类型。
- 缺失值显式处理:`df.isna().sum()`;删还是填要说清理由(均值/中位数/前向填充/删除),不要静默 `dropna`。
- 重复行、异常值、类型转换(`pd.to_numeric(errors="coerce")`)都要检查并说明。

## 中文字体绘图(图表出现中文时必须)

每个绘图脚本开头固定这段,否则中文变豆腐块:

```python
import matplotlib
matplotlib.use("Agg")  # 无头环境
from matplotlib import font_manager
font_manager.fontManager.addfont("/usr/share/fonts/truetype/simhei.ttf")
matplotlib.rcParams["font.sans-serif"] = ["SimHei"]
matplotlib.rcParams["axes.unicode_minus"] = False  # 负号正常显示
```

生成后要验证中文真的渲染出来了:重新打开 PNG 检查(比如用 `Pillow` 读尺寸、或 `pdftoppm` 转图看),不要只信 `savefig` 成功。

## 图表规范

- 尺寸:`fig, ax = plt.subplots(figsize=(8, 5))`,`dpi=150`,最后 `fig.tight_layout()`。
- 按问题选图型:时间序列→折线;分类对比→柱状;分布→直方图/箱线;占比→饼图(仅当类别很少);两变量关系→散点。
- 坐标轴加标签和单位,标题用用户语言;图例放不遮挡的位置。
- 保存:`fig.savefig("...png", bbox_inches="tight")`,聊天/报告用 PNG,用户要可编辑图时再给 SVG。
- 柱状图数值太多时旋转刻度(`plt.xticks(rotation=45)`);中文标签务必走上面的字体配置。

## 报告规范

- 数字四舍五入到合理精度,数量和百分比一起给(如 `1234 (12.3%)`)。
- **先直接回答用户的问题**(结论先行),再用表格/图表支撑,不要贴原始输出刷屏。
- 多张图时按逻辑顺序编号并配一句图注。
- 生成的每个文件(图/数据/报告)末尾用一行 `[结果文件] <相对路径>` 声明(见 chat.md「结果文件声明」)。
- 一次产出 **2 个及以上** 交付物时,先建 `deliverables/<主题>_<日期>/` 子目录统一存放,保持工作区根目录干净。

## 规则

- 绝不写工作区以外的路径,绝不读配置/密钥文件。
- 中间分析脚本:生成并验证最终图表/报告后删除(脚本本身就是交付物时保留)。
- 数据需要未预装的库时,告诉用户缺什么并请管理员加进镜像,不要自行安装。
