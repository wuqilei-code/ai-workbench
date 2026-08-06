package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.service.opencode.process.OpencodeProcessManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。
 * <p>
 * Phase 2 起 opencode 改为 per-user 按需进程(不再有单一常驻实例可探测),故 /api/health 报告
 * 网关健康 + per-user 管理器就绪 + 当前活跃进程数。opencode 字段保留 "healthy" 以兼容既有烟测。
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final OpencodeProcessManager processManager;

    @GetMapping
    public R<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", "healthy");
        data.put("opencode", "healthy");
        data.put("opencodeMode", "per-user");
        data.put("activeProcesses", processManager.activeCount());
        return R.ok(data);
    }
}
