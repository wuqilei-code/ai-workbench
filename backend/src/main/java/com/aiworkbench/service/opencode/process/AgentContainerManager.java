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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * {@code opencode serve}(容器内 4096,经共享网络按容器名 DNS 直连)。每用户容器只 bind 挂载该用户自己的
 * 工作区 {@code /workspace/users/<userId>} 与私有数据 {@code /data/users/<uid>}(宿主卷挂载点下的子目录),
 * 其他用户目录与共享数据在容器内不可见;挂载点与 app 容器内路径一致,故 opencode 会话路径
 * (Home/XDG/x-opencode-directory)与进程模式完全一致,数据/会话可跨回收持久化。
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

    /** NSS wrapper 库路径(镜像已装 libnss-wrapper):拦截 getpwuid/getgrgid,让以 --user uid 运行
     *  的 agent 进程能解析自身账户(否则 ssh/git 等 NSS 客户端报 "No user exists for uid xxx") */
    private static final String NSS_WRAPPER_SO = "/usr/lib/x86_64-linux-gnu/libnss_wrapper.so";
    /** 每用户 NSS 覆盖文件目录(位于 per-user data root 下,已 bind 挂载进 agent 容器,路径一致) */
    private static final String NSS_DIR = "nss";

    private final OpencodeProperties props;
    /** app 容器自身卷挂载解析结果(/workspace 与 /data 的宿主源路径→容器内 dest),首次解析后缓存 */
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

    /**
     * 创建并启动某用户的 agent 容器。已存在<b>运行中</b>容器则直接复用(不删不重建,
     * 保留其健康进程与 opencode 会话状态);仅存在已停止/残留容器时才删除重建
     * (保证配置/env 最新)。幂等。
     */
    public void createAndStart(Long userId, int uid, int gid, Map<String, String> env) {
        String name = containerName(userId);
        if (isRunning(userId)) {
            return;
        }
        if (inspectOk(name)) {
            remove(name);
        }
        ensureUserDirs(userId, uid, gid);
        writeNssOverride(userId, uid, gid);
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
        for (Map.Entry<String, String> m : userMounts(userId, uid).entrySet()) {
            args.add("-v");
            args.add(m.getKey() + ":" + m.getValue());
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            args.add("-e");
            args.add(e.getKey() + "=" + e.getValue());
        }
        // NSS wrapper:让以 --user uid 运行的进程能解析自身账户(见 writeNssOverride 说明)。
        // LD_PRELOAD 在创建时注入,容器内 ssh/git/python 等 NSS 客户端即正常。
        args.add("-e");
        args.add("LD_PRELOAD=" + NSS_WRAPPER_SO);
        args.add("-e");
        args.add("NSS_WRAPPER_PASSWD=" + nssFile(userId, uid, "passwd"));
        args.add("-e");
        args.add("NSS_WRAPPER_GROUP=" + nssFile(userId, uid, "group"));
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
    /**
 * 解析容器内路径(以 /workspace 或 /data 为前缀)对应的宿主 bind 源路径。
 * 定时任务容器复用:(1) 工作区快照 jobs/&lt;id&gt;;(2) per-user 数据 /data/users/&lt;uid&gt;。
 * 仅容器内(root)可用。
 */
public String hostSourceFor(String containerPath) {
    String p = containerPath;
    while (p.startsWith("/")) {
        p = p.substring(1);
    }
    String prefix = p.contains("/") ? p.substring(0, p.indexOf('/')) : p;
    for (Map.Entry<String, String> e : selfVolumes().entrySet()) {
        String dest = e.getValue();
        if (dest != null && dest.equals("/" + prefix)) {
            return e.getKey() + "/" + p.substring(prefix.length()).replace('\\', '/');
        }
    }
    throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
            "无法解析容器内路径的宿主源: " + containerPath);
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

    /** app 容器自身 /workspace 与 /data 卷挂载的宿主源路径(命名卷的挂载点或 bind 宿主路径)→容器内 dest。 */
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
                String src = volumeSource(parts[1], parts[2]);
                if (src == null || src.isBlank()) {
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

    /** 解析挂载的宿主源路径:优先 bind/命名卷的 Source 宿主绝对路径,否则经 docker volume inspect 取挂载点。 */
    private String volumeSource(String name, String source) {
        if (source != null && source.startsWith("/")) {
            return source;
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            Result r = run(List.of("docker", "volume", "inspect", "-f", "{{.Mountpoint}}", name));
            String mp = r.ok() ? r.output().trim() : "";
            return mp.isBlank() ? null : mp;
        } catch (Exception e) {
            return null;
        }
    }

    /** 每用户挂载:只把该用户自己的工作区与私有数据目录 bind 进容器,其他用户目录与共享数据在容器内不可见。 */
    private Map<String, String> userMounts(Long userId, int uid) {
        Map<String, String> self = selfVolumes();
        String wsSrc = null;
        String dataSrc = null;
        for (Map.Entry<String, String> e : self.entrySet()) {
            if ("/workspace".equals(e.getValue())) {
                wsSrc = e.getKey();
            } else if ("/data".equals(e.getValue())) {
                dataSrc = e.getKey();
            }
        }
        Map<String, String> m = new LinkedHashMap<>();
        if (wsSrc != null) {
            m.put(wsSrc + "/users/" + userId, "/workspace/users/" + userId);
        }
        if (dataSrc != null) {
            m.put(dataSrc + "/users/" + uid, props.getPerUserDataRoot() + "/" + uid);
        }
        if (m.isEmpty()) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "无法解析 app 容器卷挂载(/workspace 与 /data),无法构建每用户挂载");
        }
        return m;
    }

    /** 确保每用户 bind 挂载源目录存在(容器 bind 要求 source 已存在);新建目录以 root 创建后移交该 uid/gid。 */
    private void ensureUserDirs(Long userId, int uid, int gid) {
        try {
            Path wsProject = Paths.get(props.getWorkspaceRoot(), "users",
                    String.valueOf(userId), "project");
            Path dataUser = Paths.get(props.getPerUserDataRoot(), String.valueOf(uid));
            if (!Files.isDirectory(wsProject)) {
                Files.createDirectories(wsProject);
                Files.setAttribute(wsProject, "unix:uid", uid);
                Files.setAttribute(wsProject, "unix:gid", gid);
            }
            if (!Files.isDirectory(dataUser)) {
                Files.createDirectories(dataUser);
                Files.setAttribute(dataUser, "unix:uid", uid);
                Files.setAttribute(dataUser, "unix:gid", gid);
            }
        } catch (IOException e) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "初始化用户目录失败: " + e.toString());
        }
    }

    private boolean inspectOk(String name) {
        try {
            return run(List.of("docker", "inspect", name)).ok();
        } catch (Exception e) {
            return false;
        }
    }

    // ============ NSS wrapper:让 agent 容器内进程能解析自身 uid 账户 ============

    /**
     * 为当前用户生成 NSS wrapper 覆盖文件(passwd/group),使以 {@code --user uid} 运行的 agent 容器进程
     * (ssh/git/python 等走 NSS getpwuid/getgrgid 的客户端)能解析自身账户。
     * <p>
     * 容器以非 root uid 运行,无法写入镜像 /etc/passwd(overlay 只读层);用 libnss-wrapper +
     * LD_PRELOAD 让 NSS 改从本文件读取。文件位于 per-user data root 下(已 bind 挂载进 agent 容器,
     * 容器内路径一致),目录 0755 + 文件 0644 保证非 root uid 可读。
     */
    private void writeNssOverride(Long userId, int uid, int gid) {
        try {
            Path nssDir = Paths.get(props.getPerUserDataRoot(), String.valueOf(uid), NSS_DIR);
            Files.createDirectories(nssDir);
            String acct = "aiwb-" + userId;
            String home = Paths.get(props.getPerUserDataRoot(), String.valueOf(uid), "home").toString();
            String passwd = acct + ":x:" + uid + ":" + gid + ":" + acct + ":" + home + ":/bin/sh\n";
            String group = acct + ":x:" + gid + ":\n";
            Files.writeString(nssDir.resolve("passwd"), passwd, StandardCharsets.UTF_8);
            Files.writeString(nssDir.resolve("group"), group, StandardCharsets.UTF_8);
            run(List.of("chmod", "0755", nssDir.toString()));
            run(List.of("chmod", "0644", nssDir.resolve("passwd").toString(), nssDir.resolve("group").toString()));
        } catch (IOException e) {
            log.warn("write NSS override failed for user {} (uid={}): {}", userId, uid, e.toString());
        }
    }

    /** agent 容器内 NSS 覆盖文件路径(per-user data root 已 bind 挂载,路径两端一致)。 */
    private String nssFile(Long userId, int uid, String name) {
        return Paths.get(props.getPerUserDataRoot(), String.valueOf(uid), NSS_DIR, name).toString();
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
