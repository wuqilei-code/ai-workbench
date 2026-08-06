package com.aiworkbench.service.user;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.entity.User;
import com.aiworkbench.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
    private final OpencodeProperties props;

    /** 系统账户名前缀:aiwb-<userId> */
    private static final String ACCT_PREFIX = "aiwb-";
    /** 禁止系统账户交互登录 */
    private static final String NOLOGIN_SHELL = "/usr/sbin/nologin";

    /**
     * 确保用户已完成 uid 隔离 provisioning。幂等:已 provisioned 则仅补齐目录权限,不重复 useradd。
     */
    public void ensureProvisioned(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "user not found: " + userId);
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
     *   <li>工作区 /workspace/users/&lt;userId&gt;/project —— 实际工作区(=userDir),git init + .opencode/skills;
     *       配置类文件(.git/.opencode/.gitignore)与用户文件同处此层,前端文件树隐藏配置项,页面只显示用户文件</li>
     *   <li>私有配置 home /data/users/&lt;uid&gt;/home/.config/opencode/agents —— Phase 2 PerUserConfigWriter 将 seed 配置</li>
     * </ul>
     * 均 chmod 0700 + chown -R uid:gid。
     */
    private void ensureDirectories(Long userId, int uid, int gid) {
        Path parent = Paths.get(props.getWorkspaceRoot(), "users", String.valueOf(userId));
        Path ws = parent.resolve("project");
        createDirs(ws); // 同时创建 parent
        // 把工作区初始化为 git 仓库:opencode 项目级技能发现(.opencode/skills/*/SKILL.md)
        // 要求 CWD 位于 git 工作树;此处以 root 幂等 init,随后 chown -R 修正 .git/.opencode 属主,
        // opencode 以 uid 运行时属主匹配,无 git "dubious ownership" 问题。顺带赋予 AI git 工具能力。
        initGitWorkspace(ws);
        // 预建技能目录,使文件区「技能」标签首启即有空目录可列
        createDirs(ws.resolve(".opencode/skills"));
        // 父目录与工作区均 0700 + chown:父目录隔离用户,工作区隔离配置与用户文件
        run("chmod", "0700", parent.toString());
        run("chmod", "0700", ws.toString());
        run("chown", "-R", uid + ":" + gid, parent.toString());

        Path home = Paths.get(props.getPerUserDataRoot(), String.valueOf(uid), "home");
        createDirs(home.resolve(".config/opencode/agents"));
        // opencode 会话存储/状态目录(XDG_DATA_HOME / XDG_STATE_HOME,见 OpencodeProcessManager.configureEnv):
        // 预建并随 home chown,进程空闲回收后重启仍能恢复旧会话上下文。
        createDirs(home.resolve(".local/share"));
        createDirs(home.resolve(".local/state"));
        run("chmod", "0700", home.toString());
        run("chown", "-R", uid + ":" + gid, home.toString());
    }

    /**
     * 幂等初始化工作区为 git 仓库 + 预置提交身份与 .gitignore(排除 uploads/ 二进制)。
     * 使 opencode 项目级技能发现生效;身份预置避免 AI 提交时报 "Please tell me who you are"。
     */
    private void initGitWorkspace(Path ws) {
        run("git", "-c", "init.defaultBranch=main", "init", ws.toString());
        run("git", "-C", ws.toString(), "config", "user.email", "aiwb@local");
        run("git", "-C", ws.toString(), "config", "user.name", "aiwb");
        Path gitignore = ws.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) {
            try {
                Files.writeString(gitignore, "uploads/\n");
            } catch (IOException e) {
                log.warn("write .gitignore failed for {}: {}", ws, e.toString());
            }
        }
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
