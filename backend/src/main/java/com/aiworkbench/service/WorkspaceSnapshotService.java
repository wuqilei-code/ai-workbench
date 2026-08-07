package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.config.OpencodeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户工作区 git 快照服务:每轮 AI 对话结束把用户工作区(已是 git 仓库)提交为一个版本,
 * 并提供单文件的历史查询、文本 diff 与回滚。
 * <p>
 * 容器内后端以 root 运行,而工作区由 per-user uid(uidBase + userId)所有。
 * 直接以 root 执行 git 会触发 "dubious ownership" 且改写 .git 属主,故所有 git 命令
 * 一律经 {@code setpriv --reuid=<uid> --regid=<uid> --clear-groups} 以目标 uid 执行,
 * 与 opencode 子进程(同 uid)保持文件属主一致。
 * <p>
 * git 身份(aiwb / aiwb@local)已在 {@link com.aiworkbench.service.user.UserProvisioningService}
 * 初始化工作区时配置,此处直接 commit 即可。
 * <p>
 * <b>二进制安全:</b>{@code exec} 返回原始字节;回滚用 {@code Files.write} 原样写回,
 * 绝不做字符串编码转换,避免损坏 xlsx/docx/pdf 等二进制文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceSnapshotService {

    private final OpencodeProperties props;
    private final WorkspaceService workspaceService;

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(,\\d+)? \\+(\\d+)(,\\d+)? @@.*$");
    private static final String BINARY_MARKER = "Binary files";

    /**
     * 对某用户工作区做一次快照(git add -A + commit)。
     * 无变更时静默跳过并返回 null;成功返回新 commit 的完整 sha。失败仅记 warn,不向上抛。
     */
    public String snapshot(Long userId, String message) {
        Path dir = userDir(userId);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        if (git(dir, uidOf(userId), "add", "-A").exitCode != 0) {
            return null;
        }
        int exit = git(dir, uidOf(userId), "commit", "-m", sanitizeMessage(message)).exitCode;
        if (exit != 0) {
            log.debug("snapshot noop for user {} (exit={}): {}", userId, exit, message);
            return null;
        }
        log.info("snapshot committed for user {}: {}", userId, message);
        Result head = git(dir, uidOf(userId), "rev-parse", "HEAD");
        if (head.exitCode != 0) {
            return null;
        }
        return head.text().trim();
    }

    /**
     * 返回最近一次快照 commit({@code sha})相对其父提交的文件变更列表 {@code status<TAB>path}。
     * 用于 AI 变更后将之作为输入生成一句话总结。无父提交(首个 commit)或 sha 无效 → 空列表。
     */
    public List<ChangedFile> lastCommitChanges(Long userId, String sha) {
        if (sha == null || sha.isBlank()) {
            return List.of();
        }
        Result r = git(userDir(userId), uidOf(userId),
                "diff-tree", "--no-commit-id", "--name-status", "-r", sha);
        if (r.exitCode != 0) {
            log.warn("lastCommitChanges git failed (user={} sha={} exit={}): {}",
                    userId, sha, r.exitCode, r.text());
            return List.of();
        }
        List<ChangedFile> out = new ArrayList<>();
        for (String line : r.text().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t");
            if (parts.length < 2) {
                continue;
            }
            String status = parts[0];
            String path = parts[parts.length - 1].trim();
            if (!path.isEmpty()) {
                out.add(new ChangedFile(status, path));
            }
        }
        if (out.isEmpty()) {
            log.warn("lastCommitChanges empty (sha={} user={}) raw={}", sha, userId, r.text());
        }
        return out;
    }

    /** 单文件历史:按时间倒序的 commit 列表。 */
    public List<FileHistoryEntry> fileHistory(Long userId, String relativePath) {
        String path = normalizePath(relativePath);
        Result r = git(userDir(userId), uidOf(userId),
                "log", "--follow", "--format=%H|%ct|%s", "--", path);
        if (r.exitCode != 0) {
            return List.of();
        }
        String out = r.text();
        List<FileHistoryEntry> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", 3);
            if (parts.length >= 2) {
                list.add(new FileHistoryEntry(
                        parts[0],
                        Long.parseLong(parts[1]) * 1000L,
                        parts.length > 2 ? parts[2] : ""));
            }
        }
        return list;
    }

    /**
     * 单文件两版文本 diff。from/to 为 commit sha;to 为 null/空 表示对比工作区当前版本。
     * 二进制文件返回 textDiff=false。
     */
    public FileDiffResult fileDiff(Long userId, String relativePath, String from, String to) {
        String path = normalizePath(relativePath);
        List<String> args = new ArrayList<>(List.of("diff", "--no-color", "--unified=3"));
        if (to != null && !to.isBlank()) {
            args.add(from + ".." + to);
        } else {
            args.add(from);
        }
        args.add("--");
        args.add(path);
        Result r = git(userDir(userId), uidOf(userId), args.toArray(new String[0]));
        String text = r.text();
        if (text.contains(BINARY_MARKER)) {
            return new FileDiffResult(false, text, List.of());
        }
        return new FileDiffResult(true, text, parseUnifiedDiff(text));
    }

    /** 把某文件回滚到指定 commit 的版本(其余文件不动),并提交一次快照。 */
    public void restoreFile(Long userId, String relativePath, String sha) {
        String path = normalizePath(relativePath);
        Path dir = userDir(userId);
        int uid = uidOf(userId);
        Result show = git(dir, uid, "show", sha + ":" + path);
        if (show.exitCode != 0) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(),
                    "该版本中不存在文件或版本号无效: " + path);
        }
        Path target = workspaceService.resolveWritePath(userId, path);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, show.bytes);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "回滚写入失败");
        }
        snapshot(userId, "回滚 " + path + " 至 " + shortSha(sha));
    }

    // ============ 内部 ============

    private int uidOf(Long userId) {
        return props.uidOf(userId);
    }

    private Path userDir(Long userId) {
        return Path.of(workspaceService.userDir(userId));
    }

    private String normalizePath(String relativePath) {
        String p = relativePath == null ? "" : relativePath;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    private String sanitizeMessage(String message) {
        String m = message == null ? "" : message.replaceAll("[\\r\\n]+", " ").trim();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    private String shortSha(String sha) {
        return sha != null && sha.length() > 7 ? sha.substring(0, 7) : (sha == null ? "" : sha);
    }

    /** 以用户 uid 执行 git(workdir=用户工作区) */
    private Result git(Path dir, int uid, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("setpriv");
        cmd.add("--reuid=" + uid);
        cmd.add("--regid=" + uid);
        cmd.add("--clear-groups");
        cmd.add("--");
        cmd.add("git");
        cmd.addAll(List.of(args));
        return exec(cmd, dir, uid);
    }

    private Result exec(List<String> cmd, Path workdir, int uid) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        // setpriv 以目标 uid 运行,但 HOME 仍是 /root(不可读)→ git 会报
        // "unable to access '/root/.config/git/..."" 噪音,污染 diff 输出。
        // 显式把 HOME 指向该用户私有数据目录,并跳过系统级 git 配置读取。
        pb.environment().put("HOME", props.getPerUserDataRoot() + "/" + uid + "/home");
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        try {
            Process p = pb.start();
            byte[] out;
            try (var in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            int exit = p.waitFor();
            return new Result(exit, out);
        } catch (IOException e) {
            log.warn("git exec failed ({}): {}", workdir, e.toString());
            return new Result(-1, e.getMessage() == null ? "io error (binary)".getBytes(StandardCharsets.UTF_8)
                    : e.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 解析统一格式 diff 为行级列表(meta/hunk/add/del/ctx) */
    private List<DiffLine> parseUnifiedDiff(String raw) {
        List<DiffLine> result = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ")
                    || line.startsWith("index ")) {
                result.add(new DiffLine("meta", line));
            } else if (HUNK_HEADER.matcher(line).matches()) {
                result.add(new DiffLine("hunk", line));
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                result.add(new DiffLine("add", line));
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                result.add(new DiffLine("del", line));
            } else {
                result.add(new DiffLine("ctx", line));
            }
        }
        return result;
    }

    // ============ 数据类型 ============

    public record FileHistoryEntry(String sha, long timestamp, String message) {}

    /** 一次快照变更的文件:status 为 git 状态字(A/M/D/R/C 等),path 为相对工作区路径。 */
    public record ChangedFile(String status, String path) {}

    /** textDiff=false 表示二进制文件,无法按行渲染 */
    public record FileDiffResult(boolean textDiff, String raw, List<DiffLine> lines) {}

    public record DiffLine(String kind, String content) {}

    /** 进程执行结果;bytes 为原始字节(二进制安全),text() 按 UTF-8 解码 for 文本调用方。 */
    private static final class Result {
        final int exitCode;
        final byte[] bytes;

        Result(int exitCode, byte[] bytes) {
            this.exitCode = exitCode;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }

        String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}