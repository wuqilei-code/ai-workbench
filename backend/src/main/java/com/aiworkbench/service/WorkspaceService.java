package com.aiworkbench.service;

import com.aiworkbench.config.OpencodeProperties;
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
 * 用户工作目录管理 + 文件操作(限定在 /workspace/users/<userId> 内)。
 * <p>
 * 隔离关键:所有 opencode 请求带 header x-opencode-directory: 该目录。
 * 文件读写均做路径越权校验,禁止逃逸出用户目录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final OpencodeProperties props;
    private final UserProvisioningService userProvisioningService;

    /**
     * 用户工作目录:/workspace/users/&lt;userId&gt;/project。
     * <p>
     * 在 userId 目录下再分一层 project/ 作为实际工作区,使 .git/.opencode/.gitignore 等
     * 配置类文件与用户文件同处 project/ 但由前端文件树隐藏,页面只展示用户文件。
     * opencode CWD、x-opencode-directory、文件 API 基准均由此派生,改此一处即全链路生效。
     */
    public String userDir(Long userId) {
        return Paths.get(props.getWorkspaceRoot(), "users", String.valueOf(userId), "project").toString();
    }

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

    /** 列出用户目录下文件树(单层)。path 相对用户目录,如 "" 表示根。 */
    public List<FileNode> listFiles(Long userId, String relativePath) {
        Path base = safeResolve(userId, relativePath);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<FileNode> nodes = new ArrayList<>();
        try (Stream<Path> stream = Files.list(base)) {
            stream.forEach(p -> {
                try {
                    nodes.add(new FileNode(
                            p.getFileName().toString(),
                            relativize(userId, p),
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
        walkSearch(base, q, userId, results, 300);
        results.sort((a, b) -> {
            int c = Boolean.compare(a.directory(), b.directory());
            if (c != 0) return -c;
            return a.name().compareTo(b.name());
        });
        return results;
    }

    /** 递归遍历(手动跳过系统目录子树,避免 Files.walk 钻进 .git 浪费开销) */
    private void walkSearch(Path dir, String q, Long userId, List<FileNode> results, int limit) {
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
                    results.add(new FileNode(name, relativize(userId, p), isDir,
                            isDir ? 0 : Files.size(p), Files.getLastModifiedTime(p).toMillis(),
                            creationTime(p)));
                } catch (IOException ignored) {}
            }
            if (isDir) walkSearch(p, q, userId, results, limit);
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

    public void writeFile(Long userId, String relativePath, String content) {
        Path path = safeResolve(userId, relativePath);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "write file failed");
        }
    }

    public void deleteFile(Long userId, String relativePath) {
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
        Path path = safeResolve(userId, relativePath);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "create directory failed");
        }
    }

    private Path safeResolve(Long userId, String relativePath) {
        Path base = Paths.get(userDir(userId)).toAbsolutePath().normalize();
        String rel = relativePath == null ? "" : relativePath;
        // 只做 normalize 后前缀校验:不因文件名含 ".."(如 foo..bar.txt)误伤合法文件;
        // 真正的目录逃逸(../ 跨出 base)由 startsWith(base) 拦截。
        Path resolved = base.resolve(rel).normalize();
        if (!resolved.startsWith(base)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "path traversal denied");
        }
        return resolved;
    }

    /** 供快照/回滚等写入场景使用:同 safeResolve 校验,返回已校验的工作区路径。 */
    public Path resolveWritePath(Long userId, String relativePath) {
        return safeResolve(userId, relativePath);
    }

    private String relativize(Long userId, Path absolute) {
        Path base = Paths.get(userDir(userId)).toAbsolutePath().normalize();
        return base.relativize(absolute.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
