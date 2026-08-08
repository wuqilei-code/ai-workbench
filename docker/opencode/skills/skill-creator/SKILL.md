---
name: skill-creator
description: Use this skill when the user asks to create, add, define, or generate a new skill (a reusable capability package), or says "新建/添加/写一个技能". It creates a compliant SKILL.md — workspace skill at .opencode/skills/<name>/SKILL.md (per-user, user-editable) or, only when the user explicitly asks for a system-wide built-in skill, tells them it must be handled by the admin and added to the image. Covers YAML frontmatter rules, naming constraints, description length, and length limits so opencode discovers and loads it via the native skill tool.
license: MIT
compatibility: opencode
metadata:
  audience: all-users
  workflow: skill-authoring
---

# Skill Creator

Help the user create a new reusable skill that opencode discovers automatically.

## Two kinds of skills — know which one the user wants

- **工作区技能(workspace skill)**: lives in the user's own workspace at `.opencode/skills/<name>/SKILL.md`. Per-user, git-tracked, visible in the workbench's **「技能」tab**, user-editable/deletable anytime. **This is the default for normal requests.**
- **内置全局技能(built-in global skill)**: lives at `~/.config/opencode/skills/<name>/SKILL.md` in the container, baked into the image for every user (e.g. `skill-creator`, `document-generation`, `data-analysis`). The user **cannot** create or edit these from the workbench — only the admin can, by adding them to `docker/opencode/skills/` in the source repo and redeploying the image.

Unless the user explicitly says "内置/全局/系统级技能" or "要所有用户都能用", create a **workspace skill** at `.opencode/skills/<name>/SKILL.md`. If they ask for a built-in one, do not write into `~/.config/opencode/skills/` (the running user's config copy is overwritten by the template on every seed anyway); instead tell the user this requires the admin to add it to `docker/opencode/skills/` and redeploy.

## Where workspace skills live

User-created skills are stored **in the workspace file area** at:

```
.opencode/skills/<name>/SKILL.md
```

opencode walks the workspace (a git worktree) and discovers every `skills/*/SKILL.md` under `.opencode/`. Each skill is one folder containing exactly one `SKILL.md`. Skills are loaded on-demand by the `skill` tool — the agent sees the name + description and calls `skill({ name: "<name>" })` when relevant.

## How to create a skill

1. Agree on a skill `name` with the user. The name MUST satisfy:
   - 1–64 characters
   - lowercase letters and digits only, single hyphens allowed as separators
   - regex: `^[a-z0-9]+(-[a-z0-9]+)*$`
   - must equal the folder name (the `<name>` in `.opencode/skills/<name>/`)
   - examples: `note-summarizer`, `sql-format`, `release-notes` (NOT `Note Summarizer`, `note_summarizer`, `-notes`)

2. Write the file `.opencode/skills/<name>/SKILL.md` using the `write` tool. The file MUST start with YAML frontmatter delimited by `---` lines, followed by the markdown body:

```
---
name: <name>
description: <one concise sentence describing what the skill does and when to use it>
---

## What I do
- <concrete behavior>

## When to use me
- <trigger conditions>
```

3. Frontmatter rules:
   - `name` (required): must match the folder name, see rules above.
   - `description` (required): 1–1024 characters. Keep it specific enough that the agent can choose correctly. Mention what it does and when to use it. Third person: "Use when ...", not "I help with ...".
   - `license`, `compatibility`, `metadata` are optional; unknown fields are ignored.

4. The body is markdown guidance the agent follows when the skill is loaded. Be concrete and actionable: steps, constraints, output format. Do not put secrets in a skill.

5. After writing, tell the user:
   - the skill path (`.opencode/skills/<name>/SKILL.md`)
   - that it appears in the workbench **「技能」tab** (right drawer → 工作区 → 技能), where they can view/edit/delete it — the tab also lists built-in global skills read-only
   - that it is available immediately; if it does not appear in the current session, starting a new session guarantees discovery

## UI alternative

The workbench **「技能」tab → 「新建技能」** button creates the same structure (directory + `SKILL.md` with `name`/`description` frontmatter) without the agent. If the user prefers clicking, point them there; the result is identical and fully editable afterwards.

## Rules

- Create exactly one `SKILL.md` per folder. Do not create skills outside `.opencode/skills/`.
- Never overwrite an existing skill without confirming with the user.
- If the user only describes a vague goal, propose a concrete name + description and confirm before writing.
- Keep the body focused; a skill is a reusable method, not a full application.

## Bundling scripts and other files (optional)

A skill is just a folder — opencode only requires `SKILL.md`, but you MAY add helper resources alongside it. The agent does not auto-run any `scripts/` directory; it uses its own `bash`/`read` tools, so you must reference files explicitly in the body.

## Long-running tasks inside a skill (async, do not block the turn)

If a skill's workflow can take a long time (minutes to hours), instruct the agent to use the **background-task convention** instead of blocking the conversation turn with in-turn polling (which holds the session busy and burns context). Tell the agent:

1. `mkdir -p tasks`, then launch detached: `nohup <command> > tasks/<id>.log 2>&1 & echo $! > tasks/<id>.pid` (`<id>` must match `[A-Za-z0-9._-]+`).
2. The job must write `tasks/<id>.done` when it finishes: content starting with `failed` means failure, any other content (or an empty file) means done; an exited pid without `.done` is also treated as done by the backend watcher. Downstream readers must treat non-`failed` as ready.
3. Write `tasks/<id>.json`: `{ "id": "<id>", "continue": "<后续步骤指示>", "sessionId": "" }` — `continue` tells the agent what to do next with the result.
4. Reply "任务已在后台启动" immediately and end the turn. The backend `BackgroundTaskWatcher` (every ~5s) detects completion and injects the result into the conversation, so the agent **automatically continues the `continue` steps**.
5. Validate scripts with bounded commands only (`bash -n`, `timeout 20 <cmd>`); **never** run a wait/dependency-loop script in the foreground of a turn to test it — that blocks the turn until the tool timeout. Run such tests through the background-task path or with a short wait budget (e.g. `PIPE_DEP_TIMEOUT_S=20`).
6. Clean up the `tasks/` files after the continuation has consumed the result.

Layout example:

```
.opencode/skills/<name>/
  SKILL.md
  scripts/
    lint.sh
  references/
    schema.json
```

In `SKILL.md`, tell the agent exactly how to use them with paths relative to the workspace root, e.g.:

```
Run the linter with: bash .opencode/skills/<name>/scripts/lint.sh <file>
Refer to the schema at .opencode/skills/<name>/references/schema.json when validating.
```

Only add scripts/files when they genuinely help. A pure-instruction `SKILL.md` is perfectly valid and is the common case.
