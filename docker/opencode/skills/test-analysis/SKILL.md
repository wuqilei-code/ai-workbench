---
name: test-analysis
description: 当用户上传或指定项目源码,要求生成测试点(测试点)、测试用例(测试用例)、测试脚本(测试脚本)、测试报告(测试报告),或对某个运行中的服务做接口测试时使用。用户提供服务地址时对真实服务发起 HTTP 接口测试;否则做源码静态分析并产出通过语法校验的可运行测试脚本。产出 测试用例、每个用例的测试情况、测试总结报告、指标分析 到 deliverables/。绝不 pip/npm install。
license: MIT
compatibility: opencode
metadata:
  audience: all-users
  workflow: test-analysis
---

# 源码测试分析

分析项目**源码**,产出完整的测试交付物。两种模式:

- **接口测试模式(推荐)**:用户提供了服务地址(`http://host:port/...`)——结合源码设计并对**运行中的服务**发起真实 HTTP 接口测试。
- **静态分析模式**:没给地址——分析代码,生成可运行的测试脚本(通过语法校验),并注明完整执行需要项目自身的依赖。

## 环境事实(沙箱真实依赖清单)

**只从下面列出的依赖里选;未列出的都不可用**,不要 import/require 未列出的包,也不要尝试安装。

### Python(3.11;`pip`/`ensurepip` 已移除,不可安装)

**HTTP/网络(接口测试首选)**:`requests`、`httpx`、`aiohttp`、`urllib`(内置)、`socket`(内置)

**测试/断言**:`pytest`、`coverage`、`unittest`/`unittest.mock`(内置)、`py_compile`(内置)

**数据处理**:`pandas`、`numpy`、`openpyxl`、`xlsxwriter`

**文档处理**:`python-docx`、`python-pptx`、`reportlab`、`PyPDF2`、`pdfplumber`、`pdfminer.six`、`pypdfium2`、`markdown`、`Pillow`(PIL)

**网页解析**:`beautifulsoup4`(bs4)、`lxml`、`soupsieve`、`html.parser`(内置)

**序列化**:`json`(内置)、`csv`(内置)、`typing_extensions`

**图表**:`matplotlib`

**其他可用**:`attrs`、`anyio`、`idna`、`charset-normalizer`、`urllib3`、`h11`、`httpcore`、`multidict`、`yarl`、`frozenlist`、`aiosignal`、`aiohappyeyeballs`、`propcache`、`certifi`、`cffi`、`cryptography`、`pycparser`、`six`、`python-dateutil`、`pyparsing`、`contourpy`、`cycler`、`fonttools`、`kiwisolver`、`et-xmlfile`、`iniconfig`、`pluggy`、`pygments`
内置模块:`json`、`csv`、`re`、`datetime`、`pathlib`、`subprocess`、`tempfile`、`io`、`sqlite3`、`collections`、`os`、`sys`、`unittest`

**⚠️ 不可用(常见但未安装/已移除)**:`flask`、`fastapi`、`uvicorn`、`starlette`、`werkzeug`、`jinja2`、`pydantic`、`pytest-mock`、`pytest-asyncio`、`pytest-cov`、`selenium`、`scrapy`、`scipy`、`sklearn`、`playwright`、`requests-mock`、`responses`、`fastapi.testclient`。**沙箱不能起 Web 服务,也不要用 TestClient——接口测试直接用 `requests`/`httpx` 打真实地址。**

### Node.js(v22.14.0)

**全局包**:`vite`、`typescript`(tsc)、`tsx`、`npm`、`corepack`、`@benborla29/mcp-server-mysql`。
**内置模块**:`node:test`、`node:assert`、`node:http`、`node:https`、`node:fs`、`node:path`、`node:child_process`、`node:url`、`node:crypto`、`node:process`。
**⚠️ 不可用**:`jest`、`mocha`、`vitest`、`supertest`、`axios`(测试优先用 `node:test` + 内置 fetch/`node:http` 或 Python `requests`)。

### 沙箱能力

- 可访问局域网/宿主服务(按 IP,如 `http://192.168.x.x:port`);本会话用户自己启动的服务可用 `http://127.0.0.1:<port>`。
- `pip`/`ensurepip`/`npm install` 均不可用,不要安装任何依赖。
- **不能在沙箱里起常驻 Web 服务**(起服务会导致 bash 卡死),接口测试只打用户给的地址。

## 定位源码

- 项目通常在上传 zip 解压后的 `uploads/<project>/` 下。找到真正的代码根目录(有 `package.json` / `pyproject.toml` / `requirements.txt` / `pom.xml` / `go.mod` / `Cargo.toml` / `manage.py` / `src/` 的地方),不要假设就是上传根目录本身。
- 按顺序读:依赖清单 → 入口 → 路由/控制器 → 模型/schema → 核心业务逻辑 → 工具函数。用 `read`/`glob`/`grep` 摸清结构。
- 阅读量随项目规模收敛;大项目聚焦承载业务规则、输入处理、数据转换的模块。

## 接口测试模式(用户提供了服务地址时)

1. **确认可达**:先 `curl -s -o /dev/null -w '%{http_code}' <url>`(或 requests)确认在线;不可达则在报告里说明并回退静态模式。
2. **从源码推导接口清单**:结合源码的路由/控制器,列出预期端点、方法、参数、鉴权要求。
3. **设计接口用例**:为关键端点设计用例(正常 / 边界 / 非法输入 / 鉴权失败 / 404 等),含输入、预期状态码与响应结构。
4. **实际执行**:用 `requests` 发真实请求(每个用例一个 `test_*` 函数、`pytest` 组织),记录实际状态码、响应体、耗时。
   - 需鉴权的服务:从源码找鉴权方式;需要 token 而用户没给时,尝试源码里的默认/测试凭据,并在报告说明。
   - 写操作(POST/PUT/DELETE):默认**不实际写**,或仅在明确安全时用测试数据,并在报告注明,避免污染数据。
5. **记录每个用例结果**:通过/失败/阻塞 + 实际响应摘要。

## 交付结构

建 `deliverables/<project>_tests_<date>/`(如 `deliverables/myapp_tests_20260811/`),全部产物放进去:

1. **`测试用例.md`** — 用例清单:每条引用测试点,给 前置条件 / 输入 / 步骤 / 预期结果 / 优先级;接口模式下含端点、方法、请求示例、预期状态码与响应。
2. **`测试脚本/`** — 可运行脚本,按模块/端点拆分:
   - 接口模式:Python `requests` + `pytest`,每个用例一个 `test_*` 函数,带 base URL 配置。
   - 静态模式:Python `pytest`/`unittest`(或 Node `node:test`),import 真实源码做纯函数测试。
   - **只用 Environment facts 列出的预装依赖**;源码依赖了未预装的库时,用 `unittest.mock` 打桩或只测能 import 的模块,并在报告说明该依赖缺失;绝不 `pip install`/`npm install`。
   - 每个脚本交付前必须过语法校验(`python -m py_compile`、`node --check`)。
3. **`测试情况.md`** — 每个用例的执行情况:用例 ID、描述、实际结果(通过/失败/阻塞)、实际状态码/响应摘要、失败原因。接口模式是真实执行记录;静态模式写「语法校验通过,需本地环境执行」。
4. **`测试总结报告.md`** — 总结:分析了什么、用例总数、通过/失败/跳过数、关键发现、风险点、明确哪些需真实环境执行。
5. **`指标分析.md`** — 指标:
   - 接口模式:接口总数、覆盖率(实测/预期端点)、用例通过率、平均/最大/最小响应时间、失败接口清单、潜在性能与健壮性问题。
   - 静态模式:模块数、估算测试点、复杂度较高的函数/模块、建议补测方向。
   - 可用 `coverage`(对能 import 的纯函数)统计行覆盖率并附数据。

## 校验

- 每个脚本必须过语法校验:
  - Python:`python -m py_compile <file>` — exit 0。
  - Node/JS:`node --check <file>` — exit 0。
  - TS:尽力 `npx tsc --noEmit <file>`。
- 接口模式下实际运行 pytest 脚本打服务;失败用例要区分「用例断言失败」和「服务/环境不可用」。**运行命令必须加超时**(`timeout 60 python3 -m pytest -x ...`),绝不用会阻塞的循环。

## 报告

- 结尾给简明总结:项目语言/框架、所用模式(接口/静态)、数量(测试点/用例/通过/失败)、关键发现;需联网/依赖环境才能执行的显式注明。
- 每个产出文件一行 `[结果文件] deliverables/<project>_tests_<date>/...` 声明,UI 渲染下载卡片。
- 中间分析笔记不要留在工作区,只交付上面的产物。

## 规则

- 绝不 `pip install` / `npm install` / 下载任何东西;绝不写工作区之外;**绝不在沙箱起常驻 Web 服务**。
- 不读配置/密钥文件(`.env`、key、凭据);项目里带了这些只提一句存在,绝不打印内容。
- 接口写操作默认只测 GET;POST/PUT/DELETE 谨慎,优先测试数据并在报告说明。
- 框架拿不准时,描述能从代码推断出的内容,显式说明假设。
- 大项目:优先覆盖业务逻辑最多的模块/端点;报告里说明覆盖了什么、跳过了什么。
