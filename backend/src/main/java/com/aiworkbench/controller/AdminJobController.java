package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.service.job.JobPauseState;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 定时任务全局暂停开关(管理员):暂停后不再触发/派发新任务,运行中的照常完成。 */
@RestController
@RequestMapping("/api/admin/jobs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminJobController {

    private final JobPauseState pauseState;

    @GetMapping("/paused")
    public R<Boolean> paused() {
        return R.ok(pauseState.isPaused());
    }

    @PostMapping("/paused")
    public R<Boolean> setPaused(@RequestBody Map<String, Object> body) {
        pauseState.setPaused(Boolean.TRUE.equals(body.get("paused")));
        return R.ok(pauseState.isPaused());
    }
}
