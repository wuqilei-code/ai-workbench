package com.aiworkbench.service.opencode.process;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.service.ChatMessageService;
import com.aiworkbench.service.ChangeSummaryService;
import com.aiworkbench.service.WorkspaceService;
import com.aiworkbench.service.WorkspaceSnapshotService;
import com.aiworkbench.service.opencode.OpencodeEventRouter;
import com.aiworkbench.service.opencode.OpencodeInstanceEventConsumer;
import com.aiworkbench.service.opencode.dto.HealthDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 每用户 opencode 进程生命周期管理器(Phase 2 核心)。
 * <p>
 * 以 {@code setpriv --reuid=<uid> --regid=<gid> --clear-groups} 启动独立 opencode 进程,
 * 各监听 127.0.0.1:&lt;port&gt;(端口由 {@link PortAllocator} 分配),配置目录隔离到
 * /data/users/&lt;uid&gt;/home/.config/opencode(由 {@link PerUserConfigWriter} 播种)。
 * <p>
 * 生命周期:getOrStart(按需首启,幂等单启)/ stop / restart / touch / idleSweep(定时回收) / stopAll(销毁)。
 * 每个进程配一个 {@link OpencodeInstanceEventConsumer} 订阅其 /global/event,事件投递到共享
 * {@link OpencodeEventRouter}(按 opencodeSessionId 路由,turn-merging 不受影响)。
 * <p>
 * 容器内以 root 运行(setpriv/chown 依赖);进程内 opencode 以目标 uid 运行,实现进程级 + 文件级隔离。
 */
@Slf4j
@Service
public class OpencodeProcessManager {

    private final OpencodeProperties props;
    private final PortAllocator portAllocator;
    private final UserProcessRegistry registry;
    private final PerUserConfigWriter configWriter;
    private final WorkspaceService workspaceService;
    private final OpencodeEventRouter router;
    private final ChatMessageService chatMessageService;
    private final WorkspaceSnapshotService snapshotService;
    private final ChangeSummaryService changeSummaryService;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;
    private final com.aiworkbench.service.opencode.OpencodeStats stats;
    private final AgentContainerManager agentContainerManager;

    public OpencodeProcessManager(OpencodeProperties props,
                                  PortAllocator portAllocator,
                                  UserProcessRegistry registry,
                                  PerUserConfigWriter configWriter,
                                  WorkspaceService workspaceService,
                                  OpencodeEventRouter router,
                                  ChatMessageService chatMessageService,
                                  WorkspaceSnapshotService snapshotService,
                                  ChangeSummaryService changeSummaryService,
                                  ObjectMapper objectMapper,
                                  @Qualifier("opencodeWebClientBuilder") WebClient.Builder webClientBuilder,
                                  com.aiworkbench.service.opencode.OpencodeStats stats,
                                  AgentContainerManager agentContainerManager) {
        this.props = props;
        this.portAllocator = portAllocator;
        this.registry = registry;
        this.configWriter = configWriter;
        this.workspaceService = workspaceService;
        this.router = router;
        this.chatMessageService = chatMessageService;
        this.snapshotService = snapshotService;
        this.changeSummaryService = changeSummaryService;
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
        this.stats = stats;
        this.agentContainerManager = agentContainerManager;
    }

    @PostConstruct
    public void init() {
        if (props.isContainerMode()) {
            try {
                agentContainerManager.cleanupAllStale();
            } catch (Exception e) {
                log.warn("stale agent container cleanup failed: {}", e.toString());
            }
        }
    }

    /** 健康检查轮询:最多 30 次 × 500ms = 15s */
    private static final int HEALTH_MAX_ATTEMPTS = 30;
    private static final long HEALTH_INTERVAL_MS = 500L;
    /** stop 时 SIGTERM 后等 5s 再 SIGKILL */
    private static final Duration STOP_GRACE = Duration.ofSeconds(5);

    // ============ 启动 ============

    /**
     * 启动某用户的 opencode 实例(由 registry 经 computeIfAbsent 保证同 userId 单启)。
     * 容器模式:创建并启动每用户 agent 容器;进程模式:setpriv 启动独立进程。
     * 失败:回收端口、清理实例,抛 OPENCODE_UNAVAILABLE。
     */
    public UserProcess start(Long userId) {
        int uid = props.uidOf(userId);
        int port;
        try {
            port = portAllocator.allocate();
        } catch (IllegalStateException e) {
            log.warn("port pool exhausted ({} active) while starting user {}", activeCount(), userId);
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(),
                    "当前并发用户数已达上限,请稍后重试");
        }
        log.info("starting opencode for user {} (uid={} port={} mode={})",
                userId, uid, port, props.isContainerMode() ? "container" : "process");
        return props.isContainerMode()
                ? startContainer(userId, uid, port)
                : startProcess(userId, uid, port);
    }

    /** 进程模式:ensure provisioning → seed 配置 → setpriv 启动 → 健康轮询 → 挂事件消费者。 */
    private UserProcess startProcess(Long userId, int uid, int port) {
        int gid = uid;

        // 1. 确保 uid/账户/目录就绪(幂等)
        workspaceService.ensureUserWorkspace(userId);
        // 2. 播种配置(模板 → 用户目录)
        configWriter.seed(userId);

        // 3. 构造进程:env + setpriv + opencode serve
        Process process = null;
        OpencodeInstanceEventConsumer consumer = null;
        try {
            Path logFile = userLogPath(uid);
            Files.createDirectories(logFile.getParent());

            ProcessBuilder pb = new ProcessBuilder(
                    "setpriv",
                    "--reuid=" + uid,
                    "--regid=" + gid,
                    "--clear-groups",
                    "--",
                    "opencode", "serve",
                    "--hostname", "127.0.0.1",
                    "--port", String.valueOf(port),
                    "--cors", "*");
            pb.directory(new File(workspaceService.userDir(userId)));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            configureEnv(pb.environment(), userId, uid);
            process = pb.start();

            // 4. 构造实例 WebClient(绝对 baseUrl + 该用户独立 Basic auth)
            WebClient instanceClient = instanceClient("http://127.0.0.1:" + port, userId);

            // 5. 健康轮询至 READY
            HealthDto health = waitReadyProcess(instanceClient, process, userId, port);
            if (health == null || !Boolean.TRUE.equals(health.getHealthy())) {
                throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                        "opencode 健康检查超时: " + tailLog(userLogPath(uid), userId));
            }

            // 6. 挂事件消费者(上报生成状态给本管理器,供 applyConfig 跳过 streaming 用户;上报事件心跳供看门狗)
            consumer = new OpencodeInstanceEventConsumer(instanceClient, router, chatMessageService,
                    snapshotService, changeSummaryService, objectMapper, port, userId,
                    s -> markStreaming(userId, s), (sid, type) -> noteEvent(userId, sid, type), stats);
            consumer.start();

            UserProcess up = new UserProcess(userId, uid, gid, port, process, instanceClient, consumer);
            log.info("opencode ready for user {} (uid={} port={} version={})",
                    userId, uid, port, health.getVersion());
            return up;
        } catch (BusinessException e) {
            cleanupFailed(userId, uid, port, process, consumer);
            throw e;
        } catch (Exception e) {
            cleanupFailed(userId, uid, port, process, consumer);
            log.error("start opencode failed for user {}: {}", userId, e.toString(), e);
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "opencode 启动失败: " + e.getMessage());
        }
    }

    /** 容器模式:ensure provisioning → seed 配置 → 创建并启动每用户 agent 容器 → 健康轮询 → 挂事件消费者。 */
    private UserProcess startContainer(Long userId, int uid, int port) {
        int gid = uid;

        workspaceService.ensureUserWorkspace(userId);
        configWriter.seed(userId);

        OpencodeInstanceEventConsumer consumer = null;
        try {
            Map<String, String> env = computeEnv(userId, uid);
            agentContainerManager.createAndStart(userId, uid, gid, env);

            // app 容器与 agent 容器共享 aiwb-agents 网络,按容器名 DNS 直连 serve(无需发布端口)
            WebClient instanceClient = instanceClient(agentContainerManager.serveUrl(userId), userId);

            HealthDto health = waitReadyContainer(instanceClient, userId, port);
            if (health == null || !Boolean.TRUE.equals(health.getHealthy())) {
                throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                        "opencode agent 容器健康检查超时: " + agentContainerManager.logs(userId, 2000));
            }

            consumer = new OpencodeInstanceEventConsumer(instanceClient, router, chatMessageService,
                    snapshotService, changeSummaryService, objectMapper, port, userId,
                    s -> markStreaming(userId, s), (sid, type) -> noteEvent(userId, sid, type), stats);
            consumer.start();

            UserProcess up = new UserProcess(userId, uid, gid, port, null, instanceClient, consumer);
            log.info("opencode agent container ready for user {} (uid={} port={} version={})",
                    userId, uid, port, health.getVersion());
            return up;
        } catch (BusinessException e) {
            cleanupFailed(userId, uid, port, null, consumer);
            throw e;
        } catch (Exception e) {
            cleanupFailed(userId, uid, port, null, consumer);
            log.error("start agent container failed for user {}: {}", userId, e.toString(), e);
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "opencode agent 容器启动失败: " + e.getMessage());
        }
    }

    // ============ 路由 ============

    /**
     * 取得(或按需首启)某用户的 opencode 实例,阻塞至 READY。每次调用刷新活动时间。
     * <p>
     * 若实例已失效(进程崩溃 / 容器健康检查失败),先 stop 清理(停消费者、回收端口、移除注册)再重启,
     * 避免返回死句柄导致后续请求全部失败。
     */
    public UserProcess getOrStart(Long userId) {
        UserProcess up = registry.getOrStart(userId, this::start);
        if (!isAlive(up)) {
            log.warn("opencode for user {} not alive, recycling and restarting", userId);
            stop(userId);
            up = registry.getOrStart(userId, this::start);
        }
        up.touch();
        return up;
    }

    /** 实例存活判定:进程模式看 Process.isAlive;容器模式正在流式直接视为存活,否则做一次本地健康探测。 */
    private boolean isAlive(UserProcess up) {
        if (!props.isContainerMode()) {
            return up.getProcess() != null && up.getProcess().isAlive();
        }
        if (up.isStreaming()) {
            return true;
        }
        try {
            HealthDto h = up.getWebClient().get().uri("/global/health")
                    .retrieve().bodyToMono(HealthDto.class)
                    .block(Duration.ofMillis(1500));
            return h != null && Boolean.TRUE.equals(h.getHealthy());
        } catch (Exception e) {
            // 线程被打断(如浏览器 WS 关闭触发 shutdown)→ 健康探测的 block() 会抛异常。
            // 此时绝不能把「探测失败」当作「实例死亡」去回收重启,否则关个面板就停掉 agent 容器。
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt(); // 保留中断标记
                throw e;
            }
            return false;
        }
    }

    /** 更新活动时间(不启动进程;无活跃进程则空操作)。 */
    public void touch(Long userId) {
        UserProcess up = registry.getIfPresent(userId);
        if (up != null) {
            up.touch();
        }
    }

    /** 仅返回已存在的进程;不启动。用于 abort 等不应触发首启的场景。 */
    public UserProcess getIfPresent(Long userId) {
        return registry.getIfPresent(userId);
    }

    /** 该用户 agent 实例当前是否已就绪(不启动)。冷启动提示用:未注册或容器/进程已挂则视为未就绪。 */
    public boolean isReady(Long userId) {
        UserProcess up = registry.getIfPresent(userId);
        if (up == null) {
            return false;
        }
        if (props.isContainerMode()) {
            return agentContainerManager.isRunning(userId);
        }
        Process p = up.getProcess();
        return p != null && p.isAlive();
    }

    /** 该用户进程当前是否正在生成(opencode 事件流上报;无活跃进程视为否)。applyConfig 据此跳过重启。 */
    public boolean isStreaming(Long userId) {
        UserProcess up = registry.getIfPresent(userId);
        return up != null && up.isStreaming();
    }

    /** 由 SSE 消费者上报生成状态(part.updated → busy;session.idle/error → 空闲)。 */
    public void markStreaming(Long userId, boolean streaming) {
        UserProcess up = registry.getIfPresent(userId);
        if (up != null) {
            up.setStreaming(streaming);
        }
    }

    /** 记录一次事件心跳(会话 + 类型)。由 SSE 消费者回调;看门狗据此判定「在途但长时间无事件」的卡死。 */
    public void noteEvent(Long userId, String sessionId, String type) {
        UserProcess up = registry.getIfPresent(userId);
        if (up != null) {
            up.noteEvent(sessionId, type);
        }
    }

    /** 进程最近事件时间(无进程返回 null)。 */
    public Instant lastEventAt(Long userId) {
        UserProcess up = registry.getIfPresent(userId);
        return up == null ? null : up.getLastEventAt();
    }

    /** 进程最近事件所在会话(无进程返回 null)。 */
    public String lastEventSessionId(Long userId) {
        UserProcess up = registry.getIfPresent(userId);
        return up == null ? null : up.getLastEventSessionId();
    }

    // ============ 停止 / 重启 ============

    /** 停止某用户实例:停消费者 → 停止实例(容器模式 stop+rm / 进程模式 SIGTERM→SIGKILL)→ 回收端口 → 移除注册。 */
    public void stop(Long userId) {
        UserProcess up = registry.getIfPresent(userId);
        if (up == null) {
            return;
        }
        up.setState(UserProcess.State.STOPPING);
        log.info("stopping opencode for user {} (uid={} port={})", userId, up.getUid(), up.getPort());

        if (up.getEventConsumer() != null) {
            try { up.getEventConsumer().stop(); } catch (Exception e) { log.warn("consumer stop failed: {}", e.toString()); }
        }
        if (props.isContainerMode()) {
            agentContainerManager.stopAndRemove(userId);
        } else {
            Process p = up.getProcess();
            if (p != null && p.isAlive()) {
                p.destroy();
                try {
                    if (!p.waitFor(STOP_GRACE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        p.destroyForcibly();
                        log.warn("opencode for user {} force-killed after {} grace", userId, STOP_GRACE);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                }
            }
        }
        portAllocator.release(up.getPort());
        registry.remove(userId, up);
        up.setState(UserProcess.State.DEAD);
        log.info("opencode stopped for user {}", userId);
    }

    /** 重启:stop 后 getOrStart(applyConfig 扇出用)。 */
    public void restart(Long userId) {
        stop(userId);
        getOrStart(userId);
    }

    // ============ 空闲回收 ============

    /** 每 60s 扫描:超过 idleTimeoutSeconds 未活动的实例回收。容器模式跳过仍有运行中后台任务的用户。 */
    @Scheduled(fixedRate = 60_000L)
    public void idleSweep() {
        Set<Long> ids = new HashSet<>(registry.activeUserIds());
        if (ids.isEmpty()) {
            return;
        }
        Instant deadline = Instant.now().minusSeconds(props.getIdleTimeoutSeconds());
        for (Long userId : ids) {
            UserProcess up = registry.getIfPresent(userId);
            if (up == null) {
                continue;
            }
            if (!up.idleBefore(deadline)) {
                continue;
            }
            if (props.isContainerMode() && hasRunningBackgroundTask(userId)) {
                log.info("idle sweep: user {} has running background task, skip reclaim", userId);
                continue;
            }
            log.info("idle sweep: stopping opencode for user {} (last activity={})",
                    userId, up.getLastActivityAt());
            try {
                stop(userId);
            } catch (Exception e) {
                log.warn("idle sweep stop failed for user {}: {}", userId, e.toString());
            }
        }
    }

    /** 容器模式下该用户是否有仍在前台/后台运行的任务(避免回收容器杀死进行中的长任务)。 */
    private boolean hasRunningBackgroundTask(Long userId) {
        Path tasksDir = Paths.get(props.getWorkspaceRoot(), "users",
                String.valueOf(userId), "project", "tasks");
        if (!Files.isDirectory(tasksDir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(tasksDir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .anyMatch(id -> taskStillRunning(userId, tasksDir, id));
        } catch (IOException e) {
            return false;
        }
    }

    private boolean taskStillRunning(Long userId, Path tasksDir, String id) {
        if (Files.exists(tasksDir.resolve(id + ".delivered")) || Files.exists(tasksDir.resolve(id + ".done"))) {
            return false;
        }
        Path pidFile = tasksDir.resolve(id + ".pid");
        if (Files.isRegularFile(pidFile)) {
            try {
                long pid = Long.parseLong(Files.readString(pidFile).trim());
                return agentContainerManager.processAlive(userId, pid);
            } catch (Exception e) {
                return false;
            }
        }
        // 无 pid 且未完成:可能刚启动,近期内保守跳过回收
        Path spec = tasksDir.resolve(id + ".json");
        try {
            return Files.getLastModifiedTime(spec).toMillis() > System.currentTimeMillis() - 5 * 60_000L;
        } catch (IOException e) {
            return false;
        }
    }

    @PreDestroy
    public void stopAll() {
        log.info("shutdown: stopping all {} opencode processes", registry.activeCount());
        for (Long userId : new HashSet<>(registry.activeUserIds())) {
            try {
                stop(userId);
            } catch (Exception e) {
                log.warn("stopAll failed for user {}: {}", userId, e.toString());
            }
        }
        registry.shutdown();
    }

    public Set<Long> activeUserIds() {
        return registry.activeUserIds();
    }

    public int activeCount() {
        return registry.activeCount();
    }

    // ============ 内部 ============

    private HealthDto waitReadyProcess(WebClient client, Process process, Long userId, int port) {
        for (int i = 0; i < HEALTH_MAX_ATTEMPTS; i++) {
            if (!process.isAlive()) {
                log.error("opencode process exited prematurely for user {} (port={})", userId, port);
                return null;
            }
            try {
                HealthDto h = client.get().uri("/global/health")
                        .retrieve().bodyToMono(HealthDto.class)
                        .block(Duration.ofSeconds(2));
                if (h != null && Boolean.TRUE.equals(h.getHealthy())) {
                    return h;
                }
            } catch (Exception e) {
                // 启动中,连接尚未就绪,继续轮询
            }
            try {
                Thread.sleep(HEALTH_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private HealthDto waitReadyContainer(WebClient client, Long userId, int port) {
        for (int i = 0; i < HEALTH_MAX_ATTEMPTS; i++) {
            if (!agentContainerManager.isRunning(userId)) {
                log.error("agent container exited prematurely for user {} (port={})", userId, port);
                return null;
            }
            try {
                HealthDto h = client.get().uri("/global/health")
                        .retrieve().bodyToMono(HealthDto.class)
                        .block(Duration.ofSeconds(2));
                if (h != null && Boolean.TRUE.equals(h.getHealthy())) {
                    return h;
                }
            } catch (Exception e) {
                // 启动中,连接尚未就绪,继续轮询
            }
            try {
                Thread.sleep(HEALTH_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** 设置子进程/容器环境:HOME / XDG 三个目录(显式固定,避免依赖默认值漂移) / Basic auth 凭据 + 解析用户 opencode.env 里的 API key。 */
    private void configureEnv(Map<String, String> env, Long userId, int uid) {
        env.putAll(computeEnv(userId, uid));
    }

    private Map<String, String> computeEnv(Long userId, int uid) {
        Map<String, String> env = new java.util.HashMap<>();
        Path home = Paths.get(props.getPerUserDataRoot(), String.valueOf(uid), "home");
        env.put("HOME", home.toString());
        env.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        // 显式固定数据/状态目录:opencode 会话存储落在 XDG_DATA_HOME,
        // 实例被空闲回收后重启仍能按原 opencode_session_id 恢复上下文(同 uid 同 HOME 同目录)。
        env.put("XDG_DATA_HOME", home.resolve(".local/share").toString());
        env.put("XDG_STATE_HOME", home.resolve(".local/state").toString());
        env.put("OPENCODE_SERVER_USERNAME", props.getUsername());
        env.put("OPENCODE_SERVER_PASSWORD", perUserPassword(userId));
        // bash 工具超时:超时后 opencode kill 命令并把提示返回给 agent,agent 可继续,而非整轮中断
        env.put("OPENCODE_EXPERIMENTAL_BASH_DEFAULT_TIMEOUT_MS", String.valueOf(props.getBashTimeoutMs()));
        // 解析用户 opencode.env 中的 KEY=VALUE(解密后的 API key),注入环境
        Path envFile = home.resolve(".config/opencode/opencode.env");
        parseEnvFile(envFile).forEach(env::put);
        return env;
    }

    /** 每用户独立 opencode 密码:由共享 master password + userId 确定性派生,
     *  容器间无法用同一凭据互访(缓解同网横向访问)。 */
    private String perUserPassword(Long userId) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((props.getPassword() + ":" + userId).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return props.getPassword();
        }
    }

    /** 构造实例 WebClient:baseUrl + 该用户独立 Basic auth 凭据(覆盖 builder 默认共享凭据)。 */
    private WebClient instanceClient(String baseUrl, Long userId) {
        String basic = Base64.getEncoder().encodeToString(
                (props.getUsername() + ":" + perUserPassword(userId)).getBytes(StandardCharsets.UTF_8));
        return webClientBuilder.clone()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Basic " + basic)
                .build();
    }

    private Map<String, String> parseEnvFile(Path envFile) {
        Map<String, String> result = new java.util.HashMap<>();
        if (!Files.isRegularFile(envFile)) {
            return result;
        }
        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String val = trimmed.substring(eq + 1).trim();
                // 去掉包围引号
                if (val.length() >= 2 && ((val.startsWith("\"") && val.endsWith("\""))
                        || (val.startsWith("'") && val.endsWith("'")))) {
                    val = val.substring(1, val.length() - 1);
                }
                result.put(key, val);
            }
        } catch (IOException e) {
            log.warn("parse opencode.env failed ({}): {}", envFile, e.toString());
        }
        return result;
    }

    private Path userLogPath(int uid) {
        return Paths.get(props.getPerUserDataRoot(), String.valueOf(uid), "opencode.log");
    }

    private void cleanupFailed(Long userId, int uid, int port, Process process, OpencodeInstanceEventConsumer consumer) {
        if (consumer != null) {
            try { consumer.stop(); } catch (Exception ignored) {}
        }
        if (props.isContainerMode()) {
            try { agentContainerManager.stopAndRemove(userId); } catch (Exception ignored) {}
        } else if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        portAllocator.release(port);
    }

    private String tailLog(Path logFile, Long userId) {
        if (props.isContainerMode() && userId != null) {
            return agentContainerManager.logs(userId, 1500);
        }
        try {
            if (!Files.isRegularFile(logFile)) {
                return "(no log)";
            }
            String all = Files.readString(logFile, StandardCharsets.UTF_8);
            return all.length() > 1500 ? "..." + all.substring(all.length() - 1500) : all;
        } catch (Exception e) {
            return "(read log failed: " + e.getMessage() + ")";
        }
    }
}
