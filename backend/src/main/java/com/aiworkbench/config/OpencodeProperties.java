package com.aiworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "opencode")
public class OpencodeProperties {
    /** HTTP Basic 认证用户名(对应 OPENCODE_SERVER_USERNAME) */
    private String username;
    /** HTTP Basic 认证密码(对应 OPENCODE_SERVER_PASSWORD) */
    private String password;
    /** 用户工作区根目录,每用户子目录为 <workspaceRoot>/users/<userId> */
    private String workspaceRoot = "/workspace";
    /** opencode 配置数据目录(opencode.json + opencode.env),apply 接口写入此处 */
    private String configDir = "/data/opencode";

    // ---- Phase 1:per-user uid 隔离配置(Phase 2 起的进程管理也会用到 portMin/Max、idleTimeout) ----
    /** uid 分配基数:实际 uid = uidBase + user.id(确定性,免协调) */
    private int uidBase = 100000;
    /** 每用户 opencode 进程监听端口下限 */
    private int portMin = 14100;
    /** 每用户 opencode 进程监听端口上限(支持 300 个并发实例) */
    private int portMax = 14399;
    /** 空闲回收阈值(秒),idleSweep 用 */
    private int idleTimeoutSeconds = 900;

    // ---- Phase 3:每用户 Docker 容器隔离(容器模式替代 setpriv 进程) ----
    /** 容器模式:每用户一个 Docker 容器运行 opencode serve,替代 setpriv 进程;false 时回退 Phase 2 进程模式 */
    private boolean containerMode = false;
    /** agent 容器镜像(须为含 opencode 运行时 + python 依赖的镜像,默认与 app 同镜像) */
    private String agentImage = "ai-workbench-app";
    /** agent 容器 CPU 配额(核) */
    private double agentCpus = 1.0;
    /** agent 容器内存上限(MB),--memory 与 --memory-swap 同值(禁用 swap,OOM 时杀容器保护宿主机) */
    private int agentMemoryMb = 1024;
    /** agent 容器 docker stop 宽限(秒),超时 SIGKILL */
    private int agentStopGraceSeconds = 5;
    /** agent 容器所在的 docker 网络(app 容器须同网);容器内 DNS 按容器名直连,无需发布端口 */
    private String agentNetwork = "aiwb-agents";
    /** 每用户私有数据根:/data/users/<uid>/home/.config/opencode/ */
    private String perUserDataRoot = "/data/users";
    /** 后台长任务扫描间隔(ms),BackgroundTaskWatcher 轮询用户 tasks/ 目录 */
    private int backgroundTaskPollMs = 5000;
    /** 后台任务结果日志取尾部字符数上限 */
    private int backgroundTaskLogTail = 4000;
    /** 配置模板真相源:/data/opencode/template/(root 0700),applyConfig 渲染、PerUserConfigWriter 拷贝 */
    private String templateDir = "/data/opencode/template";

    // ---- 工具在途看门狗(StuckToolWatcher):兜底「卡死但无人回答」的权限/工具在途 ----
    // 注意:opencode bash 工具自带 2 分钟默认超时(OPENCODE_EXPERIMENTAL_BASH_DEFAULT_TIMEOUT_MS,超时后
    // kill 命令并把超时提示返回给 agent,agent 可继续/说明)。看门狗阈值必须**大于** bash 超时,
    // 让 bash 的超时先生效(agent 得以继续),而不是看门狗 90s 提前 abort 整个生成(那会导致任务中断无后续)。
    /** 判定阈值:生成中且在途但超过该时长(ms)无任何事件 → 视为卡死(须 > bash 2min 默认超时) */
    private long stuckToolTimeoutMs = 150_000;
    /** 权限未被回答的兜底阈值(ms):超过即在对话中注入提示(不自动 abort) */
    private long stuckPermissionPromptMs = 30_000;
    /** 看门狗扫描间隔(ms) */
    private long stuckSweepMs = 5_000;
    /** 同一用户连续 abort 的最小间隔(ms),避免误杀后立刻再杀 */
    private long stuckCooldownMs = 120_000;
    /** 未回答的权限/问题请求超过该时长(ms)自动拒绝(需 < 容器 idle 回收,让拒绝先于回收生效;0=禁用) */
    private long stuckPermissionTimeoutMs = 300_000;

    // ---- 定时任务(JobScheduler / JobQueueDispatcher) ----
    /** 同时运行的任务容器上限(容器资源有限,默认 1,超出排队) */
    private int jobMaxConcurrent = 1;
    /** 任务容器镜像(与 agent 容器同镜像) */
    private String jobImage = "ai-workbench-agent";
    /** 任务容器命名前缀(如 aiwb-job-<jobId>) */
    private String jobNamePrefix = "aiwb-job-";
    /** 任务容器 CPU 配额(核) */
    private double jobCpus = 1.0;
    /** 任务容器内存上限(MB) */
    private int jobMemoryMb = 1024;
    /** 单任务最大运行时长(分钟),超时强制停止容器的保护 */
    private int jobTimeoutMinutes = 30;
    /** 任务快照工作区所在:每用户工作区下 jobs/ 子目录(挂进任务容器的独立副本) */
    private String jobSnapshotSubdir = "jobs";
    /** 任务调度扫描间隔(ms)(供 cron 触发与队列调度复用,默认每 5s) */
    private long jobSweepMs = 5_000;

    /** uid = uidBase + userId(确定性,免协调;与 UserProvisioningService 一致) */
    public int uidOf(Long userId) {
        return uidBase + userId.intValue();
    }
}
