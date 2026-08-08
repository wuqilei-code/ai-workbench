package com.aiworkbench.dto.chat;

import lombok.Builder;
import lombok.Data;

/** 后台任务状态项(tasks/&lt;id&gt;.json 等运行文件聚合;供「任务」面板展示)。 */
@Data
@Builder
public class TaskInfoVo {
    /** 任务 id(与 tasks/<id>.json 对应) */
    private String id;
    /** 状态:pending / running / done / failed / delivered */
    private String status;
    /** 状态中文描述(含 done 内容,如失败原因) */
    private String statusText;
    /** done 文件内容(完成/失败信息) */
    private String doneContent;
    /** 是否已由后台续跑交付过一次 */
    private boolean delivered;
    /** 任务发起时间(ms,json 文件 mtime) */
    private long startedAt;
    /** 完成/交付时间(ms,仅 done/delivered 有) */
    private Long finishedAt;
    /** 运行时长(秒;running 时为已运行秒数,否则为发起到完成秒数) */
    private Long runningSeconds;
    /** 日志尾部(列表返回短尾,详情返回全尾) */
    private String logTail;
}
