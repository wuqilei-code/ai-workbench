package com.aiworkbench.service.user;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.config.WorkspaceProperties;
import com.aiworkbench.entity.User;
import com.aiworkbench.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Phase 1:per-user uid 隔离 provisioning。
 * <p>
 * 为每个用户确定性分配 uid = uidBase + userId,创建系统账户 aiwb-<userId>(nologin shell),
 * 创建 0700 私有工作目录与私有配置 home 目录并 chown 给该 uid。
 * <p>
 * Phase 1 仍跑单 root opencode 进程,本服务仅打好目录权限地基;真正的进程级隔离在 Phase 2 生效
 * (opencode 以 setpriv --reuid=&lt;uid&gt; 启动,workspace 0700 + uid 阻断跨用户读写)。
 * <p>
 * 幂等:uid 已分配则跳过 useradd;目录已存在则仅补齐 chmod/chown。被 UserService.register/login
 * 与 WorkspaceService.ensureUserWorkspace 调用。仅在容器内以 root 运行(useradd/chmod/chown 依赖)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProvisioningService {

    private final UserMapper userMapper;
    private final WorkspaceProperties props;

    /** 系统账户名前缀:aiwb-<userId> */
    private static final String ACCT_PREFIX = "aiwb-";
    /** 禁止系统账户交互登录 */
    private static final String NOLOGIN_SHELL = "/usr/sbin/nologin";
    /** 陈旧工作区归档根目录名(位于 workspace root 下、users/ 之外) */
    private static final String QUARANTINE_DIR = "_quarantine";
    private static final java.time.format.DateTimeFormatter STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 注册专用防护:杜绝 userId 复用导致的跨用户数据可见。
     * <p>
     * 背景:工作区与 uid 均按 userId 确定性派生,而 userId 可能被复用 —— MySQL AUTO_INCREMENT
     * 在行被删除后会回收 id,调试脚本也可能提前对未注册的 userId 跑过 provisioning。此时新注册
     * 用户的工作区里会残留上一任(或测试)的 project 文件、skills 自建技能、.memory 记忆,
     * 甚至 agent 会直接加载上一任的技能 —— 属数据隔离缺陷。
     * <p>
     * 策略:全新注册用户本不该有任何历史目录,因此 users/&lt;id&gt; 与 /data/users/&lt;uid&gt; 只要存在
     * 即视为陈旧,整目录移动到同卷的 _quarantine/ 下归档(不删除,保留可追溯性;归档 0700 且位于
     * users/ 之外,任何用户工作区都无法触达)。随后 {@link #ensureProvisioned} 重建干净目录。
     * <p>
     * 仅注册路径调用;登录路径绝不能调用(那里是用户真实数据)。
     */
    public void quarantineStaleWorkspace(Long userId) {
        if (java.io.File.separatorChar == '\\') {
            return; // Windows 本地开发无 uid 隔离,跳过
        }
        int uid = props.getUidBase() + userId.intValue();
        Path staleUsers = Paths.get(props.getRoot(), "users", String.valueOf(userId));
        Path staleHome = Paths.get(props.getPerUserDataRoot(), String.valueOf(uid));
        if (!Files.exists(staleUsers) && !Files.exists(staleHome)) {
            return;
        }
        // 安全阀:目录含任何用户文件,说明曾被真实会话使用过。
        // 此时不自动归档(避免 DB 异常等场景下误丢用户数据),抛错交由管理员确认。
        // 调试残留 / 已删用户的工作区通常为空,可安全自动归档。
        if (hasUserFiles(staleUsers.resolve("project"))) {
            log.error("refusing to quarantine non-empty workspace for registering user id={}", userId);
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(),
                    "工作区初始化失败:检测到该用户目录已有历史数据,已阻止自动清理,请联系管理员");
        }
        // /workspace 与 /data 是相互独立的卷:跨卷 rename 会失败(EXDEV / DirectoryNotEmpty),
        // 因此归档目录必须与被归档内容同卷 —— 各卷下建 _quarantine/<键>-<时间戳>/。
        String stamp = java.time.LocalDateTime.now().format(STAMP);
        try {
            if (Files.exists(staleUsers)) {
                Path a = Paths.get(props.getRoot(), QUARANTINE_DIR, userId + "-" + stamp);
                Files.createDirectories(a);
                moveDir(staleUsers, a.resolve("users"));
                lockDown(a);
                log.warn("quarantined stale workspace for registering user id={} -> {}", userId, a);
            }
            if (Files.exists(staleHome)) {
                Path a = Paths.get(props.getPerUserDataRoot(), QUARANTINE_DIR, uid + "-" + stamp);
                Files.createDirectories(a);
                moveDir(staleHome, a.resolve("uid"));
                lockDown(a);
                log.warn("quarantined stale per-user data for registering user id={} uid={} -> {}",
                        userId, uid, a);
            }
        } catch (IOException e) {
            // 归档失败时阻断注册:宁可拒绝复用脏目录,也不能让新用户看到别人的数据。
            log.error("failed to quarantine stale dirs for user id={}: {}", userId, e.toString());
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(),
                    "工作区初始化失败:检测到历史残留数据且归档失败,请联系管理员");
        }
    }

    /** 归档内容仅 root 可读,阻断任何用户进程(含 agent 沙箱)触达。 */
    private void lockDown(Path archive) {
        run("chmod", "-R", "go-rwx", archive.toString());
    }

    /**
     * 工作区是否已有任何用户文件(非空即视为有历史数据)。隐藏配置项忽略;
     * 空目录/仅隐藏文件按"无数据"处理,以便调试残留能被自动归档(保守方向:归档失败会抛错阻断注册,不会静默丢数据)。
     */
    private boolean hasUserFiles(Path ws) {
        if (!Files.isDirectory(ws)) {
            return false;
        }
        try (var stream = Files.walk(ws)) {
            return stream.anyMatch(p -> Files.isRegularFile(p) && !p.getFileName().toString().startsWith("."));
        } catch (IOException e) {
            log.warn("scan workspace failed for {}: {}", ws, e.toString());
            return false;
        }
    }

    /** 目录移动:优先原子 rename,跨文件系统时退化为 copy+delete。 */
    private void moveDir(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    /**
     * 确保用户已完成 uid 隔离 provisioning。幂等:已 provisioned 则仅补齐目录权限,不重复 useradd。
     */
    public void ensureProvisioned(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "user not found: " + userId);
        }

        // Windows 本地开发:无 useradd/groupadd/chown,uid 隔离是 Linux 独有特性,直接跳过。
        // 生产为容器内 root(Linux),不受影响。
        if (java.io.File.separatorChar == '\\') {
            log.info("skip user provisioning on Windows (uid isolation is Linux-only)");
            return;
        }

        int uid = props.getUidBase() + userId.intValue();
        int gid = uid; // gid 同 uid,确定性
        String acct = ACCT_PREFIX + userId;

        // 1. 系统账户(幂等)
        ensureSystemAccount(acct, uid, gid);

        // 2. 私有目录(幂等):workspace + per-user config home
        ensureDirectories(userId, uid, gid);

        // 3. 回填 users.uid(仅老用户首次;新用户注册后 uid=NULL)
        if (user.getUid() == null) {
            User upd = new User();
            upd.setId(userId);
            upd.setUid(uid);
            userMapper.updateById(upd);
            log.info("provisioned uid for user id={} -> uid={} acct={}", userId, uid, acct);
        }
    }

    /** 查询/创建系统账户 + 同名组(gid=uid)。幂等:getent 命中则跳过。 */
    private void ensureSystemAccount(String acct, int uid, int gid) {
        String home = Paths.get(props.getPerUserDataRoot(), String.valueOf(uid), "home").toString();

        if (!exists("group", acct)) {
            run("groupadd", "-g", String.valueOf(gid), acct);
        }
        if (!exists("passwd", acct)) {
            int code = run("useradd",
                    "-u", String.valueOf(uid),
                    "-g", String.valueOf(gid),
                    "-M",
                    "-d", home,
                    "-s", NOLOGIN_SHELL,
                    acct);
            if (code != 0 && !exists("passwd", acct)) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(),
                        "useradd failed for " + acct + " (uid=" + uid + ")");
            }
        }
    }

    /**
     * 创建并权限化目录:
     * <ul>
     *   <li>父目录 /workspace/users/&lt;userId&gt; —— 隔离容器,0700</li>
     *   <li>工作区 /workspace/users/&lt;userId&gt;/project —— 实际工作区(=userDir)。
     *       (deepagents 架构,技能已移至 users/&lt;id&gt;/skills,不再创建 .opencode;
     *       也不再 git init — 省去 .git 的磁盘占用与维护成本)</li>
     *   <li>私有配置 home /data/users/&lt;uid&gt;/home/.config/opencode/agents —— Phase 2 PerUserConfigWriter 将 seed 配置</li>
     * </ul>
     * 均 chmod 0700 + chown -R uid:gid。
     */
    private void ensureDirectories(Long userId, int uid, int gid) {
        Path parent = Paths.get(props.getRoot(), "users", String.valueOf(userId));
        Path ws = parent.resolve("project");
        createDirs(ws); // 同时创建 parent
        // 父目录与工作区均 0700 + chown:父目录隔离用户,工作区隔离配置与用户文件
        run("chmod", "0700", parent.toString());
        run("chmod", "0700", ws.toString());
        run("chown", "-R", uid + ":" + gid, parent.toString());

        Path home = Paths.get(props.getPerUserDataRoot(), String.valueOf(uid), "home");
        createDirs(home);
        run("chmod", "0700", home.toString());
        run("chown", "-R", uid + ":" + gid, home.toString());
    }

    private void createDirs(Path p) {
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "create dir failed: " + p);
        }
    }

    /** getent &lt;db&gt; &lt;name&gt;:exit 0=存在,非 0=不存在 */
    private boolean exists(String db, String name) {
        return run("getent", db, name) == 0;
    }

    /** 执行命令,返回 exit code;非 0 仅 warn(幂等场景下重复创建/重复 chmod 属预期)。 */
    private int run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                log.warn("cmd '{}' exit={} out={}", String.join(" ", cmd), code, out.trim());
            }
            return code;
        } catch (IOException e) {
            log.error("cmd '{}' io error: {}", String.join(" ", cmd), e.toString());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("cmd '{}' interrupted", String.join(" ", cmd));
            return -1;
        }
    }
}
