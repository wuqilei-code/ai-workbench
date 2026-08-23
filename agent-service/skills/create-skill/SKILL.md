---
name: create-skill
description: 用户要求创建/编写/更新一个新技能(skill)、让 agent 学会一项可复用能力时使用。通过 create_skill 工具创建,自动入库可在技能页管理。
---

# 创建技能(create-skill)

为用户创建一个新的用户技能,agent 以后能按需调用这项能力。

## 必须用 create_skill 工具

技能目录 `/skills` 对文件写入工具是**只读**的——不要试图用 write_file/edit_file 直接写
`/skills/<name>/SKILL.md`,会被拒绝。唯一正确方式是调用 **create_skill** 工具(调用前需用户在界面确认):

- `name`:技能名,小写字母/数字/连字符(如 `note-summarizer`),必须与 SKILL.md frontmatter 的 name 一致
- `description`:一句话说明何时使用(中英文关键词兼顾触发)
- `content`:SKILL.md 正文(frontmatter 之后的部分;工具会自动补全 frontmatter)
- `files_json`(可选):附加文件数组 `[{"path": "scripts/run.py", "content": "..."}]`,
  路径相对技能目录,禁止 `..` 与绝对路径

## SKILL.md 正文规范

1. 标题 + 概述:做什么、何时用
2. **具体步骤**:可操作的分步指令(含命令/代码)
3. 示例:给出可直接执行的示例,注明依赖与中文字体等环境细节
4. 规则/边界:失败怎么办、输出物放哪(结果文件放 /workspace 并按对话要求汇报)

## 硬性规则

- 平台内置技能(bash/file-edit/file-search/glob-grep/read/write/create-skill 等)**不可覆盖或修改**
- 从市场安装的技能只读,不能更新;想改就让用户在技能页复制一份再改
- 创建成功后返回技能名与描述即可;新技能**下一次会话**才会被加载生效,当前会话内告知用户这一点
