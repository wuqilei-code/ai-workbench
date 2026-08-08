package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.dto.admin.UsageStatsVo;
import com.aiworkbench.service.AdminStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    /** 用量统计看板数据(概览 + 每日序列 + Top 用户)。 */
    @GetMapping("/stats/usage")
    public R<UsageStatsVo> usage(@RequestParam(defaultValue = "14") int days) {
        return R.ok(adminStatsService.usage(days));
    }
}
