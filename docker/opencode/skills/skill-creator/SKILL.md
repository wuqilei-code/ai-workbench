---
name: skill-creator
description: Use this skill when the user asks to create, add, define, or generate a new skill (a reusable capability package). It guides creating a compliant SKILL.md in the workspace at .opencode/skills/<name>/SKILL.md with correct YAML frontmatter, naming rules, and length limits so opencode can discover and load it via the native skill tool.
license: MIT
compatibility: opencode
metadata:
  audience: all-users
  workflow: skill-authoring
---

# Skill Creator

Help the user create a new reusable skill that opencode discovers automatically.

## Where skills live

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
   - `description` (required): 1–1024 characters. Keep it specific enough that the agent can choose correctly. Mention what it does and when to use it.
   - `license`, `compatibility`, `metadata` are optional; unknown fields are ignored.

4. The body is markdown guidance the agent follows when the skill is loaded. Be concrete and actionable: steps, constraints, output format. Do not put secrets in a skill.

5. After writing, tell the user:
   - the skill path (`.opencode/skills/<name>/SKILL.md`)
   - that it appears in the workspace **「工作区 → 技能」** panel where they can view/edit/delete it
   - that it is available immediately; if it does not appear in the current session, starting a new session guarantees discovery

## Rules

- Create exactly one `SKILL.md` per folder. Do not create skills outside `.opencode/skills/`.
- Never overwrite an existing skill without confirming with the user.
- If the user only describes a vague goal, propose a concrete name + description and confirm before writing.
- Keep the body focused; a skill is a reusable method, not a full application.

## Bundling scripts and other files (optional)

A skill is just a folder — opencode only requires `SKILL.md`, but you MAY add helper resources alongside it. The agent does not auto-run any `scripts/` directory; it uses its own `bash`/`read` tools, so you must reference files explicitly in the body.

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
