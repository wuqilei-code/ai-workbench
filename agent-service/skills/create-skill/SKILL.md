---
name: create-skill
description: 用户要求创建/编写一个新技能(skill)时使用:按规范写 SKILL.md 到技能目录,使 agent 学会这项能力。
---

# 创建技能(create-skill)

为用户创建一个新的内置技能,让 agent 以后能按需调用这项能力。技能写在用户技能的
**deepagents 根目录**:`/skills/<name>/SKILL.md`(在沙箱里该虚拟路径对应
`users/<uid>/skills/<name>/SKILL.md`)。创建后立即可用(下次对话自动加载)。

## 格式与规范
目录名必须是合法 slug(小写字母/数字/连字符,如 `note-summarizer`)。

SKILL.md 必须:
1. 以 YAML frontmatter 开头,`name` 必须与**目录名一致**:
```markdown
---
name: note-summarizer
description: 用户要求总结笔记/文档要点时使用。可包含 1-2 句触发条件说明。
---
```
2. 正文:标题;概述(做什么/何时用);**具体步骤**;示例;规则/边界;必要时给参考。
3. 中文环境建议:内容用中文写,但 `description` 兼顾中英文关键词触发。

## 参考模板
```markdown
---
name: <skill-name>
description: <一句话:何时使用这项能力>
---

# <技能名>

环境说明 / 适用场景。

## 步骤
1. …  2. …  3. …

## 示例
(代码块给出可直接执行的示例,注明依赖与中文字体等环境细节)

## 规则
- 交付文件用 `[结果文件] <相对路径>` 标记。
```

## 执行流程
1. 先看 `/skills/` 下是否已存在同名技能(`browser_get_text` 无关,直接 ls/read `/skills` 目录),
   **已存在则不重复创建,说明即可**。
2. 用 `write_file` 写入 `/skills/<name>/SKILL.md`(内容按上述规范)。
3. 校验:重新 `read_file` 读回,确认 frontmatter 与 markdown 结构完整(名称与目录一致)。
4. 回复中说明技能名、用途、已保存路径。

## 参考
- 环境:沙箱 python3 已装 python-docx/pptx/openpyxl/reportlab/pdfplumber/matplotlib,pandas;
  中文字体 simhei.ttf 在 `/usr/share/fonts/truetype/simhei.ttf`。
- 若要技能创作文档/表格/PDF 等,参考技能挂在 `/skills/` 下的 `docx`/`pptx`/`xlsx`/`pdf` 技能(可 `read_file` 它们借鉴格式)。