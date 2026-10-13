# AI Workbench · 总目标与整改清单

把本项目做成类似 Trae 的 AI 工作台:对话 + 智能体编排 + 浏览器接管 + 动态 UI 可视化 + 技能体系一体化。

图例:优先级 `P0`(最高) / `P1` / `P2`;状态 `[ ]` 待办 / `[~]` 进行中 / `[x]` 已完成。

> 本清单由一次全仓审计(A→G)生成,按项逐条整改,完成即勾选并注明改动位置。

---

## A. 工程质量

- [x] `P0` A1 自动化测试为零。→ 已加:后端 `JwtTokenProviderTest` + `JobTaskServiceVarsTest` + `InternalApiFilterTest` + `SessionEventHubTest` + `SecretRedactorTest`(**24 例过**:令牌 + 任务变量 `${key}` 替换 + 内部接口鉴权/CIDR + SSE 跨副本中继 + 敏感脱敏)、前端 `preview/ext.test.ts` + `i18n/i18n.test.ts`(vitest,**6 例过**:新增用例遍历 zh/en 全部文案并调用 `t()` 强制编译,可拦截 `Invalid linked format` 之类 i18n 语法错误)、agent-service `tests/test_workspace.py` + `tests/test_net_guard.py` + `tests/test_inbound_guard.py` + `tests/test_metrics.py` + `tests/test_sandbox_cap.py`(pytest);均已接入 CI。
- [x] `P0` A2 无 CI/CD。→ 已加 `.github/workflows/ci.yml`:后端 `mvn clean package`、前端 `npm ci && typecheck && test && build`、agent-service `pytest` + 编译导入冒烟。
- [x] `P0` A3 前端缺 lint/typecheck 脚本(`vue-tsc` 已装未用)。→ 已加 `npm run typecheck`;并修复 `unwrap` 泛型 + 24 处既有类型错误,`vue-tsc --noEmit` 现为 **0 error**,已纳入 CI 阻断。
- [x] `P0` A4 数据库无迁移框架。→ 已引入 **Flyway**(`flyway-core`/`flyway-mysql`):`baseline-on-migrate=true` + `baseline-version=1` + `locations=classpath:db/migration`;现状由 schema.sql 建成后打基线 V1,已应用占位 `V2__noop.sql`。实测日志 `Successfully baselined ... version: 1` → `Successfully applied 1 migration ... now at version v2`;`flyway_schema_history` 有 BASELINE(1)+SQL(2, success=1)。**约定:后续结构变更一律新增 `db/migration/Vn__xxx.sql`,不改已应用迁移。**
- [ ] `P1` A5 工作区大量未提交改动(54 项)与 `TASKS.md` 待办空置导致的追踪缺失。→ 分批提交,保持清单实时。
- [x] `P1` A6 文档滞后(`README.md` 仍以 opencode 为内核、compose MySQL 描述过时);无接口文档。→ **README 重写**为当前架构(deepagents 单轨 + agent-service + AIO 沙箱 + 跨副本中继)、技术栈、快速开始、本地开发、测试/CI、API 文档与 env 变量表。**引入 springdoc-openapi**(`io.swagger.v3` 2.6.0):`OpenApiConfig`(info + Bearer JWT scheme),`/v3/api-docs` + `/swagger-ui/index.html`;`OPENAPI_ENABLED`(默认 true)开关,生产可关为"需登录"。nginx 补 `/v3/api-docs`、`/swagger-ui/` 反代(原被 SPA fallback 截走返回 index.html)。实测:经 8080 访问返回真实 spec(约 55KB,含 openapi 字段)、swagger-ui 200。**注意:开启时文档路径为公开(仅 schema,无凭据)**。
- [~] `P2` A7 依赖版本未全锁、无依赖审计(SBOM)。→ 已加 **Dependabot**(`.github/dependabot.yml`:npm/maven/pip/docker/github-actions 周更分组 PR)与 CI **`audit` job**(`npm audit --audit-level=high` + `pip-audit -r requirements.txt`,仅提示不阻断)。前端 `package-lock.json` 已锁定;后端 Maven 由 parent BOM 管理。**剩余:Python `requirements.txt` 未生成锁文件(pip-compile/pip-tools 需真实解析环境),SBOM 未生成。**

## B. 安全

- [x] `P0` B1 密钥:核对 `docker/.env` **已被 .gitignore 忽略且从未提交**(git 历史为空),无需从历史清除。→ 仍需生产轮换密钥(填入随机值);见 B1 备注。
- [x] `P0` B2 `/api/internal/**` 全放行,仅靠 `X-Internal-Token`。→ 新增 `security/InternalApiFilter` 统一鉴权(注册进安全过滤链,覆盖所有内部端点,避免逐个 controller 遗漏):`X-Internal-Token` 常量时间比对,**未配置令牌一律拒绝(fail-closed)**;并支持 `app.internal-trusted-cidrs`(IPv4/CIDR 白名单)按来源收紧(默认空=不限来源)。实测:无/错令牌 403、正确令牌通过(空体 400)、正常接口不受影响。**注意:经容器内 nginx 转发时 remote=127.0.0.1**,配置白名单需含 `127.0.0.1`(或 agent-service 所在网段)。
- [x] `P0` B3 CORS `allowedOriginPatterns("*")`。→ 改为可配置白名单 `app.cors.allowed-origins`;并已修正实现:未配置时 Security 的 CorsConfigurationSource **返回 null(不启用 CORS 处理)**——否则会把同源 POST(浏览器会带 Origin 头)误判为非法跨域而返回 403(曾导致「全部已读」等所有浏览器 POST 403)。同时移除 `WebConfig` 的 MVC `*` CORS,统一由安全层处理。实测:同源 POST 200、跨域无 ACAO 头。
- [x] `P0` B4 app 容器挂载 docker.sock 的安全性。→ 已给 **app 服务加 `security_opt: no-new-privileges:true`**(dev+prod,实测 `HostConfig.SecurityOpt` 生效,容器健康);docker.sock 需保留以管理每用户沙箱容器——彻底消除该面需改为「受限容器管理边车/API」,属部署方决策(见备注)。
- [x] `P0` B5 账号体系缺改密失效/角色即时生效/登录限流。→ 已实现 `users.token_version`(登录签发进 JWT,过滤器比对;改密 +1 使旧 token 立即失效,实测 401);`JwtAuthFilter` 角色改为以库为准(改角色即时生效);登录失败锁定(5 次/10 分钟)原已存在。
- [ ] `P2` B5b 邮箱验证 / 密码自助重置 / MFA / 刷新令牌与主动登出。→ 后续迭代。
- [x] `P1` B6 出站请求(MCP/OpenAPI/manual 工具、市场安装)SSRF 面。→ 新增 `agent-service/app/net_guard.py`:默认禁止解析到内网/回环/链路本地/保留地址的 http(s) 目标;`AGENT_OUTBOUND_ALLOWLIST`(主机白名单)或 `AGENT_ALLOW_PRIVATE_OUTBOUND`(内网私有部署放行)可覆盖。已在 MCP 远程连接、OpenAPI 文档抓取/调用、manual 接口调用处接入;实测拦截 127.0.0.1/169.254.169.254/192.168/10.x 与 file://。市场安装为服务端内部读写,后续如加远程拉取再接入。
- [x] `P1` B7 agent 命令黑名单为启发式,易绕过。→ `AGENT_HITL` 代码默认改为 **true**(fail-safe,compose 亦为 true);`_DANGEROUS_LEAD_TOKENS` 扩充系统/服务/挂载/定时/内核模块/网络外联等命令;复合命令与命令替换始终要求确认。
- [x] `P2` B8 审计/敏感日志脱敏核查。→ 核查:32 处 `@AuditLog` 的 `targetSpel` 只取 `#id`(不含请求体),全仓无打印密码/密钥/令牌值的日志(仅 `reset password`/`API key(s) encrypted` 等计数型文案)。新增 `common/audit/SecretRedactor`(掩码 `Bearer xxx`/`sk-…`/`password=…`/`apiKey:…` 等)并在 `AuditLogger` 对入库的 `target`/`errorMsg` 脱敏+截断(审计表可在管理端查看,防二次泄露;服务端排障日志不脱敏)。新增 `SecretRedactorTest`(5 例,含"不误伤 token_version"用例)。

## C. 后端

- [x] `P1` C1 多个列表接口无分页。→ 已给**会话/归档/收藏/jobs**加可选 `limit/offset`(默认全量兼容旧行为,上限 500/1000),**通知**加 `offset`;前端会话列表改为**每页 200 条 + 侧边栏「加载更多」**渐进加载。全部实测 200。
- [x] `P0` C2 大文件全量读入内存(`FileController` content/download 无 Range/流)。→ 下载/raw 已支持 HTTP `Range`(206 + Content-Range + Accept-Ranges,BoundedInputStream 按需读取);已实测 `bytes=5-9` 返回 206 与正确片段。
- [x] `P0` C3 无法水平扩展:`SessionEventHub` 进程内、`JobScheduler/JobQueueDispatcher` 无分布式锁。→ 已加 `DbLockService`(基于 `app_lock` 表 + TTL 的轻量分布式锁):两个定时 sweep 均先抢锁,多副本时仅一个实例执行(实测两把锁被同一实例持有)。**`SessionEventHub` 跨实例广播已实现**:新增 `session_event` 出站表(迁移 `V4`)+ 各实例 `dispatch` 写行(带 `origin`=实例 id)、`relay()` 按 `seq` 增量轮询并把**非本实例**事件投递给本地订阅者(无订阅者的事件丢弃,刷新后由消息接口重建),`cleanupOutbox()` 定期清理;可用 `app.sse-relay.enabled=false` 退回纯内存。启动即把水位推进到当前最大 `seq`,避免新实例重放历史。实测:v4 迁移应用、`sse relay enabled (instance=inst-...)`、SSE 订阅收到 `open`/`ping`;单测 3 例覆盖 本地投递+出站/外部事件中继+跳过自身与无订阅者/关闭不写表。
- [x] `P1` C4 异常处理与错误码分散、部分吞异常。→ `GlobalExceptionHandler` 统一映射:新增 400(请求体格式错误/参数缺失/类型不匹配)、404(未知路径 `NoResourceFoundException`)、405(方法不支持)、413(上传过大)、415(媒体类型)、429(限流,修正此前 429 被当 400 的 bug);`AccessDeniedException`+`AuthorizationDeniedException`→403;客户端错误记 debug 不打 error 堆栈,仅未预期异常记 error。`ResultCode` 补 `METHOD_NOT_ALLOWED/PAYLOAD_TOO_LARGE/UNSUPPORTED_MEDIA_TYPE`,并把 `USER_NOT_FOUND/SESSION_NOT_FOUND`→404、`LAST_ADMIN`→409。实测:未知路径 404、PUT /api/jobs 405、DELETE /api/jobs/ 404、`{name:` 400。
- [x] `P1` C5 死代码/冗余。→ 已移除未被前端调用的 `/chat/templates/home` 与 `homeTemplates`;并**完整移除恒空的「AI 变更总结」功能**(`ChangeSummary` 类型、store `summaries`/`flow` 传参、`turns.buildFlow` 的 summary 交错逻辑、`ChatView` summary 分支与 `ChangeSummaryCard.vue`)。模板 `enabled`/`setEnabled`(无 UI 入口)暂留。

## D. 前端

- [x] `P1` D1 巨型组件:`ChatView.vue`、`ChatInput.vue` 过大。→ 抽出 **6 个 composable**:`useSessionFind.ts`(会话内查找/高亮)、`useSessionNav.ts`(任务/收藏跳转 + 消息定位轮询)、`useWorkspacePanel.ts`(面板开合/标签/拖拽宽度)、`useChatScroll.ts`(钉底/贴底稳定期/上翻加载/内容撑高跟随/任务规划)、`useManagePages.ts`(五类整页互斥 + 侧边栏联动)、`useVirtualList.ts`(渲染序列归并 + 流式/思考占位判定),以及 `components/{SkillsPanel,SessionPickers,SlashSkillMenu}.vue`;`ChatView.vue` **1877 → 859 行**、`ChatInput.vue` **1271 → ~1072 行**(纯搬迁、逻辑逐行保留;每步 typecheck 0 错、7/7 测试、build 通过、页面 200 无报错、容器 healthy)。
- [x] `P1` D2 调试日志。→ `stores/chat.ts` 的 `[timeline]` `console.debug` 全部改走 `dbg()` 门控(默认关闭,`localStorage.setItem('aiwb_debug','1')` 开启);`WidgetFrame.vue` 两条高频调试日志移除。`favorites.ts` 的 3 条为错误路径 `console.debug`,保留。
- [x] `P1` D3 无 i18n,全站中文硬编码。→ 已引入 `vue-i18n` + `src/i18n/index.ts`(zh/en、持久化、`setLocale`、`<html lang>` 同步、`messages` 导出 + 编译回归测试),**48 个命名空间、约 1340 处**接线,覆盖:侧边栏(时间分组/会话操作菜单/归档/重命名/导出)、`ChatInput`、`ChatView`(连接状态/查找/切换遮罩/工作区拖拽)、`CronPicker`(cron 五种模式/星期/时/分/描述串)、`SkillsPanel`、`LoginView`/`RegisterView`、`AutomationManager`(78 处,含任务变量编辑器与执行历史)、`DashboardView`(驾驶舱 46 处)、`WidgetFrame`、`SessionPickers`、`JobRunsPanel`、`TemplateLibrary`、`DeliverableBar`、`ErrorBoundary`、`FavoritesPanel`、`FileTree`、`ModelSelector`、`Onboarding`、`ReasoningBlock`、`SlashSkillMenu`、`TemplateThumb`、`TextPromptModal`、`TypeReveal`、`UserMenu`、`WelcomeBoard`、`ChatLayout`、docx/pptx 预览器,以及 `stores/chat.ts`、`utils/markdown.ts`、`utils/codeCopy.ts`、`utils/turns.ts`、`composables/useWorkspacePanel.ts` 等非组件模块(用 `i18n.global.t`)。**剩余约 70 行中文为有意保留**:① 入库数据(模板分类值、技能 emoji 映射)② agent 输出协议标记(`[结果文件]` `[附件]` `[技能:]` 及 `完成/错误/提示` 日志分级正则)③ **发给模型的提示词正文**(`ChatInput` 示例提问、`ChatView` 模板填充文案、`chat.ts` 总结提示词)④ 中文关键词启发式(`AssistantTurn` 的 `为什么|怎么|是什么` 正则)⑤ 代码注释与 SKILL.md 种子文档。

> **七条踩坑约束(已写入清单)**:① 入库数据不译;② 与后端播种值耦合的字符串不译;③ 变量名被占用的文件用别名(`tr`/`t2`);④ 批量替换脚本须以 UTF-8 读取后执行(PS 5.1 按 ANSI 读 `.ps1` 会打乱中文),且**落盘脚本需带 UTF-8 BOM**;⑤ 提示词正文保持中文,只译界面 chrome;⑥ **字面量花括号必须转义**(`{{占位}}` → `{'{{占位}}'}`,`{参数}` → `{'{参数}'}`),且这类行外层要用双引号;⑦ **文案不能以 `|` 开头**(vue-i18n 视为复数分隔符,会报 "Plural must have messages"),分隔符放到模板里。批量脚本的引号占位符用 `%%` 而非 `@`,否则会误伤 `@click` 等 Vue 指令前缀。
>
> **回归测试**:`i18n.test.ts` 遍历 zh/en 全部叶子 key 调 `t()` 强制编译,断言带 key 名便于定位;并正确处理「整条文案就是一个占位符」(如 `en.cron.dayUnit = '{n}'`)无参时合法为空串的情况。历史上真被它抓出两类事故:`McpManager` 花括号未转义导致 `t()` 返回空串(提示文案实际空白)、`automation2.exitCode` 以 `|` 开头导致编译失败。
>
> **前端快速迭代通道**:`.\scripts\dev-frontend.ps1` = 宿主机构建 + `docker cp` 覆盖容器内 `/usr/share/nginx/html`,**免重建镜像、免重启容器**(约 27s,对比 `docker compose up -d --build app` 的 2~4 分钟且会把前端构建两遍)。仅改后端时才用全量 `--build`(BuildKit 会命中前端层缓存)。
- [~] `P1` D4 可访问性弱(键盘/焦点/ARIA)。→ 已给图标按钮补 `aria-label`(输入框附件/技能/工具/发送/停止、ChatView 侧边栏展开/查找/工作区收起、回到最新);可点击 `span/div`(侧边栏收起、任务规划头部)加 `role="button"` + `tabindex` + Enter/Space 键盘操作 + `aria-expanded`;**`<html lang>` 跟随语言**(`applyDocumentLang`,zh-CN/en,含无 document 环境兜底);对话消息列表加 `role="log"` + `aria-live="polite"` + `aria-relevant` + `aria-label`(屏读播报新消息);弹窗焦点由 naive-ui `NModal` 自动 trap。其余(更多 tab/列表键盘导航、焦点返回)继续。
- [x] `P2` D5 无错误边界;异步异常未统一处理。→ 新增 `components/ErrorBoundary.vue`(`onErrorCaptured` 捕获子树渲染/生命周期异常,展示兜底 UI + 重试/刷新,并 `return false` 阻止冒泡避免整页白屏),在 `App.vue` 包裹 `<router-view/>`;`main.ts` 注册 `app.config.errorHandler` 与 `window.unhandledrejection` 统一记录。(注:此前 i18n `@` 语法错误导致的 ChatInput 白屏即属此类,现已可视兜底。)
- [x] `P2` D6 长会话无虚拟列表;移动端仅部分适配。→ **虚拟列表**:新增 `composables/useVirtualWindow.ts`(变高列表窗口化:key→实测高度缓存、累计偏移二分定位可见区间、视口上下各 6 行 overscan、单个 `ResizeObserver` 校正实测高度)。三条关键设计:① **阈值兜底**——条数 ≤ 40 时整渲染,短会话行为与引入前完全一致(绝大多数场景零回归);② **高度缓存以 key 为索引**——历史分页前插只改顺序不改 key,已测高度直接复用;③ **锚定补偿**——位于视口上方的行变高时按 delta 补偿 `scrollTop`(贴底时改为滚到底),避免 iframe/图片异步撑高把用户正在读的内容顶走。`ChatView.vue` 改为渲染 `vwinRows` + 上下 `.vw-spacer` 占位;`useSessionFind.locateTurn` 新增可选 `locate` 定位器(目标行未渲染时按偏移直接滚动,回退原 DOM 定位);`useChatScroll.handleVirtualScroll` 前插历史时按 `scrollHeight` 差补偿 `scrollTop`(原先无论普通滚动还是窗口化都会整体上跳一屏)。**移动端**:统一 768px 断点,补 `.chat-virtual-inner` 去 860px 限宽/收窄内边距、回到最新按钮放大到 40px、查找条整行、切换遮罩字号;`AssistantTurn` 新增移动端块(工具条常态显示且按钮 34px、授权选项改单列、时间线 `max-height` 300px)并**删除一段指向不存在类名的死 CSS**;`index.html` 补 `viewport-fit=cover`,`.chat-view` 补 `env(safe-area-inset-*)`;修 `ChatInput` 自适应高度上限 JS 200px / CSS 260px 的不一致。

> **新踩坑约束**:`withDefaults(defineProps<...>(), { ... })` 的默认值会被 SFC 编译器提升到 `setup()` 之外,**不能引用 `t()` 等 setup 内声明的变量**,否则 `vite build` 报 "`defineProps()` cannot reference locally declared variables"(而 `vue-tsc` 与 vitest 都查不出来,只有真实构建会失败)。这类默认值要留空串,兜底文案放到模板里写 `props.title || t('...')`。`scripts/dev-frontend.ps1` 已在构建失败时抛错中止、不会覆盖容器,因此该问题不会污染线上。
- [x] `P2` D7 Tab/列表交互模式不统一、重复代码多。→ 抽出 `components/ui/` 五个公共组件并迁移全部调用点:① **`SectionTabs.vue`** 统一下划线 Tab 条(原 5 处手写:`SkillsPanel`/`SkillMarketplace`/`TemplateLibrary`/`McpManager`/`AutomationManager`),补 ←/→/Home/End 键盘切换 + roving tabindex + `role=tablist/tab` + `aria-selected`,计数徽章从三套(`.lib-tab-n`/`.tab-count`/`.cnt`)收敛为一套;② **`FilterChips.vue`** 统一分类筛选胶囊(原 2 处;模板库那处原本是 `<span>` 不可键盘访问,现为 `<button>` + `aria-pressed`);③ **`IconPicker.vue`** 统一图标选择器(原 3 处,尺寸 28~30px 不一),`ICON_PRESETS` 从两份副本收敛到 `utils/iconPresets.ts`;④ **`EmptyState.vue`** 统一空态(图标 + 主/副文案 + 操作插槽);⑤ **`ListPager.vue`** 统一分页器(原 11 处),支持 `item-count`/`page-count` 两种模式与居中/靠右。同步删除各组件内重复的 `.lib-tab`/`.icon-choice`/`.mp-tab`/`.tpl-cat`/`.tab-count` CSS(约 130 行)。顺带修掉工作区技能面板两处问题:内嵌的「技能市场」Tab 已移除(市场改为独立页面/弹窗,见 `ChatView` 的 `skillsPageShow`/`marketplaceShow`),并给「我的技能」加分页(每页 8 条,删除后自动收敛到最后一页)。

> **技能计数不一致的根因与修复(重要)**:`SkillMarketplaceService.publish()` 过去**只写 `skill_marketplace` 表**,既不写 `user_skill` 也不落盘;而「工作区技能」查 `user_skill`、「选择技能」查 `磁盘 ∩ user_skill`,只有「市场-我的」查市场表 —— 于是同一批技能在三处的可见性不同、计数长期对不上(实测 3 / 2 / 2)。修复四件事:① `publish()` 发布即入库(写 `user_skill` + 落盘,`source=self`、`visibility=1`);② 市场列表的 `installed` 判据从 `skill_install` 改为 **`user_skill`**(本地技能库才是事实源);③ 列表读取时做**存量自愈** `repairOwnSkills()`:清理「幽灵安装」(`skill_install` 有、`user_skill` 已被删 → 曾被误标已安装)并为「只发布未入库」的技能补登记;④ `UserSkillService.list()` 增加**磁盘自愈**,把「库里有、盘上没有」的技能补写一次。`SkillMarketplaceService` 不复用 `UserSkillService`(后者反向依赖它,构造器注入会成环),沿用 `install()` 的直写 mapper 模式。
>
> **模型重复读取同一文件导致死循环(已修)**:`AioSandbox.read` 没有任何防重复保护,实测一次"做流程图"的请求里,模型先合理探索了 25 个 `dynamic-ui` 技能文件,随后对 `templates/architecture-elements/template.md` **连续发出 26 次逐字节相同的 `read_file`**(args 完全相同、从不改 `offset`),直到用户手动中断(`status=aborted`);`parts.toolCalls` 里留下 53 条调用、51 条 `read_file`,界面显示"53 步操作"。修复:在 `AioSandbox` 上加 `_read_seen[(path, offset, limit)] = (内容 sha256 前 16 位, 重复次数)`,重复读取且**返回区间内容逐字节相同**时,在结果末尾追加提示「该区间已重复读取 N 次,内容与上次完全相同,重复读取不会得到新信息;文件共 M 行,如需后续内容请用 offset=K 继续读取」,让模型跳出循环。内容本身照常返回(只多一行提示),因此不影响正常分页读取;改动文件后区间内容变化则不触发(判定基于实际返回的切片,而非 mtime,无需额外 stat 调用)。缓存上限 200 条,防止长会话无限增长。
>
> **SSE 工具结果截断标注(已修)**:`main.py` 转发给界面的工具结果硬截断为 2000 字符,**且不带任何标记** —— 前端 `ToolCallItem` 的 chip 行数统计基于这段截断内容,于是 7232 字节的 `template.md` 在界面上显示成"28 行",极具误导性(模型侧拿的是完整 `ToolMessage`,不受影响)。现提取 `_SSE_TOOL_CONTENT_LIMIT = 2000` 常量,超限时追加「…[内容较长,此处仅显示前 N 行(共 M 行)]」。
>
> **另一条排查教训**:用 MySQL `JSON_TABLE` 统计 `toolCalls[].output` 是否为空时,列类型写 `TEXT` / `VARCHAR(4000)` 会让超长值变成 `NULL`,导致把有内容的调用误判成"空输出"(一度以为 `read_file`/`use_skill` 也被隐藏)。正确做法是把原始 JSON 导出到文件后用 Node 解析。
>
> **「步操作数」与工具分类卡对不上(已修)**:`AssistantTurn` 的折叠头原先用 `thinkingStepCount` 统计步数,口径与上方分类卡不同 —— ① 额外把「有解说的思考段」也算作一步(解说是文字,不是操作,也不出现在任何卡片里);② 直接累加各 `step.toolCalls.length`,没有像 `allToolCalls` 那样按调用 id 去重。实测一条 `toolCalls=31 / timeline=48` 的消息,头部显示「44 步操作」而卡片合计只有 31。现统一取 `allToolCalls.length`(与分类卡同源,合计必然相等),并新增 `extraTimelineRows` 在头部补一句「含 N 段解说」,把时间线里那些非工具的行显式交代清楚。
>
> **两个工具"卡片有、时间线没有"(已修)**:① `write_todos` 被 `displayToolCalls` 里硬编码的 `tc.name !== 'write_todos'` 过滤,理由是"清单已在任务规划卡片渲染",但那张卡片**只在流式生成期间存在**(`activePlan` 要求 `chat.isStreaming`),turn 一结束就消失,8 条记录在界面上彻底不见;现改为**仅在生成中隐藏**,定稿后自动还回时间线。② `baidu_chatCompletions`(来自 MCP server `baidu`)的结果不在 `output` 里(恒为空串),真正的内容在 `args`,被 `isBlankToolOutput`(已完成 + 空输出 ⇒ 判为"无内容记录")整条隐藏;现改为只有 `output` 空 **且** `outputs` 空 **且** `args` 为空才算无内容。
>
> **排查时先确认数据源**:应用连的是宿主机 MySQL(`host.docker.internal:3306`),compose 里的 `docker-mysql-1` 是未被使用的另一套库;应用容器内没有 mysql 客户端,可用 `docker exec docker-mysql-1 mysql -h host.docker.internal -P 3306 -u aiworkbench -p<pw> ai_workbench` 查真实库(密码取自 app 容器 `DATASOURCE_PASSWORD`)。控制台中文会因代码页显示为乱码,需要精确比对时把结果写入临时 UTF-8 文件再用读文件工具查看。
>
> **i18n 字典的隐性损坏(已修并加测试)**:`zh.admin: {` 曾缺少闭合花括号,导致 `automation2`/`dashboard`/`pickers2`/`tail` 等 **24 个命名空间被嵌进 admin 内部**。语法合法、`vue-tsc` 不报错、原有「遍历字典所有 key」的测试也发现不了(那些 key 确实存在于 `messages.zh.admin.xxx`,只是路径不对),表现为这批界面文案全部退化成裸 key。现补两条回归测试:**① zh/en 的命名空间与叶子 key 集合必须完全一致;② 源码里所有 `t('…')`/`tr('…')` 引用的 key 必须同时存在于 zh 与 en**(用 Vite 的 `import.meta.glob(..., { query: '?raw' })` 读源码,不依赖 node 类型,故无需 `@types/node`)。测试数由 7 增至 9。
>
> **另一条编译教训**:在 `@RequiredArgsConstructor` 类里重复声明同名 `final` 字段,会让 javac 在进入注解处理轮之前就报错,**连带导致全项目的 Lombok 注解处理失败**,刷出成百上千条 `cannot find symbol: variable log / getXxx()` 假错误。遇到「突然所有 Lombok getter 都没了」,先找真实的重复字段/语法错误。

## E. Agent-service

- [x] `P1` E1 疑似死模块。→ 核查确认 `sandbox.py`/`sandbox_k8s.py`/`tokui_builder.py` 源码已不存在(仅残留 `__pycache__` *.pyc),无需删除。
- [x] `P2` E2 无测试/指标/追踪。→ 已加 `agent-service/app/metrics.py` + `GET /metrics`(Prometheus 文本:uptime + invoke/stream/stream_error 计数);测试见 A1。追踪(Tracing)与直方图待后续。
- [x] `P1` E3 鉴权仅内部令牌,`user_id` 即身份。→ 加网络层限制 + 来源校验:新增 `agent-service/app/inbound_guard.py`(从 `INTERNAL_TRUSTED_CIDRS` 解析 IP/CIDR 白名单),以 FastAPI 中间件对**除 `/health` 外的所有请求**做来源校验(非白名单 → 403);并给此前漏挂的 `/sandbox/browser-info/{user_id}`、`/sandbox/active-users` 补 `Depends(verify_internal_token)`(原为裸暴露)。默认空=不限制来源(仍要求令牌),生产可填内网网段。实测:health 200、browser-info/active-users 无令牌 401、带令牌 200、metrics 200(受 CIDR 约束)。compose(dev+prod)已透传 `INTERNAL_TRUSTED_CIDRS`;新增 `tests/test_inbound_guard.py`。
- [~] `P1` E4 取消/超时/资源回收策略有限。→ 已有:单命令超时 `AGENT_SANDBOX_MAX_TIMEOUT`(clamp)、空闲回收 `AGENT_SANDBOX_IDLE_TTL`(reaper)、流式每步 `request.is_disconnected()` 断连即终止(langgraph checkpoint 保留)、后端 `AGENT_JOBS_TIMEOUT_MINUTES`/`AGENT_MAX_TURNS`。**新增并发上限保护** `AGENT_SANDBOX_MAX_ACTIVE`(0=不限):达上限时**拒绝新建**沙箱(复用已有不受限,计数排除自身,故 max=1 仍可服务单用户),并计 `aiwb_sandbox_rejected_total`;dev/prod compose 与 `.env.example` 已透传。实测(容器内):cap 关闭放行、cap=1 时第二个用户抛 `沙箱并发已达上限(1)...`、`active_sandbox_count(exclude=self)` 正确。新增 `tests/test_sandbox_cap.py`。**剩余:未做正式并发压测(部署期验证)。**
- [x] `P2` E5 沙箱生命周期与多用户隔离的可观测性。→ `metrics.py` 支持 gauge(`set_gauge`);`aio_sandbox` 埋点:`aiwb_sandbox_active`(活跃数 gauge)+ `created/reused/stopped/destroyed/reaped/errors` 计数,生命周期关键节点补日志(创建就绪/空闲回收)。`GET /sandbox/active-users` 改为**按 Docker 标签 `aiwb.role=aio-sandbox` 枚举**(跨 worker 一致,真正反映 per-user 隔离边界;Docker 不可用时回退进程内注册表),返回 total/users/sandboxes(user/name/status/startedAt)。实测:ensure 沙箱后 `aiwb_sandbox_created_total 1`、`reused_total 2`、`active 1`;`active-users` 返回 `{total:1, users:["20"], sandboxes:[{user:"20",name:"aio-sandbox-u20",status:"running"}]}`。新增 `tests/test_metrics.py`。

## F. 运维 / 交付

- [~] `P0` F1 单机部署、无 HA、无副本协调(依赖 C3)。→ 应用层副本协调已具备:定时任务用 `DbLockService` 互斥、会话事件用 `session_event` 出站表中继(见 C3),多副本可并列运行。**剩余为部署层**(超出代码范围,需部署方决策):无副本编排/自动故障转移、MySQL/Postgres 单点、nginx 单入口;建议:多副本 + 外部 LB、DB 主从/托管实例、会话改用外部存储。
- [x] `P0` F2 无备份/恢复:MySQL、Postgres、`/workspace`、审计文件。→ 新增 `scripts/backup.sh`:一次性备份 MySQL(经 host.docker.internal 的 mysql 客户端容器)、Postgres(compose 容器 `pg_dump`)、工作区卷(`docker_workspace` tar),按 KEEP 保留最近 N 份,附恢复命令。三条底层命令已实测可用。
- [x] `P1` F3 无监控告警,仅 `/api/health`;无 token/成本配额。→ **监控**:`metrics.py` gauge/counters + `docker/prometheus.yml` 示例 + 新增 `docker/alert.rules.yml`(AgentServiceDown/AppHealthDown/HighStreamErrorRate/SandboxConcurrencyRejected/SandboxErrors,已在 prometheus.yml 通过 `rule_files` 引入)。**配额**:每用户每日 token 配额 `app.quota.daily-tokens`(`APP_QUOTA_DAILY_TOKENS`,0=不限)。用量按日累计于 `user_token_daily`(迁移 `V5`,`INSERT ... ON DUPLICATE KEY UPDATE` 幂等累加);`AgentStreamBridge.persistTurn` 多次 upsert 按**增量差值**累加(流式只算新增);`sendUserMessage` 前校验,超限抛 429;统计/查询失败**fail-open**不阻断对话。新增 `TokenQuotaServiceTest`(5 例)。实测:v5 迁移应用、`user_token_daily` 表就绪(默认不限流,行为不变)。
- [~] `P2` F4 镜像偏大、前端产物体积大。→ 尝试 `manualChunks` 拆分:因 highlight.js/codemirror/mermaid 的语言包是**动态分包**,强制归并反而生成 13.7MB 单 chunk(更差),已回滚保持原状;改进需按库单独调优,暂缓。
- [~] `P2` F5 依赖版本锁定与安全审计。→ 同 A7:Dependabot + CI `audit`(`npm audit`/`pip-audit`,非阻断)+ 前端 lockfile。**剩余:Python 锁文件/SBOM(需真实环境生成)。**

## G. 产品功能缺口

- [ ] `P2` G1 对话:消息重新生成/编辑重发、分支/克隆会话、会话级系统提示词与温度、输入历史。
- [ ] `P2` G2 会话:文件夹/标签/批量操作。
- [ ] `P2` G3 技能:版本、依赖、权限审核、安全扫描;市场审核/评分/评论治理。
- [ ] `P2` G4 模板:去重复制(引用化)、缩略图上传、版本。
- [x] `P2` G5 自动化:重试策略、失败告警渠道、全量暂停、任务变量。→ 已实现**失败重试**(`retry_count` + `AGENT_JOBS_MAX_RETRIES`)、**失败告警 Webhook**(`AGENT_JOBS_WEBHOOK_URL`)、**全局暂停/恢复**(`JobPauseState` + `AGENT_JOBS_PAUSED` 初值 + 管理员接口 `GET/POST /api/admin/jobs/paused`,自动化面板头管理员可切换)、**任务变量**(`job_task.vars` JSON 列 + 迁移 `V3__job_task_vars.sql`;`JobTaskService.resolvePrompt` 用 `${key}` 替换,调度执行与报告均用替换后提示词;前端新建/编辑弹窗支持键值对编辑、卡片显示变量标签)。
- [ ] `P2` G6 协作:团队/组织/共享空间、租户配额。
- [ ] `P2` G7 通知:邮件/Webhook/IM 渠道。
- [ ] `P2` G8 国际化、无障碍达标、移动端优化。

---

## 备注

### B1 生产密钥轮换清单(部署方执行)
- `JWT_SECRET`:`openssl rand -hex 32` 生成随机值(当前 `docker/.env` 为占位弱值)。
- `APP_AES_KEY`:`openssl rand -base64 32`。
- `INTERNAL_TOKEN`:`openssl rand -hex 32`,app 与 agent-service 必须一致。
- `AGENT_MODEL_API_KEY`:真实 DeepSeek/模型 Key,仅存于部署环境,不入库。
- `APP_REGISTRATION_ENABLED`:默认 `true`;公网部署建议设为 `false`。

### B4 docker.sock 说明(需部署方决策)
- `app` 与 `agent-service` 均挂载 `/var/run/docker.sock`,用于按需创建/回收 per-user 沙箱容器。
- 这是「单机管理员部署」的现有设计;若对外暴露或不可信租户环境,建议改为独立的容器管理边车/受限 API,并给 app 加 `security_opt: no-new-privileges:true`。
- 本轮未改动运行配置,避免影响现网沙箱能力;列为待决策项。
