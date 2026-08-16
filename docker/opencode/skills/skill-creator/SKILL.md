---
name: skill-creator
description: 当用户要求创建、添加、定义或生成一个新技能(可复用的能力包),或者说「新建/添加/写一个技能」时使用。它创建一个合规的 SKILL.md——默认是工作区技能 .opencode/skills/<name>/SKILL.md(每用户、可编辑);只有用户明确要系统级内置技能时才说明须由管理员加进镜像。覆盖 YAML frontmatter 规则、命名约束、描述长度等,确保 opencode 能通过原生 skill 工具发现并加载它。
license: MIT
compatibility: opencode
metadata:
  audience: all-users
  workflow: skill-authoring
---

# 技能创建器

帮用户创建一个 opencode 能自动发现并加载的可复用技能。

## 两种技能——先搞清楚用户要哪种

- **工作区技能(workspace skill)**:放在用户自己工作区的 `.opencode/skills/<name>/SKILL.md`。每用户独立、可 git 管理、在工作台「技能」tab 可见、随时可编辑/删除。**普通请求默认创建这种。**
- **内置全局技能(built-in global skill)**:放在容器内 `~/.config/opencode/skills/<name>/SKILL.md`,烘焙进镜像对所有用户生效(如 `skill-creator`、`document-generation`、`data-analysis`、`test-analysis`)。用户**无法**从工作台创建/编辑这种——只有管理员能,把技能加进源码仓库 `docker/opencode/skills/` 后重新构建镜像。

除非用户明确说「内置/全局/系统级技能」或「所有用户都能用」,否则创建**工作区技能**。用户要内置技能时,不要写进运行中用户配置目录(每次播种都会被模板覆盖),而是告知这需要管理员加进 `docker/opencode/skills/` 并重新部署。

## 工作区技能的位置

用户自建技能存在工作区文件区:

```
.opencode/skills/<name>/SKILL.md
```

opencode 遍历工作区(git 工作树),发现 `.opencode/` 下每个 `skills/*/SKILL.md`。一个技能 = 一个目录 + 一个 `SKILL.md`。技能按需由 `skill` 工具加载——agent 看到 name + description,相关时调用 `skill({ name: "<name>" })`。

## 创建步骤

1. 和用户确认技能 `name`,必须满足:
   - 1–64 字符
   - 仅小写字母和数字,连字符 `-` 作分隔
   - 正则 `^[a-z0-9]+(-[a-z0-9]+)*$`
   - 必须等于目录名(即 `.opencode/skills/<name>/` 里的 `<name>`)
   - 示例:`note-summarizer`、`sql-format`、`release-notes`(不要 `Note Summarizer`、`note_summarizer`、`-notes`)

2. 用 `write` 工具写 `.opencode/skills/<name>/SKILL.md`。文件必须以 `---` 包裹的 YAML frontmatter 开头,再接 markdown 正文:

   ```
   ---
   name: <name>
   description: <一句简洁的话,说明技能做什么、何时使用>
   ---

   ## 我做什么
   - <具体行为>

   ## 何时使用我
   - <触发条件>
   ```

3. frontmatter 规则:
   - `name`(必填):必须与目录名一致,规则见上。
   - `description`(必填):1–1024 字符,足够具体让 agent 能正确选择。说明做什么、何时用。用第三人称「当…时使用」,不要写「我来帮你…」。
   - `license`、`compatibility`、`metadata` 可选;未知字段被忽略。

4. 正文是技能被加载后 agent 遵循的 markdown 指引。要具体、可执行:步骤、约束、输出格式。**不要把密钥写进技能。**

5. 写完告诉用户:
   - 技能路径(`.opencode/skills/<name>/SKILL.md`)
   - 它会在工作台「技能」tab(右侧抽屉 → 工作区 → 技能)出现,可查看/编辑/删除;该 tab 也列出内置全局技能(只读)
   - 立即可用;当前会话若没出现,新开会话一定发现

## UI 替代

工作台「技能」tab 的「新建技能」按钮会创建同样的结构(目录 + 带 name/description 的 `SKILL.md`),无需 agent。用户喜欢点按钮就指过去;结果一致且之后可编辑。

## 打包脚本和其他文件(可选)

技能就是个目录——opencode 只要求 `SKILL.md`,但可以在旁边放辅助资源。agent 不会自动运行 `scripts/` 目录,它用自身的 `bash`/`read` 工具,所以正文里要显式引用这些文件。

## 技能内的长任务(异步,别阻塞对话)

如果技能的工作流可能要几分钟到几小时,让 agent 用**后台任务约定**,而不是在对话轮内轮询(会占住会话、烧上下文)。指引 agent:

1. `mkdir -p tasks`,然后后台启动:`nohup <command> > tasks/<id>.log 2>&1 & echo $! > tasks/<id>.pid`(`<id>` 须匹配 `[A-Za-z0-9._-]+`)。
2. 任务结束写 `tasks/<id>.done`:内容以 `failed` 开头=失败,其他任意内容(或空文件)=完成;pid 退出但没有 `.done` 时后端 watcher 也视为完成。下游读取方要把非 `failed` 当作就绪。
3. 写 `tasks/<id>.json`:`{ "id": "<id>", "continue": "<后续步骤指示>", "sessionId": "" }`——`continue` 告诉 agent 拿到结果后做什么。
4. 立即回复「任务已在后台启动」并结束本轮。后端 `BackgroundTaskWatcher`(约每 5s)检测完成,把结果注入对话,agent **自动继续 `continue` 里的步骤**。
5. 校验脚本只用有界命令(`bash -n`、`timeout 20 <cmd>`);**绝不在对话轮的前台跑 wait/依赖循环脚本去测试**——会一直阻塞到工具超时。这种测试走后台任务路径或给个短等待预算(如 `PIPE_DEP_TIMEOUT_S=20`)。
6. 结果被注入的后续步骤消费后,清理 `tasks/` 文件。

目录结构示例:

```
.opencode/skills/<name>/
  SKILL.md
  scripts/
    lint.sh
  references/
    schema.json
```

在 `SKILL.md` 里用相对工作区根的路径告诉 agent 怎么用,例如:

```
运行 linter:bash .opencode/skills/<name>/scripts/lint.sh <file>
校验时参考 .opencode/skills/<name>/references/schema.json。
```

只在真正有用时才加脚本/文件。纯指令的 `SKILL.md` 完全合法,也是最常见的情况。

## 规则

- 一个目录只建一个 `SKILL.md`。不要建到 `.opencode/skills/` 之外。
- 未经确认不要覆盖已有技能。
- 用户只说了模糊目标时,先给出具体的 name + description 方案,确认后再写。
- 正文保持聚焦;技能是可复用方法,不是完整应用。
