package com.aiworkbench.service.job;

import com.aiworkbench.common.ShellExec;
import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 定时任务隔离工作区:任务启动前把用户工作区复制成独立快照(排除 node_modules/.git 等重目录),
 * 任务在快照里跑,结束时把变更/新增文件回写到真实工作区,与在线会话互不干扰。
 * <p>
 * 位置:{workspaceRoot}/users/&lt;userId&gt;/jobs/&lt;jobId&gt;(在用户目录内,挂载进任务容器路径一致)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobWorkspaceService {

    private final OpencodeProperties props;
    private final WorkspaceService workspaceService;

    /** 排除的顶层目录名(重或系统目录,快照/回写均跳过) */
    private static final List<String> EXCLUDE_DIRS = List.of(
            "node_modules", ".git", ".opencode", ".gitignore", "tasks", "uploads", "jobs");

    /** 任务快照目录:{workspaceRoot}/users/&lt;userId&gt;/jobs/&lt;jobId&gt; */
    public Path jobDir(Long userId, Long jobId) {
        return Paths.get(props.getWorkspaceRoot(), "users",
                String.valueOf(userId), "jobs", String.valueOf(jobId));
    }

    /**
     * 准备任务快照工作区:复制真实工作区→任务目录,并初始化 git(任务 agent 需要 git 工作树做
     * 项目级技能发现与版本操作)。目录交由目标 uid 所有(任务容器以该 uid 运行)。
     * 返回任务工作目录。原样保留用户文件(含子目录结构)。
     */
    public Path prepare(Long userId, Long jobId) {
        Path src = Path.of(workspaceService.userDir(userId));
        Path dest = jobDir(userId, jobId);
        try {
            if (Files.isDirectory(dest)) {
                deleteRecursively(dest);
            }
            Files.createDirectories(dest);
        } catch (IOException e) {
            throw new IllegalStateException("创建任务快照目录失败: " + dest, e);
        }
        int uid = props.uidOf(userId);
        ShellExec.run("chmod", "0755", dest.getParent().toString()); // 中间层 jobs/ 需可 traverse
        copyTree(src, dest);
        // 初始化 git:技能发现要求 CWD 位于 git 工作树;身份与真实工作区一致
        ShellExec.run("git", "-c", "init.defaultBranch=main", "init", dest.toString());
        ShellExec.run("git", "-C", dest.toString(), "config", "user.email", "aiwb@local");
        ShellExec.run("git", "-C", dest.toString(), "config", "user.name", "aiwb");
        ShellExec.run("chown", "-R", uid + ":" + uid, dest.toString());
        // 初始 commit,使「运行前后差异」以 commit 为基线
        ShellExec.run("git", "-C", dest.toString(), "add", "-A", ".");
        return dest;
    }

    /**
     * 基线文件清单:任务启动前记录所有文件 (size,lastModified),供 {@link #collectChanges} 判定变更。
     */
    public Map<String, FileStamp> baseline(Path jobDir) {
        Map<String, FileStamp> map = new HashMap<>();
        walk(jobDir, (rel, p) -> {
            try {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                map.put(rel, new FileStamp(a.size(), a.lastModifiedTime().toMillis()));
            } catch (IOException ignored) {
            }
        });
        return map;
    }

    /**
     * 收集任务期间的变更(相对任务目录):新增或内容/时间变更的文件,并回写到真实工作区。
     * 删除的文件不回写(避免误删用户实时工作区);返回回写成功的相对路径列表。
     */
    public List<String> collectChanges(Long userId, Path jobDir, Map<String, FileStamp> baseline) {
        Map<String, FileStamp> after = new HashMap<>();
        walk(jobDir, (rel, p) -> {
            try {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                after.put(rel, new FileStamp(a.size(), a.lastModifiedTime().toMillis()));
            } catch (IOException ignored) {
            }
        });
        Path realRoot = Path.of(workspaceService.userDir(userId));
        int uid = props.uidOf(userId);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, FileStamp> e : after.entrySet()) {
            FileStamp before = baseline.get(e.getKey());
            if (before != null && before.equals(e.getValue())) {
                continue;
            }
            Path src = jobDir.resolve(e.getKey());
            Path dst = realRoot.resolve(e.getKey());
            try {
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                ShellExec.run("chown", uid + ":" + uid, dst.toString());
                changed.add(e.getKey());
            } catch (IOException ex) {
                log.warn("writeback {} failed: {}", e.getKey(), ex.toString());
            }
        }
        return changed;
    }

    /** 清理任务快照目录。 */
    public void cleanup(Long userId, Long jobId) {
        Path p = jobDir(userId, jobId);
        try {
            deleteRecursively(p);
        } catch (IOException e) {
            log.debug("cleanup job dir {} failed: {}", p, e.toString());
        }
    }

    private void copyTree(Path src, Path dest) {
        try (Stream<Path> stream = Files.walk(src)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.equals(src)) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (Files.isDirectory(p) && EXCLUDE_DIRS.contains(name)) {
                    continue;
                }
                Path rel = src.relativize(p);
                Path target = dest.resolve(rel.toString());
                try {
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    log.debug("copy {} into job refused: {}", p, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("job snapshot copy failed: {}", e.toString());
        }
    }

    private void walk(Path root, Walker visitor) {
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.equals(root)) {
                    continue;
                }
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String rel = root.relativize(p).toString().replace('\\', '/');
                boolean excluded = false;
                for (String d : EXCLUDE_DIRS) {
                    if (rel.equals(d) || rel.startsWith(d + "/")) {
                        excluded = true;
                        break;
                    }
                }
                if (excluded) {
                    continue;
                }
                visitor.visit(rel, p);
            }
        } catch (IOException e) {
            log.debug("walk {} failed: {}", root, e.toString());
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.deleteIfExists(dir);
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream.sorted(
                    (a, b) -> b.getNameCount() - a.getNameCount())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    private interface Walker {
        void visit(String rel, Path p);
    }

    /** 文件基线戳:size + mtime 判定是否变更。 */
    public record FileStamp(long size, long mtime) {
    }
}