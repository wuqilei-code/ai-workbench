package com.aiworkbench.service;

import com.aiworkbench.config.WorkspaceProperties;
import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.service.user.UserProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 用户工作目录管理 + 文件操作(限定在 /workspace/users/<userId>/project 内)。
 * <p>
 * 隔离关键:所有文件读写均做路径越权校验,禁止逃逸出用户目录。
 * 工作区与 agent-service 沙箱共享同一卷(见 compose AGENT_SANDBOX_VOLUME_ROOT),
 * 用户在此目录上传/编辑的文件即时对 agent 可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceProperties props;
    private final UserProvisioningService userProvisioningService;

    /**
     * 用户工作目录:/workspace/users/&lt;userId&gt;/project。
     * <p>
     * 在 userId 目录下再分一层 project/ 作为实际工作区,使 .git/.opencode/.gitignore 等
     * 配置类文件与用户文件同处 project/ 但由前端文件树隐藏,页面只展示用户文件。
     * opencode CWD、x-opencode-directory、文件 API 基准均由此派生,改此一处即全链路生效。
     */
    public String userDir(Long userId) {
        return Paths.get(props.getRoot(), "users", String.valueOf(userId), "project").toString();
    }

    /** deepagents 技能根(用户目录的兄弟):users/<userId>/skills —— agent 实际加载的 /skills。 */
    public String skillsDir(Long userId) {
        return Paths.get(props.getRoot(), "users", String.valueOf(userId), "skills").toString();
    }

    /** 用于 deepagents 技能根的虚拟根标记(相对路径以 "skills/" 开头即解析到技能根)。 */
    private static final String SKILLS_VIRTUAL = "skills/";

    /** 按虚拟根选择基目录并归一化返回 (base, 去掉虚拟前缀后的相对路径)。 */
    private Base resolveBase(Long userId, String relativePath) {
        Path base;
        String rel = relativePath == null ? "" : relativePath;
        if (rel.startsWith(SKILLS_VIRTUAL) || rel.equals("skills")) {
            base = Paths.get(skillsDir(userId)).toAbsolutePath().normalize();
            rel = rel.equals("skills") ? "" : rel.substring(SKILLS_VIRTUAL.length());
        } else {
            base = Paths.get(userDir(userId)).toAbsolutePath().normalize();
        }
        return new Base(base, rel);
    }

    /**
     * 技能文件写闸(硬性约束):skills/ 下的一切新增/更新/删除必须持有同步令牌,
     * 而令牌只由会同步写 user_skill 表的服务(UserSkillService/SkillMarketplaceService.install)打开,
     * 保证"技能文件动了 ⇒ 数据库必有记录",任何旁路(文件树 API/上传/快照等)一律拒绝。
     */
    private final ThreadLocal<Boolean> skillSyncFlag = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 打开技能同步令牌;必须用 try-with-resources 确保关闭。仅限会同步入库的技能服务调用。 */
    public SkillSyncScope openSkillSync() {
        skillSyncFlag.set(Boolean.TRUE);
        return () -> skillSyncFlag.set(Boolean.FALSE);
    }

    /** 技能同步令牌句柄(try-with-resources 用)。 */
    public interface SkillSyncScope extends AutoCloseable {
        @Override
        void close();
    }

    private void assertSkillSyncAllowed(String relativePath) {
        String rel = relativePath == null ? "" : relativePath;
        boolean touchesSkills = rel.startsWith(SKILLS_VIRTUAL) || rel.equals("skills");
        if (touchesSkills && !Boolean.TRUE.equals(skillSyncFlag.get())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(),
                    "技能目录受保护:新增/更新必须经技能服务同步保存到数据库");
        }
    }

    private record Base(Path dir, String rel) {}

    /**
     * 确保用户工作目录存在并完成 uid 隔离 provisioning(登录/首次建会话时调用)。
     * <p>
     * Phase 1 起委托 {@link UserProvisioningService#ensureProvisioned}:分配 uid、创建系统账户、
     * workspace 0700 + chown。幂等。
     */
    public void ensureUserWorkspace(Long userId) {
        userProvisioningService.ensureProvisioned(userId);
    }

    public record FileNode(String name, String path, boolean directory, long size, long modified, long created) {}

    /** 列出用户目录下文件树(单层)。path 相对用户目录(或 "skills/" 技能根)。 */
    public List<FileNode> listFiles(Long userId, String relativePath) {
        Base baseRel = resolveBase(userId, relativePath);
        Path base = baseRel.dir();
        Path dir = safeResolve(base, baseRel.rel());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<FileNode> nodes = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                try {
                    nodes.add(new FileNode(
                            p.getFileName().toString(),
                            relativize(base, p),
                            Files.isDirectory(p),
                            Files.size(p),
                            Files.getLastModifiedTime(p).toMillis(),
                            creationTime(p)));
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "list files failed");
        }
        // 目录优先,再按名字排序
        nodes.sort((a, b) -> {
            int c = Boolean.compare(a.directory(), b.directory());
            if (c != 0) return -c;
            return a.name().compareTo(b.name());
        });
        return nodes;
    }

    /** 读取文件创建时间(ms);部分文件系统(如 overlayfs)不暴露 birthtime,回退到修改时间。 */
    private long creationTime(Path p) {
        try {
            return Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class)
                    .creationTime().toMillis();
        } catch (IOException e) {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException ignored) {
                return 0L;
            }
        }
    }

    /** 递归搜索 root 下文件名含 query 的节点(跳过 .git/.opencode/node_modules 子树,限定结果数)。
     *  <p>path 字段为相对用户工作区根的路径,与 listFiles 一致,前端可直接用于打开/预览。 */
    public List<FileNode> searchFiles(Long userId, String root, String query) {
        if (query == null || query.trim().isEmpty()) return List.of();
        Path base = safeResolve(userId, root);
        if (!Files.isDirectory(base)) return List.of();
        String q = query.toLowerCase().trim();
        List<FileNode> results = new ArrayList<>();
        walkSearch(base, q, base, results, 300);
        results.sort((a, b) -> {
            int c = Boolean.compare(a.directory(), b.directory());
            if (c != 0) return -c;
            return a.name().compareTo(b.name());
        });
        return results;
    }

    /** 递归遍历(手动跳过系统目录子树,避免 Files.walk 钻进 .git 浪费开销) */
    private void walkSearch(Path dir, String q, Path base, List<FileNode> results, int limit) {
        if (results.size() >= limit) return;
        List<Path> children;
        try (Stream<Path> stream = Files.list(dir)) {
            children = stream.toList();
        } catch (IOException e) {
            return;
        }
        for (Path p : children) {
            if (results.size() >= limit) return;
            String name = p.getFileName().toString();
            boolean isDir;
            try {
                isDir = Files.isDirectory(p);
            } catch (Exception e) {
                continue;
            }
            // 跳过系统/隐藏文件(与前端正常浏览过滤一致:.git/.opencode/.gitignore);
            // node_modules 子树也跳过(依赖文件多且无意义)
            if (name.equals(".git") || name.equals(".opencode") || name.equals(".gitignore")
                    || (isDir && name.equals("node_modules"))) {
                continue;
            }
            if (name.toLowerCase().contains(q)) {
                try {
                    results.add(new FileNode(name, relativize(base, p), isDir,
                            isDir ? 0 : Files.size(p), Files.getLastModifiedTime(p).toMillis(),
                            creationTime(p)));
                } catch (IOException ignored) {}
            }
            if (isDir) walkSearch(p, q, base, results, limit);
        }
    }

    public String readFile(Long userId, String relativePath) {
        Path path = safeResolve(userId, relativePath);
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "read file failed");
        }
    }

    /** 技能目录是否已落盘(含 SKILL.md);用于「库里有、盘上没有」的自愈判断。只读,不受技能写闸限制。 */
    public boolean skillDirExists(Long userId, String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return false;
        }
        try {
            return Files.isRegularFile(Paths.get(skillsDir(userId)).resolve(name).resolve("SKILL.md"));
        } catch (Exception e) {
            return false;
        }
    }

    public void writeFile(Long userId, String relativePath, String content) {
        assertSkillSyncAllowed(relativePath);
        Path path = safeResolve(userId, relativePath);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "write file failed");
        }
    }

    /** 写二进制文件(技能附加文件中的图片/数据等,须持技能同步令牌)。 */
    public void writeBinaryFile(Long userId, String relativePath, byte[] content) {
        assertSkillSyncAllowed(relativePath);
        Path path = safeResolve(userId, relativePath);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "write binary file failed");
        }
    }

    public void deleteFile(Long userId, String relativePath) {
        assertSkillSyncAllowed(relativePath);
        Path path = safeResolve(userId, relativePath);
        if (!Files.exists(path)) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                // 递归删除目录及其内容(路径已由 safeResolve 校验未逃逸用户目录)
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "delete file failed");
        }
    }

    /** 下载文件:返回已校验的 Path(供 Controller 包装为流返回) */
    public Path getFilePath(Long userId, String relativePath) {
        Path path = safeResolve(userId, relativePath);
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return path;
    }

    /** 上传文件:将输入流写入用户目录下指定子目录 */
    public void uploadFile(Long userId, String dir, String filename, InputStream data) {
        String rel = (dir == null || dir.isBlank()) ? filename : dir + "/" + filename;
        assertSkillSyncAllowed(rel);
        Path path = safeResolve(userId, rel);
        try {
            Files.createDirectories(path.getParent());
            Files.copy(data, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "upload file failed");
        }
    }

    /**
     * 上传 ZIP 项目包并解压到 {@code dir} 目录下。
     * <p>
     * 防路径逃逸:每个条目解压前用 safeResolve 校验并 normalize,目标必须仍位于用户工作区内;
     * 拒绝绝对路径、包含 ".." 或空条目。返回解压出的文件数(便于前端提示)。
     */
    public int uploadZip(Long userId, String dir, InputStream data) {
        assertSkillSyncAllowed(dir);
        Path base = safeResolve(userId, dir == null || dir.isBlank() ? "uploads" : dir);
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "create zip dir failed");
        }
        int count = 0;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(data)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isBlank() || name.startsWith("/")) {
                    continue;
                }
                // 拒绝任何含 .. 的条目(含 Windows 反斜杠变体),防 zip-slip
                if (name.contains("..") || name.contains("\\")) {
                    continue;
                }
                Path target = safeResolve(userId,
                        (dir == null || dir.isBlank() ? "uploads" : dir) + "/" + name);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "unzip failed");
        }
        return count;
    }

    /** 新建目录(支持多级) */
    public void createDirectory(Long userId, String relativePath) {
        assertSkillSyncAllowed(relativePath);
        Path path = safeResolve(userId, relativePath);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "create directory failed");
        }
    }

    private Path safeResolve(Long userId, String relativePath) {
        Base baseRel = resolveBase(userId, relativePath);
        return safeResolve(baseRel.dir(), baseRel.rel());
    }

    private Path safeResolve(Path base, String rel) {
        rel = rel == null ? "" : rel;
        // 只做 normalize 后前缀校验:不因文件名含 ".."(如 foo..bar.txt)误伤合法文件;
        // 真正的目录逃逸(../ 跨出 base)由 startsWith(base) 拦截。
        Path resolved = base.resolve(rel).normalize();
        if (!resolved.startsWith(base)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "path traversal denied");
        }
        return resolved;
    }

    /** 写入场景使用:同 safeResolve 校验,返回已校验的工作区路径。 */
    public Path resolveWritePath(Long userId, String relativePath) {
        assertSkillSyncAllowed(relativePath);
        return safeResolve(userId, relativePath);
    }

    private String relativize(Path base, Path absolute) {
        return base.relativize(absolute.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
