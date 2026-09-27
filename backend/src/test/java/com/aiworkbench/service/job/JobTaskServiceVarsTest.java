package com.aiworkbench.service.job;

import com.aiworkbench.entity.JobTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JobTaskService 任务变量(JSON 解析 + ${key} 替换)单元测试(无 Spring 上下文、无 DB)。
 */
class JobTaskServiceVarsTest {

    private final JobTaskService svc = new JobTaskService(null, null, new ObjectMapper());

    @Test
    void parseVars_nullOrBlank_returnsEmpty() {
        assertTrue(svc.parseVars(null).isEmpty());
        assertTrue(svc.parseVars("  ").isEmpty());
    }

    @Test
    void parseVars_validJson_returnsMap() {
        Map<String, String> m = svc.parseVars("{\"date\":\"2026-09-27\",\"topic\":\"AI\"}");
        assertEquals("2026-09-27", m.get("date"));
        assertEquals("AI", m.get("topic"));
    }

    @Test
    void resolvePrompt_replacesAllKnownVars() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("date", "2026-09-27");
        vars.put("topic", "AI");
        String out = svc.resolvePrompt("report ${date} about ${topic}", vars);
        assertEquals("report 2026-09-27 about AI", out);
    }

    @Test
    void resolvePrompt_keepsUnknownVars() {
        Map<String, String> vars = Map.of("date", "2026-09-27");
        assertEquals("x ${missing} y", svc.resolvePrompt("x ${missing} y", vars));
    }

    @Test
    void resolvePrompt_noVars_unchanged() {
        assertEquals("a ${date} b", svc.resolvePrompt("a ${date} b", Map.of()));
        assertEquals("plain", svc.resolvePrompt("plain", null));
    }

    @Test
    void resolvePrompt_fromJobEntity() {
        JobTask job = new JobTask();
        job.setPrompt("do ${topic}");
        job.setVars("{\"topic\":\"AI\"}");
        assertEquals("do AI", svc.resolvePrompt(job));
    }

    @Test
    void resolvePrompt_valueWithDollarSigns_isNotReinterpreted() {
        Map<String, String> vars = Map.of("v", "$1 and ${x}");
        assertEquals("out: $1 and ${x}", svc.resolvePrompt("out: ${v}", vars));
    }
}
