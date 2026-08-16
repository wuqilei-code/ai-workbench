package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.dto.my.MyStatsVo;
import com.aiworkbench.service.MyStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 个人驾驶舱:当前登录用户自己的统计数据。 */
@RestController
@RequestMapping("/api/my")
@RequiredArgsConstructor
public class MyStatsController {

    private final MyStatsService myStatsService;

    /** 个人统计看板(概览 + 每日趋势 + agent/模型分布),默认最近 14 天。 */
    @GetMapping("/stats")
    public R<MyStatsVo> stats(@RequestParam(defaultValue = "14") int days) {
        return R.ok(myStatsService.dashboard(SecurityUtil.currentUserId(), days));
    }
}
