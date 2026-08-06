package com.aiworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "opencode")
public class OpencodeProperties {
    /** opencode serve 的 HTTP 地址 */
    private String baseUrl;
    /** HTTP Basic 认证用户名(对应 OPENCODE_SERVER_USERNAME) */
    private String username;
    /** HTTP Basic 认证密码(对应 OPENCODE_SERVER_PASSWORD) */
    private String password;
    /** 用户工作区根目录,每用户子目录为 <workspaceRoot>/users/<userId> */
    private String workspaceRoot = "/workspace";
    /** opencode 配置数据目录(opencode.json + opencode.env),apply 接口写入此处 */
    private String configDir = "/data/opencode";

    // ---- Phase 1:per-user uid 隔离配置(Phase 2 起的进程管理也会用到 portMin/Max、idleTimeout) ----
    /** uid 分配基数:实际 uid = uidBase + user.id(确定性,免协调) */
    private int uidBase = 100000;
    /** 每用户 opencode 进程监听端口下限 */
    private int portMin = 14100;
    /** 每用户 opencode 进程监听端口上限(支持 300 个并发实例) */
    private int portMax = 14399;
    /** 空闲回收阈值(秒),Phase 2 的 idleSweep 用;Phase 1 仅声明 */
    private int idleTimeoutSeconds = 900;
    /** 每用户私有数据根:/data/users/<uid>/home/.config/opencode/ */
    private String perUserDataRoot = "/data/users";
    /** 配置模板真相源:/data/opencode/template/(root 0700),applyConfig 渲染、PerUserConfigWriter 拷贝 */
    private String templateDir = "/data/opencode/template";

    /** uid = uidBase + userId(确定性,免协调;与 UserProvisioningService 一致) */
    public int uidOf(Long userId) {
        return uidBase + userId.intValue();
    }
}
