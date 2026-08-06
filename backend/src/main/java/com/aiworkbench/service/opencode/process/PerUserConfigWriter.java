package com.aiworkbench.service.opencode.process;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.common.ShellExec;
import com.aiworkbench.config.OpencodeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 每用户 opencode 配置写入器:把模板真相源({@code /data/opencode/template/},root 0700)
 * 拷贝到用户私有配置目录 {@code /data/users/<uid>/home/.config/opencode/},并 chown 给该 uid、
 * opencode.env 强制 0600。
 * <p>
 * 用途:
 * <ul>
 *   <li>{@link OpencodeProcessManager#start} 首启前 {@link #seed} 播种</li>
 *   <li>{@code ProviderConfigService.applyConfig} 扇出时 {@link #pushConfig} 推送新配置</li>
 * </ul>
 * 幂等且覆盖式:每次都从模板全量拷贝,保证用户副本与真相源一致。
 * <p>
 * 仅在容器内以 root 运行(读 0700 模板 + chown 依赖)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerUserConfigWriter {

    private final OpencodeProperties props;

    /**
     * 从模板拷贝 opencode.json / opencode.env / agents/chat.md 到用户配置目录,
     * chown uid:gid,env chmod 0600,json/agents 0644。覆盖式、幂等。
     */
    public void seed(Long userId) {
        int uid = props.uidOf(userId);
        int gid = uid;
        Path tplDir = Paths.get(props.getTemplateDir());
        Path cfgDir = userConfigDir(uid);
        try {
            Files.createDirectories(cfgDir);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "create config dir failed: " + cfgDir);
        }

        copyIfExists(tplDir.resolve("opencode.json"), cfgDir.resolve("opencode.json"));
        copyIfExists(tplDir.resolve("opencode.env"), cfgDir.resolve("opencode.env"));
        // agents
        Path tplAgents = tplDir.resolve("agents");
        if (Files.isDirectory(tplAgents)) {
            Path cfgAgents = cfgDir.resolve("agents");
            try {
                Files.createDirectories(cfgAgents);
            } catch (IOException e) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "create agents dir failed: " + cfgAgents);
            }
            copyIfExists(tplAgents.resolve("chat.md"), cfgAgents.resolve("chat.md"));
        }
        // skills(内置全局技能,如 skill-creator;opencode 经 skill 工具从 ~/.config/opencode/skills/ 发现)
        // 整树拷贝(skills/<name>/SKILL.md),后续 chown -R 统一修正属主。
        Path tplSkills = tplDir.resolve("skills");
        if (Files.isDirectory(tplSkills)) {
            Path cfgSkills = cfgDir.resolve("skills");
            try {
                Files.createDirectories(cfgSkills);
            } catch (IOException e) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "create skills dir failed: " + cfgSkills);
            }
            ShellExec.run("cp", "-r", tplSkills.toString() + "/.", cfgSkills.toString());
        }

        // 整体 chown 给该 uid(含拷贝出来的 root 属主文件)
        ShellExec.run("chown", "-R", uid + ":" + gid, cfgDir.toString());
        // 显式权限:env 0600(明文 key 仅本 uid),json/agents 0644
        ShellExec.run("chmod", "0600", cfgDir.resolve("opencode.env").toString());
        ShellExec.run("chmod", "0644", cfgDir.resolve("opencode.json").toString());
        Path chatMd = cfgDir.resolve("agents/chat.md");
        if (Files.exists(chatMd)) {
            ShellExec.run("chmod", "0644", chatMd.toString());
        }
        log.debug("seeded opencode config for user {} (uid={}) -> {}", userId, uid, cfgDir);
    }

    /** applyConfig 扇出用:语义同 {@link #seed}(覆盖式推送最新模板)。 */
    public void pushConfig(Long userId) {
        seed(userId);
    }

    private Path userConfigDir(int uid) {
        return Paths.get(props.getPerUserDataRoot(), String.valueOf(uid), "home", ".config", "opencode");
    }

    private void copyIfExists(Path src, Path dst) {
        if (!Files.isRegularFile(src)) {
            return;
        }
        try {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(),
                    "copy config failed: " + src + " -> " + dst);
        }
    }
}
