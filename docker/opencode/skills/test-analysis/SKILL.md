---
name: test-analysis
description: Use when the user uploads or points to a project's source code and asks to generate test points (测试点), test cases (测试用例), test scripts (测试脚本), or a test report (测试报告). Reads the source statically (the sandbox has no internet and cannot install project dependencies), produces a test strategy, runnable test scripts validated for syntax, and a report under deliverables/. Never pip/npm install.
license: MIT
compatibility: opencode
metadata:
  audience: all-users
  workflow: test-analysis
---

# Test Analysis (源码测试分析)

Analyze a project's **source code statically** and produce a complete testing deliverable set. The sandbox has **no internet** and cannot install the project's dependencies, so you cannot boot the app or run its real tests — work from the code.

## Environment facts

- Python 3.11 with **pytest** and **coverage** preinstalled, plus `unittest` (stdlib), `py_compile`. `mock` is **not** needed — `unittest.mock` is built-in.
- Node.js v22 + npm, with global `vite`, `typescript`, `tsx`; `node --test` and `node --check` available.
- `pip` / `ensurepip` are removed; do NOT `npm install` project deps, do NOT `pip install`. No internet.
- Never attempt to run the project itself (its dependencies are missing). Static analysis + syntax/light-weight validation only.

## Locating the source

- The project usually lives under `uploads/<project>/` after the user uploaded a zip. Find the actual code root (where `package.json` / `pyproject.toml` / `requirements.txt` / `pom.xml` / `go.mod` / `Cargo.toml` / `manage.py` / `src/` etc. live) — do not assume it is the upload root itself.
- Read, in order: dependency/manifest files, entry points, routers/controllers, models/schemas, core business logic modules, utils. Use `read`/`glob`/`grep` to map the structure.
- Keep total reading proportional to project size; for large projects focus on the modules that carry business rules, input handling, and data transforms.

## Deliverable structure

Create `deliverables/<project>_tests_<date>/` (e.g. `deliverables/myapp_tests_20260811/`) and put **all** artifacts there:

1. **`测试点清单.md`** — test-point list: for each key module/feature, the behaviors/inputs/edge cases worth testing, each with a priority (高/中/低) and the reason.
2. **`测试用例.md`** — test cases: each references a test point, gives 前置条件 / 输入 / 步骤 / 预期结果 / 优先级. Enough detail that a developer can execute them by hand or turn them into code.
3. **`测试脚本/`** — runnable test scripts, one per relevant module, in the project's real language:
   - Python: prefer **pytest** style (plain `def test_*()` + `assert`); fall back to `unittest` only if the module needs stdlib-only structure. Files like `test_<module>.py`.
   - Node/JS/TS: scripts using `node:test` (built-in) or plain assertions, runnable with the sandbox Node.
   - Each script must import the **real source files** it tests (relative imports into `uploads/<project>/...`), so it works in a real environment too.
4. **`测试报告.md`** — summary: what was analyzed, how many test points/cases/scripts, which scripts passed syntax validation, and a clear note that **full execution requires installing the project's dependencies in a networked environment** (the sandbox cannot run them).

## Validation (syntax check only, never execute business logic)

- Every generated script must pass a syntax check before you ship it:
  - Python: `python -m py_compile <file>` — must exit 0.
  - Node/JS: `node --check <file>` — must exit 0.
  - TS: `npx tsc --noEmit <file>` (or `tsx`/`node --experimental-strip-types` if available) — best effort.
- Fix any syntax errors in the generated scripts until they all pass. Run all checks with a bounded command (`timeout 30 ...`), never a blocking loop.
- For **pure functions** that have no external deps, you MAY run a minimal smoke test with the sandbox's own runtime (e.g. `python3 -m pytest -x test_<module>.py` on a single stdlib-only test file, or `node --test test_<module>.mjs`). If anything imports missing deps, skip execution and mark it "需本地环境执行" — do not try to install.
- Record pass/fail per script in 测试报告.md.

## Reporting

- End your reply with a concise summary: project language/framework detected, counts (test points / cases / scripts), syntax-validation results, and the explicit caveat that real execution needs the project's dependencies installed.
- Declare each produced file with `[结果文件] deliverables/<project>_tests_<date>/...` (one per file) so the UI renders download cards.
- Keep intermediate analysis notes out of the workspace; only ship the four deliverables above.

## Rules

- Never `pip install` / `npm install` / download anything. Never run the project. Never write outside the workspace.
- Do not read config/secret files (`.env`, keys, credentials) — if the project ships them, note their existence in the report but never print contents.
- If the project uses a framework you are unsure about, describe what you can infer from the code and be explicit about assumptions.
- Large projects: prioritize the modules with the most business logic; state in the report what was covered and what was skipped.
