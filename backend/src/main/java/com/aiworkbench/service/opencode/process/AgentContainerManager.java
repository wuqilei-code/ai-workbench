package com.aiworkbench.service.opencode.process;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.config.OpencodeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 每用户 opencode agent 容器的 Docker 生命周期管理(容器模式)。
 * <p>
 * 通过挂载进 app 容器的 /var/run/docker.sock 调用 docker CLI,为每个用户创建一个独立容器运行
 * {@code opencode serve}(容器内 4096,宿主机发布到 127.0.0.1:&lt;port&gt;)。容器与 app 容器共享
 * workspace/app-data 命名卷且挂载到相同绝对路径,故 opencode 会话路径(Home/XDG/x-opencode-directory)
 * 与进程模式完全一致,数据/会话可跨回收持久化。
 * <p>
 * 生命周期:createAndStart(创建+启动)/ stopAndRemove(停止+删除,完整释放端口与内存)/
 * cleanupAllStale(应用启动时清理残留)/ processAlive(容器内 kill -0,供后台任务存活判定)。
 * 容器镜像为 app 镜像(docker build 同款),运行期仅 opencode serve + python 等运行时,资源受限(--cpus/--memory)。
 */
@Slf4j
@Component
public class AgentContainerManager {

    private static final String NAME_PREFIX = "aiwb-agent-";
    private static final int SERVE_PORT = 4096;
    private static final Duration CMD_TIMEOUT = Duration.ofSeconds(30);

    private final OpencodeProperties props;
    /** app 容器自身卷挂载解析结果(/workspace 与 /data 的 source→dest),首次解析后缓存 */
    private volatile Map<String, String> selfVolumes;

    public AgentContainerManager(OpencodeProperties props) {
        this.props = props;
    }

    public String containerName(Long userId) {
        return NAME_PREFIX + props.uidOf(userId);
    }

    /** app 容器经共享网络访问该用户 agent 容器的 serve 地址(容器内 DNS 按名解析)。 */
    public String serveUrl(Long userId) {
        return "http://" + containerName(userId) + ":" + SERVE_PORT;
    }

    /** 创建并启动某用户的 agent 容器。已存在同名容器则先删除重建(保证配置/env 最新)。 */
    public void createAndStart(Long userId, int uid, int gid, Map<String, String> env) {
        String name = containerName(userId);
        if (inspectOk(name)) {
            remove(name);
        }
        List<String> args = new ArrayList<>(List.of(
                "docker", "create",
                "--name", name,
                "--no-healthcheck",
                "--restart", "no",
                "--network", props.getAgentNetwork(),
                "-w", props.getWorkspaceRoot() + "/users/" + userId + "/project",
                "--user", uid + ":" + gid,
                "--cpus", String.valueOf(props.getAgentCpus()),
                "--memory", props.getAgentMemoryMb() + "m",
                "--memory-swap", props.getAgentMemoryMb() + "m"));
        for (Map.Entry<String, String> m : selfVolumes().entrySet()) {
            args.add("-v");
            args.add(m.getKey() + ":" + m.getValue());
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            args.add("-e");
            args.add(e.getKey() + "=" + e.getValue());
        }
        args.add("--entrypoint");
        args.add("opencode");
        args.add(props.getAgentImage());
        args.add("serve");
        args.add("--hostname");
        args.add("0.0.0.0");
        args.add("--port");
        args.add(String.valueOf(SERVE_PORT));
        args.add("--cors");
        args.add("*");

        Result created = run(args);
        if (!created.ok()) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "创建 agent 容器失败: " + created.output());
        }
        Result started = run(List.of("docker", "start", name));
        if (!started.ok()) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "启动 agent 容器失败: " + started.output());
        }
        log.info("agent container {} started on network {} (serve at {})",
                name, props.getAgentNetwork(), serveUrl(userId));
    }

    /** 停止并删除某用户的 agent 容器(docker stop -t grace 后 rm -f),完整释放端口与内存。 */
    public void stopAndRemove(Long userId) {
        String name = containerName(userId);
        Result stop = run(List.of("docker", "stop", "-t", String.valueOf(props.getAgentStopGraceSeconds()), name));
        if (!stop.ok() && !notFound(stop.output())) {
            log.warn("docker stop {} failed: {}", name, stop.output());
        }
        Result rm = run(List.of("docker", "rm", "-f", name));
        if (!rm.ok() && !notFound(rm.output())) {
            log.warn("docker rm {} failed: {}", name, rm.output());
        }
    }

    /** 容器当前是否在运行。 */
    public boolean isRunning(Long userId) {
        try {
            Result r = run(List.of("docker", "inspect", "-f", "{{.State.Running}}", containerName(userId)));
            return r.ok() && "true".equals(r.output().trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** 容器内 pid 是否存活(跨 PID namespace 判定后台任务)。容器不在则视为不存活;僵尸进程(<defunct>)视为不存活,避免守护日志永久跳过回收。 */
    public boolean processAlive(Long userId, long pid) {
        try {
            Result r = run(List.of("docker", "exec", containerName(userId),
                    "sh", "-c", "kill -0 " + pid + " 2>/dev/null && [ \"$(awk '{print $3}' /proc/" + pid + "/stat)\" != \"Z\" ]"));
            return r.ok();
        } catch (Exception e) {
            return false;
        }
    }

    /** 容器最近日志(诊断用,至多 tailChars 字符)。 */
    public String logs(Long userId, int tailChars) {
        try {
            Result r = run(List.of("docker", "logs", "--tail", "200", containerName(userId)));
            if (!r.ok()) {
                return "(docker logs failed: " + r.output() + ")";
            }
            String s = r.output();
            return s.length() > tailChars ? s.substring(s.length() - tailChars) : s;
        } catch (Exception e) {
            return "(docker logs failed: " + e.getMessage() + ")";
        }
    }

    /** 应用启动时清理历史残留的 agent 容器(如 app 被 force-recreate 而 agent 容器存留)。 */
    public void cleanupAllStale() {
        Result r = run(List.of("docker", "ps", "-aq", "--filter", "name=" + NAME_PREFIX));
        if (!r.ok()) {
            log.warn("docker ps (stale agent cleanup) failed: {}", r.output());
            return;
        }
        int n = 0;
        for (String line : r.output().split("\\R")) {
            String id = line.trim();
            if (id.isEmpty()) {
                continue;
            }
            Result s = run(List.of("docker", "stop", "-t", String.valueOf(props.getAgentStopGraceSeconds()), id));
            if (!s.ok()) {
                log.warn("stale agent stop {} failed: {}", id, s.output());
            }
            Result rm = run(List.of("docker", "rm", "-f", id));
            if (!rm.ok()) {
                log.warn("stale agent rm {} failed: {}", id, rm.output());
            }
            n++;
        }
        if (n > 0) {
            log.info("cleaned {} stale agent container(s)", n);
        }
    }

    // ============ 内部 ============

    /** app 容器自身 /workspace 与 /data 卷挂载的 source→dest(source 为命名卷名或 bind 宿主路径)。 */
    private Map<String, String> selfVolumes() {
        Map<String, String> cached = selfVolumes;
        if (cached != null) {
            return cached;
        }
        String hostname = System.getenv("HOSTNAME");
        if (hostname == null || hostname.isBlank()) {
            hostname = "self";
        }
        Result r = run(List.of("docker", "inspect", "-f",
                "{{range .Mounts}}{{.Destination}}={{.Name}}={{.Source}};{{end}}", hostname));
        Map<String, String> map = new LinkedHashMap<>();
        if (r.ok()) {
            for (String seg : r.output().trim().split(";")) {
                String[] parts = seg.split("=", 3);
                if (parts.length != 3) {
                    continue;
                }
                String dest = parts[0];
                String src = parts[1].isBlank() ? parts[2] : parts[1];
                if (src.isBlank()) {
                    continue;
                }
                if (dest.equals("/workspace") || dest.equals("/data")) {
                    map.put(src, dest);
                }
            }
        }
        if (map.isEmpty()) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "无法解析 app 容器自身卷挂载(需 /workspace 与 /data),docker 输出: " + r.output());
        }
        selfVolumes = map;
        return map;
    }

    private boolean inspectOk(String name) {
        try {
            return run(List.of("docker", "inspect", name)).ok();
        } catch (Exception e) {
            return false;
        }
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
                throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                        "docker 命令超时: " + String.join(" ", args));
            }
            return new Result(p.exitValue(), out.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "docker 执行失败(请确认 app 容器已挂载 /var/run/docker.sock 且已安装 docker CLI): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(), "docker 命令被中断");
        }
    }
}
