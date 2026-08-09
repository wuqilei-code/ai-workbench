package com.aiworkbench.dto.chat;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 技能广场条目:列表/详情统一返回。 */
@Data
@Builder
public class MarketplaceSkillVo {
    private Long id;
    /** 发布者用户名 */
    private String authorName;
    private String name;
    private String description;
    /** 分类标签:dev/doc/data/writing/office/other */
    private String category;
    /** 展示图标(emoji 或短语) */
    private String icon;
    /** SKILL.md 全文(详情用;列表可省略) */
    private String content;
    /** 附加文件(SKILL.md 之外的脚本/数据等;仅详情返回) */
    private java.util.List<SkillFileVo> files;
    private int installs;    /** 当前用户是否已安装 */
    private boolean installed;
    /** 当前用户是否自己的作品 */
    private boolean mine;
    private LocalDateTime updatedAt;
}
