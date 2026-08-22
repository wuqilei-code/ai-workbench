package com.aiworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户工作区 / uid 隔离配置(deepagents 单轨)。
 * <p>
 * 工作区=/workspace/users/&lt;userId&gt;/project(共享卷,agent-service 与沙箱容器挂载同源);
 * 每用户 uid 确定性分配 uidBase + userId(沙箱/任务在容器内以该 uid 运行)。
 */
@Data
@ConfigurationProperties(prefix = "workspace")
public class WorkspaceProperties {
    /** 用户工作区根目录,每用户子目录为 <root>/users/<userId>/project */
    private String root = "/workspace";
    /** 每用户私有数据根(历史遗留布局,仍用于按 uid 定位 home 目录) */
    private String perUserDataRoot = "/data/users";
    /** uid 分配基数:实际 uid = uidBase + user.id(确定性,免协调) */
    private int uidBase = 100000;

    /** uid = uidBase + userId(确定性,免协调;与 UserProvisioningService 一致) */
    public int uidOf(Long userId) {
        return uidBase + userId.intValue();
    }
}