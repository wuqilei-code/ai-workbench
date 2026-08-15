package com.aiworkbench.service.job;

import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.service.opencode.process.AgentContainerManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务容器生命周期:任务在独立容器里执行 {@code opencode run <prompt>}(非交互单次),
 * 工作区使用 {@link JobWorkspaceService} 准备的快照目录,与在线交互容器完全隔离。
 * <p>
 * 容器命名 {@code aiwb-job-&lt;jobId&gt;},挂载:任务快照目录(作为 CWD)、该用户私有数据目录
 * (HOME/配置/NSS,与 agent 容器一致);资源受限(--cpus/--memory);镜像为 agent 镜像。
 */
@Slf4j
@Component
public class JobContainerManager {

    private static final Duration CMD_TIMEOUT = Duration.ofSeconds(30);
    /** NSS wrapper(与 agent 容器一致:非 root uid 解析自身账户) */
    private static final String NSS_WRAPPER_SO = "/usr/lib/x86_64-linux-gnu/libnss_wrapper.so";
    private static final String NSS_DIR = "nss";

    private final OpencodeProperties props;
    private final AgentContainerManager agentContainerManager;

    public JobContainerManager(OpencodeProperties props, AgentContainerManager agentContainerManager) {
        this.props = props;
        this.agentContainerManager = agentContainerManager;
    }

    public String containerName(Long jobId) {
        return props.getJobNamePrefix() + jobId;
    }

    /**
     * 创建并启动任务容器,运行 {@code opencode run}。阻塞等待容器退出(至多 timeout)。
     *
     * @return 运行结果(exitCode + 截断日志)
     */
    public RunResult run(Long userId, Long jobId, int uid, Path jobDir,
                         String prompt, String agent, boolean autoApprove, String logFile) {
        String name = containerName(jobId);
        remove(name);

        List<String> args = new ArrayList<>(List.of(
                "docker", "create",
                "--name", name,
                "--no-healthcheck",
                "--restart", "no",
                "--network", props.getAgentNetwork(),
                "-w", jobDir.toString(),
                "--user", uid + ":" + uid,
                "--cpus", String.valueOf(props.getJobCpus()),
                "--memory", props.getJobMemoryMb() + "m",
                "--memory-swap", props.getJobMemoryMb() + "m",
                "-v", agentContainerManager.hostSourceFor(jobDir.toString()) + ":" + jobDir.toString()));
        // 每用户私有数据目录(配置/环境/nss),与 agent 容器一致的挂载内外路径
        String dataContainer = Paths.get(props.getPerUserDataRoot(), String.valueOf(uid)).toString();
        String dataHostSource = agentContainerManager.hostSourceFor(dataContainer);
        args.add("-v");
        args.add(dataHostSource + ":" + dataContainer);
        ensureNssOverride(userId, uid, dataHostSource);
        // 环境:HOME/XDG + opencode.env 注入 + NSS(env 用容器内路径)
        for (Map.Entry<String, String> e : computeEnv(userId, uid).entrySet()) {
            args.add("-e");
            args.add(e.getKey() + "=" + e.getValue());
        }
        args.add("-e");
        args.add("LD_PRELOAD=" + NSS_WRAPPER_SO);
        args.add("-e");
        args.add("NSS_WRAPPER_PASSWD=" + dataContainer + "/" + NSS_DIR + "/passwd");
        args.add("-e");
        args.add("NSS_WRAPPER_GROUP=" + dataContainer + "/" + NSS_DIR + "/group");
        // 入口:opencode run <prompt> [--auto](默认 format=formatted,输出最终回答文本供日志/结果展示)
        args.add(props.getJobImage());
        args.add("run");
        args.add(prompt);
        if (autoApprove) {
            args.add("--auto");
        }
        if (agent != null && !agent.isBlank()) {
            args.add("--agent");
            args.add(agent);
        }

        Result created = run(args);
        if (!created.ok()) {
            throw new IllegalStateException("创建任务容器失败: " + created.output());
        }
        Result started = run(List.of("docker", "start", name));
        if (!started.ok()) {
            remove(name);
            throw new IllegalStateException("启动任务容器失败: " + started.output());
        }
        log.info("job container {} started (userId={} jobId={} prompt='{}')",
                name, userId, jobId, tldr(prompt));
        return waitExit(userId, jobId, name, logFile);
    }

    /** 容器是否在运行。 */
    public boolean isRunning(Long jobId) {
        try {
            Result r = run(List.of("docker", "inspect", "-f", "{{.State.Running}}", containerName(jobId)));
            return r.ok() && "true".equals(r.output().trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** 容器退出码(-1 表示容器/检查失败)。 */
    public int exitCode(Long jobId) {
        try {
            Result r = run(List.of("docker", "inspect", "-f", "{{.State.ExitCode}}", containerName(jobId)));
            return r.ok() ? Integer.parseInt(r.output().trim()) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 容器日志(至多 tailChars 字符)。 */
    public String logs(Long jobId, int tailChars) {
        Result r = run(List.of("docker", "logs", "--tail", "500", containerName(jobId)));
        if (!r.ok()) {
            return "(docker logs failed: " + r.output() + ")";
        }
        String s = r.output();
        return s.length() > tailChars ? s.substring(s.length() - tailChars) : s;
    }

    /** 停止并删除容器(非运行也安全)。 */
    public void stopAndRemove(Long jobId) {
        Result stop = run(List.of("docker", "stop", "-t", String.valueOf(props.getAgentStopGraceSeconds()), containerName(jobId)));
        if (!stop.ok() && !notFound(stop.output())) {
            log.warn("docker stop {} failed: {}", containerName(jobId), stop.output());
        }
        remove(containerName(jobId));
    }

    /** 应用启动时清理任务容器残留(与 AgentContainerManager 同思路)。 */
    public void cleanupAllStale() {
        Result r = run(List.of("docker", "ps", "-aq", "--filter", "name=" + props.getJobNamePrefix()));
        if (!r.ok()) {
            log.warn("docker ps (stale job cleanup) failed: {}", r.output());
            return;
        }
        int n = 0;
        for (String line : r.output().split("\\R")) {
            String id = line.trim();
            if (id.isEmpty()) {
                continue;
            }
            Result s = run(List.of("docker", "stop", "-t", String.valueOf(props.getAgentStopGraceSeconds()), id));
            Result rm = run(List.of("docker", "rm", "-f", id));
            n++;
            log.info("cleaned stale job container {} (stop={} rm={})", id, s.ok(), rm.ok());
        }
        if (n > 0) {
            log.info("cleaned {} stale job container(s)", n);
        }
    }

    // ============ 内部 ============

    /** 生成 NSS override 文件(与 AgentContainerManager 一致),使非 root uid 在容器内能解析自身账户。 */
    private void ensureNssOverride(Long userId, int uid, String dataHost) {
        try {
            Path nssDir = Paths.get(dataHost, NSS_DIR);
            Files.createDirectories(nssDir);
            String acct = "aiwb-" + userId;
            String home = Paths.get(dataHost, "home").toString();
            Files.writeString(nssDir.resolve("passwd"),
                    acct + ":x:" + uid + ":" + uid + ":" + acct + ":" + home + ":/bin/sh\n",
                    StandardCharsets.UTF_8);
            Files.writeString(nssDir.resolve("group"), acct + ":x:" + uid + ":\n", StandardCharsets.UTF_8);
            run(List.of("chmod", "0755", nssDir.toString()));
            run(List.of("chmod", "0644", nssDir.resolve("passwd").toString(), nssDir.resolve("group").toString()));
        } catch (IOException e) {
            log.warn("write NSS override failed for user {}: {}", userId, e.toString());
        }
    }

    /** 等待容器退出,轮询 timeout 分钟;随后收集 exitCode + 日志落盘并返回结果。 */
    private RunResult waitExit(Long userId, Long jobId, String name, String logFile) {
        long deadline = System.currentTimeMillis() + props.getJobTimeoutMinutes() * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning(jobId)) {
                break;
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopAndRemove(jobId);
                return new RunResult(-1, 0, "任务等待被中断");
            }
        }
        boolean timedOut = isRunning(jobId);
        if (timedOut) {
            stopAndRemove(jobId);
            String msg = "任务超过 " + props.getJobTimeoutMinutes() + " 分钟超时,已强制停止";
            writeLog(logFile, msg);
            log.warn("job timed out: userId={} jobId={} name={}", userId, jobId, name);
            return new RunResult(124, 0, msg);
        }
        int code = exitCode(jobId);
        String full = logs(jobId, 200_000);
        writeLog(logFile, full);
        remove(name);
        log.info("job container {} exited code={} (userId={} jobId={})", name, code, userId, jobId);
        return new RunResult(code, code == 0 ? 1 : 0, full);
    }

    private void writeLog(String logFile, String content) {
        try {
            Path p = Paths.get(logFile);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("write job log {} failed: {}", logFile, e.toString());
        }
    }

    /** 与 OpencodeProcessManager 一致的环境:HOME/XDG + 用户 opencode.env 注入的 API key。 */
    private Map<String, String> computeEnv(Long userId, int uid) {
        Map<String, String> env = new java.util.HashMap<>();
        Path home = Paths.get(props.getPerUserDataRoot(), String.valueOf(uid), "home");
        env.put("HOME", home.toString());
        env.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        env.put("XDG_DATA_HOME", home.resolve(".local/share").toString());
        env.put("XDG_STATE_HOME", home.resolve(".local/state").toString());
        env.put("OPENCODE_SERVER_USERNAME", props.getUsername());
        env.put("OPENCODE_SERVER_PASSWORD", props.getPassword());
        Path envFile = home.resolve(".config/opencode/opencode.env");
        parseEnvFile(envFile).forEach(env::put);
        return env;
    }

    private Map<String, String> parseEnvFile(Path envFile) {
        Map<String, String> result = new java.util.HashMap<>();
        if (!Files.isRegularFile(envFile)) {
            return result;
        }
        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.trim().startsWith("#")) {
                    continue;
                }
                int idx = line.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = line.substring(0, idx).trim();
                String val = line.substring(idx + 1).trim();
                result.put(key, val);
            }
        } catch (IOException e) {
            log.warn("parse opencode.env failed ({}): {}", envFile, e.toString());
        }
        return result;
    }

    private String tldr(String prompt) {
        String p = prompt == null ? "" : prompt.replace('\n', ' ');
        return p.length() > 60 ? p.substring(0, 60) + "…" : p;
    }

    private void remove(String name) {
        Result rm = run(List.of("docker", "rm", "-f", name));
        if (!rm.ok() && !notFound(rm.output())) {
            log.warn("docker rm {} failed: {}", name, rm.output());
        }
    }

    private boolean notFound(String output) {
        return output.contains("No such container") || output.contains("no such container");
    }

    private record Result(int code, String output) {
        boolean ok() {
            return code == 0;
        }
    }

    private Result run(List<String> args) {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            if (!p.waitFor(CMD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("docker 命令超时: " + String.join(" ", args));
            }
            return new Result(p.exitValue(), out.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("docker 执行失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker 命令被中断");
        }
    }

    /** 任务运行结果。 */
    public record RunResult(int exitCode, int delivered, String logTail) {
    }
}