---
name: test-analysis
description: Use when the user uploads or points to a project's source code and asks to generate test points (测试点), test cases (测试用例), test scripts (测试脚本), a test report (测试报告), or to test a running service by API. If the user provides a service URL, perform real HTTP API tests against it (requests preinstalled); otherwise do static source analysis and produce runnable test scripts validated for syntax. Produces 测试用例、每个用例的测试情况、测试总结报告、指标分析 under deliverables/. Never pip/npm install.
license: MIT
compatibility: opencode
metadata:
  audience: all-users
  workflow: test-analysis
---

# Test Analysis (源码测试分析)

Analyze a project's **source code** and produce a complete testing deliverable set. Two modes:

- **API 测试模式(推荐)**:user provides a service URL (`http://host:port/...`) — combine it with the source to design and run **real HTTP API tests** against the running service.
- **静态分析模式**:no URL given — analyze the code, generate runnable test scripts (syntax-validated), and note that full execution needs the project's deps.

## Environment facts (沙箱实际依赖清单)

**只在下面列出的依赖里选;未列出的任何库都不可用**,不要 `import`/`require` 未列出的包,也不要尝试安装。

### Python(版本 3.11,`pip`/`ensurepip` 已移除,不可安装)

**HTTP/网络(API 测试首选)**:
- `requests`、`httpx`、`aiohttp`、`urllib`(内置)、`socket`(内置)

**Web 框架(用于读源码理解服务,或起本地 mock)**:
- `flask`、`fastapi`、`uvicorn`、`starlette`、`werkzeug`、`jinja2`、`markupsafe`、`itsdangerous`、`blinker`、`click`

**测试/断言**:
- `pytest`、`coverage`、`unittest`/`unittest.mock`(内置)、`py_compile`(内置)

**数据处理**:
- `pandas`、`numpy`、`openpyxl`、`xlsxwriter`、`et_xmlfile`、`python-dateutil`、`six`

**文档处理**:
- `python-docx`、`python-pptx`、`reportlab`、`PyPDF2`、`pdfplumber`、`pdfminer.six`、`pypdfium2`、`markdown`、`Pillow`(PIL)

**网页解析**:
- `beautifulsoup4`(bs4)、`lxml`、`soupsieve`、`html.parser`(内置)

**数据校验/序列化**:
- `pydantic`、`pydantic_core`、`typing_extensions`、`json`(内置)、`csv`(内置)

**图表**:
- `matplotlib`、`contourpy`、`cycler`、`fonttools`、`kiwisolver`、`pyparsing`、`packaging`

**加密/证书**:
- `cryptography`、`cffi`、`pycparser`、`certifi`

**其他可用**:
- `attrs`、`anyio`、`idna`、`charset-normalizer`、`urllib3`、`h11`、`httpcore`、`multidict`、`yarl`、`frozenlist`、`aiosignal`、`aiohappyeyeballs`、`propcache`
- 内置: `json`、`csv`、`re`、`datetime`、`pathlib`、`subprocess`、`tempfile`、`io`、`sqlite3`、`datetime`、`collections`、`os`、`sys`

**⚠️ 不可用(常见但未安装)**:`pytest-mock`、`pytest-asyncio`、`pytest-cov`、`selenium`、`scrapy`、`scipy`、`sklearn`、`playwright`、`requests-mock`、`responses`、`fastapi.testclient`(依赖的 httpx 有,但 `TestClient` 需 starlette 测试依赖,尽量直接用 requests 打真实接口)。

### Node.js(v22.14.0)

**全局包**:`vite`、`typescript`(tsc)、`tsx`、`npm`、`corepack`、`@benborla29/mcp-server-mysql`。
**内置模块**:`node:test`、`node:assert`、`node:http`、`node:https`、`node:fs`、`node:path`、`node:child_process`、`node:url`、`node:crypto`、`node:process`。

**⚠️ 不可用**:`jest`、`mocha`、`vitest`、`supertest`、`axios`(未全局装;测试优先用 `node:test` + `node:http`/内置 fetch 或 Python requests)。

### 沙箱能力

- 可访问**局域网/宿主服务**(按 IP,如 `http://192.168.x.x:port`);本会话用户启动的服务可用 `http://127.0.0.1:<port>`。
- `pip`/`ensurepip`/`npm install` 均不可用;不要尝试安装任何依赖。

## Locating the source

- The project usually lives under `uploads/<project>/` after the user uploaded a zip. Find the actual code root (where `package.json` / `pyproject.toml` / `requirements.txt` / `pom.xml` / `go.mod` / `Cargo.toml` / `manage.py` / `src/` etc. live) — do not assume it is the upload root itself.
- Read, in order: dependency/manifest files, entry points, routers/controllers, models/schemas, core business logic modules, utils. Use `read`/`glob`/`grep` to map the structure.
- Keep total reading proportional to project size; for large projects focus on the modules that carry business rules, input handling, and data transforms.

## API 测试模式(用户提供服务地址时)

When the user provides a service URL (e.g. `服务地址: http://192.168.1.20:8080`):

1. **确认可达性**:先 `curl -s -o /dev/null -w '%{http_code}' <url>`(或 requests)确认服务在线;不可达则在报告里说明并回退静态模式。
2. **从源码推导 API 清单**:结合源码中的路由/控制器/接口定义,列出预期存在的端点、方法、参数、鉴权要求。
3. **设计 API 测试用例**:为每个关键端点设计用例(正常路径 / 边界 / 非法输入 / 鉴权失败 / 404 等),含输入、预期状态码与响应结构。
4. **实际执行**:用 **requests** 发起真实 HTTP 请求测试(每个用例一个测试函数,pytest 组织),记录实际状态码、响应体、耗时。
   - 需要鉴权的服务:从源码找鉴权方式;若需 token 且用户没提供,尝试源码中的默认/测试凭据,并在报告说明。
   - 对写操作(POST/PUT/DELETE):默认**不实际写入**,或仅在明确安全时用测试数据,并在报告注明避免污染数据。
5. **记录每个用例的结果**:通过/失败/阻塞 + 实际响应摘要。

## Deliverable structure

Create `deliverables/<project>_tests_<date>/` (e.g. `deliverables/myapp_tests_20260811/`) and put **all** artifacts there:

1. **`测试用例.md`** — test cases: each references a test point, gives 前置条件 / 输入 / 步骤 / 预期结果 / 优先级. API 模式下含:端点、方法、请求示例、预期状态码与响应。
2. **`测试脚本/`** — runnable test scripts, one per relevant module/endpoint:
   - **API 模式**:Python `requests` + `pytest` scripts,每个用例一个 `test_*` 函数,带 base URL 配置。
   - **静态模式**:Python `pytest`/`unittest`(或 Node `node:test`),import 真实源码做纯函数测试。
   - **只使用 Environment facts 列出的预装依赖**。若源码依赖了未预装的库(如 `scipy`/`sklearn`/`selenium`),测试脚本用 `unittest.mock` 打桩或仅测能 import 的模块,并在报告中说明该依赖缺失;绝不尝试 `pip install`/`npm install`。
   - Each script must pass syntax check (`python -m py_compile`, `node --check`) before shipping.
3. **`测试情况.md`(每个用例的测试情况)** — per-case run results:用例 ID、用例描述、实际执行结果(通过/失败/阻塞)、实际状态码/响应摘要、失败原因。API 模式下为真实执行记录;静态模式下为"语法校验通过,需本地环境执行"。
4. **`测试总结报告.md`** — 总结:分析了什么、用例总数、通过/失败/跳过数、关键发现、风险点、明确说明哪些需真实环境执行。
5. **`指标分析.md`** — 指标分析:
   - **API 模式**:接口总数、覆盖率(实测/预期端点)、用例通过率、平均/最大/最小响应时间、失败接口清单、潜在性能与健壮性问题。
   - **静态模式**:模块数、估算测试点、复杂度较高函数/模块、建议补充测试的方向。
   - 可用 `coverage`(对能 import 的纯函数)统计行覆盖率并附数据。

## Validation

- Every generated script must pass a syntax check:
  - Python: `python -m py_compile <file>` — exit 0.
  - Node/JS: `node --check <file>` — exit 0.
  - TS: best effort `npx tsc --noEmit <file>`.
- API 模式下,实际运行 pytest 脚本并对服务发起请求;失败用例要区分"用例断言失败"与"服务/环境不可用"。Run with a bounded command (`timeout 60 python3 -m pytest -x ...`), never a blocking loop.

## Reporting

- End your reply with a concise summary: project language/framework, mode used (API/静态), counts (test points / cases / passed / failed), key findings, and the explicit caveat if anything needs a networked/dependent environment.
- Declare each produced file with `[结果文件] deliverables/<project>_tests_<date>/...` (one per file) so the UI renders download cards.
- Keep intermediate analysis notes out of the workspace; only ship the deliverables above.

## Rules

- Never `pip install` / `npm install` / download anything. Never write outside the workspace.
- Do not read config/secret files (`.env`, keys, credentials) — if the project ships them, note their existence in the report but never print contents.
- API 写操作:默认只读/只测 GET;对 POST/PUT/DELETE 谨慎,优先用测试数据并在报告说明,避免污染生产数据。
- If the project uses a framework you are unsure about, describe what you can infer from the code and be explicit about assumptions.
- Large projects: prioritize the modules/endpoints with the most business logic; state in the report what was covered and what was skipped.
