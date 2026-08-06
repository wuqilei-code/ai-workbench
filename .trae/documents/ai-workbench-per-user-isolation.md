# 多用户安全加固:per-user opencode 进程 + uid 隔离

## Context(为什么做)

当前 ai-workbench 单 app 容器内**一个 opencode 进程以 root 跑**,所有用户共享,隔离仅靠 `x-opencode-directory` 请求头。opencode 的 bash 工具以进程 uid(root)执行,**绕过 Java 层 `WorkspaceService.safeResolve` 路径校验**,可:`cd /`、读他人 `/workspace/users/<otherId>`、读 `/data/opencode/opencode.env` 里**全平台明文 API key**。`permission:allow` 使工具自动执行。这在多用户场景下是严重越界 + 密钥泄漏。

用户选择 **per-user 进程 + uid 隔离** 方向(单容器内),并发规模 **20-200(登录态)**,并要求"分析是否可行"。

## 可行性结论:条件性可行 ✅

(基镜像实测为 `maven:3.9-eclipse-temurin-21` → **Ubuntu 24.04.4 LTS**,`setpriv`/`runuser`/`useradd` 均在。)

| # | 关键问题 | 结论 |
|---|---|---|
| Q1 | opencode 多实例(不同端口 + 不同 XDG_CONFIG_HOME) | ✅ 可行。标准 HTTP 服务,端口+配置目录天然隔离 |
| Q2 | Java 网关以指定 uid 启动子进程 | ✅ 可行。用 `setpriv --reuid=<uid> --regid=<gid> --clear-groups`(不走 PAM、可用纯数字 uid、开销最低) |
| Q3 | 20-200 并发进程生命周期 | ⚠️ 条件可行。opencode 单进程 RSS ~100-200MB,**200 常驻不可行**;on-demand 启动 + 空闲 15min 回收 → 同时活跃进程 ≈ 最近 15min 活跃用户(典型 10-50),单机 16-32GB 可承载。**200 路同时活跃对话超单容器能力,需横向扩展(超出本计划范围)** |
| Q4 | SSE 多实例 fan-in | ✅ 可行。每活跃实例一个 `OpencodeInstanceEventConsumer` 订阅自己的 `/global/event`,事件投递到**共享** `OpencodeEventRouter`(按 opencodeSessionId 路由,全局唯一,天然正确) |
| Q5 | applyConfig 扇出 | ✅ 可行。模板(`/data/opencode/template/`,root 0700)+ 每用户副本(`/data/users/<uid>/.config/opencode/`,env 0600)。apply 时更新模板 + 推送到所有活跃实例目录 + 逐个 restart |
| Q6 | 残留风险:用户 bash 读到自己 key | ⚠️ 可接受。opencode(uid=A)必须 source env 调 LLM → 用户 A bash(同 uid)能读自己那份。但**用户间隔离已达成**(读不到 B 的)。现状是 user A 能读全平台所有 key,新设计只读自己那份,**不恶化**。可选增强:per-user key |

## 目标架构

```
单 app 容器(supervisord 管 nginx:80 + gateway:8080,均 root;opencode 由网关 ProcessBuilder 管)
  Java 网关 :8080 (root)
    ├─ OpencodeProcessManager  (ProcessBuilder + setpriv)
    ├─ UserProcessRegistry      (userId → UserProcess,computeIfAbsent 单启)
    ├─ PortAllocator            (BitSet,14100-14399)
    ├─ PerUserConfigWriter      (模板 → /data/users/<uid>/,chown + 0600)
    └─ 共享 OpencodeEventRouter  (按 opencodeSessionId 路由到前端,不改)

  每活跃用户按需启动:
    setpriv --reuid=<uidA> --regid=<gidA> --clear-groups -- \
      env HOME=/data/users/<A>/home XDG_CONFIG_HOME=/data/users/<A>/home/.config \
      OPENCODE_SERVER_USERNAME=... OPENCODE_SERVER_PASSWORD=... \
      opencode serve --hostname 127.0.0.1 --port 141XX --cors *

文件系统隔离:
  /workspace/users/<userId>                         0700  owner=uid(userId)   ← bash cwd
  /data/users/<userId>/home/.config/opencode/
      opencode.json                                 0644  owner=uid(userId)
      opencode.env                                  0600  owner=uid(userId)   ← 明文 key,仅本 uid
      agents/chat.md
  /data/opencode/template/                          0700  root                ← applyConfig 真相源
```

uid 分配:`uid = 100000 + user.id`(确定性,免协调);gid 同 uid。系统账户 `aiwb-<userId>`(useradd,仅 `ps`/`chown` 可读性 + runuser 备选,setpriv 不依赖它)。

请求路由:前端 → nginx:80 → 网关:8080 → `OpencodeClient` 取 userId → `ProcessManager.getOrStart(userId)` 拿 `(port,userDir)` → `http://127.0.0.1:<port>/...` 带 `x-opencode-directory`。turn-merging 不受影响(`message.part.updated` 仍按 opencodeSessionId 走 `ChatMessageService.upsertPartFromEvent`)。

---

## 分阶段实施

### Phase 1 — 基础:uid 管理 + 每用户配置目录 + workspace chown

(此阶段仍跑单进程,先把目录权限地基打好;隔离在 Phase 2 生效。)

- **`backend/src/main/resources/db/schema.sql`**:`users` 表加 `uid INT NULL UNIQUE`(幂等迁移;新建用户时填 `100000+id`)。
- **`backend/src/main/java/com/aiworkbench/entity/User.java`**:加 `private Integer uid;`。
- **`backend/src/main/java/com/aiworkbench/config/OpencodeProperties.java`**:加 `uidBase=100000`、`portMin=14100`、`portMax=14399`、`idleTimeoutSeconds=900`、`perUserDataRoot=/data/users`、`templateDir=/data/opencode/template`;绑到 `application.yml` 的 `opencode:` 段。
- **新增 `backend/.../service/user/UserProvisioningService.java`**:`ensureProvisioned(userId)` —— 回填 `users.uid`(老用户登录时)、`useradd -u <uid> -M -d /data/users/<uid>/home -s /usr/sbin/nologin aiwb-<userId>`(幂等)、创建 `/workspace/users/<uid>` chmod 0700 + chown、创建 `/data/users/<uid>/home/.config/opencode/` + agents。被 `UserService.register/login` 调用,替代当前 `workspaceService.ensureUserWorkspace`。
- **`backend/.../service/WorkspaceService.java`**:`ensureUserWorkspace` 改为调 `UserProvisioningService`(或保留但加 chmod 0700 + chown via `PosixFileAttributeView`)。`safeResolve` 不变。
- **`docker/entrypoint.sh`**:移除全局 opencode.env 软链(`entrypoint.sh:24` 的 `ln -sf`);改为创建 `/data/opencode/template/`(0700 root)与 `/data/users/`(0755 root);首启从 `/opt/aiworkbench/opencode.json.tpl` 拷模板到 template 目录;`agents/chat.md` 拷到 template。

**验证**:注册新用户 → `docker exec` 验证 `id aiwb-<id>`、`ls -ld /workspace/users/<id>`(0700 + 该 uid);老用户登录后 uid 回填。

### Phase 2+3 — 进程生命周期管理器 + 路由 + SSE fan-in

(Phase 2 与 3 必须一起上线:进程启动即挂消费者,否则事件无人消费。)

**新增类(`backend/.../service/opencode/process/` 包)**:
- `PortAllocator.java`:`BitSet` 维护 `[portMin,portMax]`;`allocate()`/`release(int)`,synchronized。
- `UserProcess.java`:`userId,uid,gid,port,Process,WebClient,OpencodeInstanceEventConsumer,Instant lastActivityAt,volatile State(STARTING/READY/STOPPING/DEAD)`。
- `UserProcessRegistry.java`:`ConcurrentHashMap<Long,CompletableFuture<UserProcess>>`;`getOrStart` 用 `computeIfAbsent` 保证同 userId 并发首启只跑一次;`getIfPresent`/`remove`/`activeUserIds`。
- `OpencodeProcessManager.java`:
  - `start(userId)`:算 uid/gid → `PortAllocator.allocate` → `PerUserConfigWriter.seed(userId)` → `ProcessBuilder` 构造 `setpriv --reuid=<uid> --regid=<gid> --clear-groups -- env HOME=... XDG_CONFIG_HOME=... OPENCODE_SERVER_USERNAME=... OPENCODE_SERVER_PASSWORD=... opencode serve --hostname 127.0.0.1 --port <port> --cors *`(stdout/stderr 重定向到 `/data/users/<uid>/opencode.log`)→ 轮询 `GET /global/health` 至 READY(复用 `OpencodeClient.health()` 但对特定 port 构造一次性 WebClient,10s 超时)→ 建 `UserProcess` + `new OpencodeInstanceEventConsumer(instanceWebClient,...).start()` → 返回。失败:回收端口、destroy 进程、抛 `OPENCODE_UNAVAILABLE`。
  - `stop(userId)`:consumer.stop() → `process.destroy()`(SIGTERM)→ 5s 后 `destroyForcibly` → release port → registry.remove。
  - `touch(userId)`:更新 lastActivityAt(每次 prompt_async/createSession/abort 调用)。
  - `restart(userId)`:stop + start(用于 applyConfig)。
  - `@Scheduled(fixedRate=60_000) idleSweep()`:过期则 stop。
  - `@PreDestroy stopAll()`。
- `PerUserConfigWriter.java`:`seed(userId)`/`pushConfig(userId)` 从 template 拷 `opencode.json`/`opencode.env`/`agents/` 到用户目录,chown uid、env chmod 0600。

**修改**:
- `backend/.../service/opencode/OpencodeClient.java`:去掉对单 `opencodeWebClient` baseUrl 依赖,注入 `WebClient.Builder` + `OpencodeProcessManager` + `WorkspaceService`;每方法 `processManager.getOrStart(userId)` 拿 port + `workspaceService.userDir(userId)`,构造绝对 URI `http://127.0.0.1:<port>/...`。方法签名由 `(opencodeSessionId, userDir, ...)` 改为 `(long userId, ...)`。Basic auth 头由 Builder 默认带。
- `backend/.../config/WebClientConfig.java`:`opencodeWebClient` bean → `opencodeWebClientBuilder`(`WebClient.Builder`,去掉 baseUrl,保留 Basic auth + codecs)。
- `backend/.../service/ChatSessionService.java`、`ChatMessageService.java`:调 `OpencodeClient` 处 `userDir` → `userId`(userId 已在手,改动机械)。
- `backend/.../service/opencode/OpencodeEventConsumer.java` → 重命名 `OpencodeInstanceEventConsumer`:去 `@Component`/`@PostConstruct`/`@PreDestroy` 单例语义,改普通类;构造参数 `(WebClient, OpencodeEventRouter, ChatMessageService, ObjectMapper)`;`start()` 订阅该实例 `/global/event`;`stop()` dispose。`handle()` 解析逻辑**原样保留**(turn-merging 关键路径,不动 `message.updated`/`message.part.updated` 持久化与 router.dispatch)。删除原单例 bean(否则启动订阅不存在的 4096 流)。
- `docker/supervisord.conf`:**删除 `[program:opencode]` 段**(不再常驻);保留 gateway/nginx。保留注释副本便于回滚。
- `docker/entrypoint.sh`:移除 opencode 启动相关初始化(已由 ProcessManager 接管)。

**`OpencodeEventRouter.java` 不改**(多消费者向同一 router 投递,按 opencodeSessionId 路由,天然正确)。

**验证**:`ps aux|grep opencode` 见 `setpriv ... --port 141XX` 且 `stat -c %u` = uid A;B 同理 port 142XX uid B;空闲 15min 进程消失。

### Phase 4 — applyConfig 扇出

- **`backend/.../service/ProviderConfigService.java`**:`applyConfig()` 改为:① `renderTemplate()` 把 DB 渲染的 `opencode.json`+`opencode.env`(解密 key)写到 `/data/opencode/template/`(root 0700,env 0600)。② `processManager.activeUserIds()` 拿活跃集合。③ 对每个:`PerUserConfigWriter.pushConfig(userId)` + `processManager.restart(userId)`,并行限流(如 5 并发)。④ 删除原 `restartOpencode()`(`ProviderConfigService.java:227`,supervisorctl 逻辑)。模板渲染逻辑(line 162-225 的 JSON/env 构建)抽私有方法复用。
- **`AdminProviderController.java` 不改**(`/api/admin/providers/apply` 签名不变)。

**验证**:admin 改 key → apply → 活跃用户进程逐个 restart、对话短暂中断后恢复;新用户首次登录 seed 出来的 env 含新 key。

### Phase 5 — 加固收尾

- **`docker/opencode/opencode.json.tpl`**:`permission` 收敛(opencode 1.18 permission 模型有限,**核心隔离靠 uid 不靠 permission**,此为纵深防御)。文档声明。
- **`docker/entrypoint.sh`**:`chmod 0750 /data /data/opencode /data/opencode/template`;`chmod 0755 /workspace /workspace/users`(子目录各自 0700)。
- **残留风险 Q6**:可选新增 `user_provider_config` 表 + `UserProviderConfigService` 支持 per-user key(加密入库)。标记可选,不强制本阶段。
- **迁移兼容**:见下节 lazy re-bind。

---

## 新增类清单

| 类 | 包路径 | 职责 |
|---|---|---|
| `UserProcess` | `service/opencode/process/` | 持有单用户 (uid,port,Process,WebClient,Consumer,lastActivityAt,state) |
| `UserProcessRegistry` | 同上 | `ConcurrentHashMap<userId, CompletableFuture<UserProcess>>`,computeIfAbsent 单启 |
| `OpencodeProcessManager` | 同上 | start/stop/getOrStart/restart/idleSweep/stopAll;ProcessBuilder+setpriv;健康检查 |
| `PortAllocator` | 同上 | BitSet 14100-14399,allocate/release |
| `PerUserConfigWriter` | 同上 | seed/pushConfig:模板→用户目录,chown+0600 |
| `OpencodeInstanceEventConsumer` | `service/opencode/` | 由 `OpencodeEventConsumer` 重构;每实例一条 /global/event |
| `UserProvisioningService` | `service/user/` | 注册/登录时 uid 分配 + useradd + 目录 chown |

修改:`User.java`(加 uid)、`OpencodeProperties.java`(加配置)、`OpencodeClient.java`(按 userId 路由)、`WebClientConfig.java`(Builder)、`ChatSessionService.java`/`ChatMessageService.java`(传 userId)、`ProviderConfigService.java`(applyConfig 扇出)、`WorkspaceService.java`(chown)、`supervisord.conf`(删 opencode program)、`entrypoint.sh`(模板目录)、`schema.sql`(users.uid)。

---

## 迁移关注点

1. **历史 opencode 会话不可达**:旧 `chat_session.opencode_session_id` 针对旧共享进程(状态在 `/root/.config/opencode`)。新每用户进程 `XDG_CONFIG_HOME=/data/users/<uid>/.config` 找不到旧会话状态。
2. **历史消息不丢**:消息已镜像 MySQL,前端历史从 MySQL 加载(`ChatMessageService.listBySession`),只读历史完全保留。
3. **旧会话继续发消息** → `prompt_async(旧id)` 命中新进程 404。**lazy re-bind**:`ChatMessageService.sendUserMessage`(`ChatMessageService.java:52`)捕获 opencode 404/session-not-found → `OpencodeClient.createSession` 建新 opencode 会话 → `UPDATE chat_session SET opencode_session_id=新` → 用新 id 重发。对用户无感,turn-merging 照常(事件按 opencodeSessionId 路由,新 id 经 `findByOpencodeSessionId` 仍可反查)。
4. **DB 迁移**:`users.uid` 老用户 NULL,`UserProvisioningService.ensureProvisioned` 登录时按 `100000+id` 回填 + useradd。无需批量脚本。
5. **回滚**:保留 `supervisord.conf` 中 `[program:opencode]` 段注释副本。

---

## 验证计划

### A. 核心隔离证明(用户间不可互读)— 必须全过
容器内以 uid A 执行(经 opencode bash 工具 或直接 `setpriv` 模拟):
1. `cat /workspace/users/<B>/any` → **Permission denied**(0700 + uid 不同)
2. `cat /data/users/<B>/.../opencode.env` → **Permission denied**(0600 + uid 不同)
3. `ls /data/opencode/template/` → **Permission denied**(0700 root)
4. `cat /data/users/<A>/.../opencode.env` → 成功(残留风险 Q6,可接受)
5. `cd /workspace/users/<B>` → **Permission denied**
6. `ps aux|grep opencode` → 多进程分别 setpriv 不同 uid

### B. turn-merging 回归(刚完成的特性不能坏)
1. 用户 A 发带工具的消息(如"用 bash 列当前目录")
2. 前端 SSE 观察 `message.part.updated`(tool pending→completed)+ `step-finish`
3. `GET /api/chat/sessions/<id>/messages` → `parts.toolCalls[]` 按 partId 去重、`stepFinish.reason` 正确
4. 刷新 → 历史从 MySQL 加载,parts JSON 完整,合并气泡 + 折叠思考区正常

### C. 聊天 + 生命周期回归
1. 冷启动无 opencode 进程;登录建会话 → 进程 STARTING→READY
2. 流式回复正常
3. 闲置 15min → 进程消失;再发消息 → 自动重启继续
4. 两用户同时对话 → 两进程互不影响
5. admin apply → 活跃用户进程逐个 restart,新用户首次登录拿新配置

### D. 并发边界
1. 同用户 5 并发请求触发首启 → 仅 1 进程(`computeIfAbsent` 验证)
2. 50 活跃用户 → `docker stats` 内存可接受;200 同时活跃 → 文档声明超限
