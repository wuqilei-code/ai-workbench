package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String email;
    private String password;
    /** user | admin */
    private String role;
    /** 1=active, 0=disabled */
    private Integer status;

    /** Phase 1 per-user uid 隔离:uid = uidBase + id,确定性分配;注册后由 UserProvisioningService 回填 */
    private Integer uid;

    /** agent 运行时偏好:opencode(默认,可空) | dsh;非空时覆盖全局 OPENCODE_AGENT_RUNTIME(见 OpencodeProcessManager.resolveAgentRuntime) */
    private String agentRuntime;

    /** 时间戳由 DB 默认值/ON UPDATE 管理,update 时 NEVER 不写入,避免覆盖自动值 */
    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
