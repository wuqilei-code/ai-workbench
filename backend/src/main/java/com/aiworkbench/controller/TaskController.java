package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.dto.chat.TaskInfoVo;
import com.aiworkbench.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 后台任务状态接口(tasks/ 目录运行文件聚合),供「任务」面板展示/查看日志/清理。 */
@RestController
@RequestMapping("/api/chat/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    /** 任务列表(含状态、时长、日志短尾),按发起时间倒序。 */
    @GetMapping
    public R<List<TaskInfoVo>> list() {
        return R.ok(taskService.listTasks(SecurityUtil.currentUserId()));
    }

    /** 任务日志尾部。 */
    @GetMapping("/{id}/log")
    public R<String> log(@PathVariable String id, @RequestParam(required = false) Integer tail) {
        return R.ok(taskService.taskLog(SecurityUtil.currentUserId(), id, tail));
    }

    /** 清理任务运行文件(json/log/pid/done/delivered)。 */
    @DeleteMapping("/{id}")
    public R<Void> cleanup(@PathVariable String id) {
        taskService.cleanupTask(SecurityUtil.currentUserId(), id);
        return R.ok();
    }
}
