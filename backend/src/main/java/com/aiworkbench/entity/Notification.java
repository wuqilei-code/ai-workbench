package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 系统消息中心:定时任务等后台事件产生的用户消息(右上角铃铛)。 */
@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 消息类型:task(定时任务)/system */
    private String type;

    private String title;

    private String content;

    /** 跳转链接(任务报告文件路径或会话地址) */
    private String link;

    /** 0=未读 1=已读 */
    private Integer readFlag;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
