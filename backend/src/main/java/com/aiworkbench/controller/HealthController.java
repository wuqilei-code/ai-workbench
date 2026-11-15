package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。
 * <p>
 * deepagents 单轨:报告网关健康与执行后端类型;无需 per-user 进程/事件统计。
 * 兼容既有烟测键:app = healthy。
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    /** 会话压缩触发阈值配置(0=按模型 profile 自动);前端统计弹窗显示为「压缩阈值」 */
    @Value("${app.compression.trigger-tokens:100000}")
    private long compressionTriggerTokens;

    @GetMapping
    public R<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", "healthy");
        data.put("agentBackend", "deepagents");
        data.put("compressionTriggerTokens", compressionTriggerTokens);
        return R.ok(data);
    }
}