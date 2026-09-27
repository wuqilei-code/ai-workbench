package com.aiworkbench.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 官方模板播种:从 classpath {@code template-seed/} 读取 HTML 模板 + manifest,
 * 以固定 id(9001+)写入 {@code prompt_template}(内置精选)与 {@code template_marketplace}(官方市场条目)。
 * <p>
 * 使用 {@code INSERT ... ON DUPLICATE KEY UPDATE},每次启动都会用最新文件覆盖旧版本,便于持续升级模板质量。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateSeeder implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** 历史简单版官方模板 id:升级为高质量版后清除,避免新旧并存 */
    private static final long[] LEGACY_IDS = {101L, 102L, 103L};

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (Exception e) {
            log.warn("[template-seed] seed failed: {}", e.toString());
        }
    }

    private void seed() throws Exception {
        // 1) 清理历史官方模板(含市场条目与用户的启用/收藏/安装关系)
        for (long id : LEGACY_IDS) {
            jdbc.update("DELETE FROM prompt_template WHERE id = ?", id);
            jdbc.update("DELETE FROM template_marketplace WHERE id = ?", id);
            jdbc.update("DELETE FROM user_template WHERE template_id = ?", id);
            jdbc.update("DELETE FROM user_template_favorite WHERE template_id = ?", id);
            jdbc.update("DELETE FROM user_template_install WHERE marketplace_id = ?", id);
        }

        // 2) 读取清单
        ClassPathResource manifest = new ClassPathResource("template-seed/manifest.json");
        if (!manifest.exists()) {
            log.info("[template-seed] manifest not found, skip");
            return;
        }
        List<Map<String, Object>> items;
        try (InputStream in = manifest.getInputStream()) {
            items = objectMapper.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
        }

        // 3) 逐条 upsert(内置精选模板 + 官方市场条目)
        int sort = 100;
        int count = 0;
        for (Map<String, Object> it : items) {
            Object idObj = it.get("id");
            if (idObj == null) continue;
            long id = ((Number) idObj).longValue();
            String content = read(new ClassPathResource("template-seed/" + str(it.get("file"))));
            if (content.isBlank()) {
                log.warn("[template-seed] empty content for id={}", id);
                continue;
            }
            upsertTemplate(id, it, content, sort);
            upsertMarket(id, it, content);
            sort += 100;
            count++;
        }
        log.info("[template-seed] upserted {} official templates", count);
    }

    private void upsertTemplate(long id, Map<String, Object> it, String content, int sort) {
        jdbc.update("""
                INSERT INTO prompt_template
                  (id, user_id, source, title, category, description, tags, content, format, skill, featured, icon, sort_order)
                VALUES (?, NULL, 'official', ?, ?, ?, ?, ?, 'html', ?, 1, ?, ?)
                ON DUPLICATE KEY UPDATE
                  title=VALUES(title), category=VALUES(category), description=VALUES(description),
                  tags=VALUES(tags), content=VALUES(content), format=VALUES(format), skill=VALUES(skill),
                  featured=VALUES(featured), icon=VALUES(icon), sort_order=VALUES(sort_order)
                """,
                id, str(it.get("title")), str(it.get("category")), str(it.get("description")),
                str(it.get("tags")), content, str(it.get("skill")), str(it.get("icon")), sort);
    }

    private void upsertMarket(long id, Map<String, Object> it, String content) {
        jdbc.update("""
                INSERT INTO template_marketplace
                  (id, user_id, author_name, title, description, category, tags, content, format, skill, icon, installs)
                VALUES (?, 0, '官方精选', ?, ?, ?, ?, ?, 'html', ?, ?, 0)
                ON DUPLICATE KEY UPDATE
                  author_name=VALUES(author_name), title=VALUES(title), description=VALUES(description),
                  category=VALUES(category), tags=VALUES(tags), content=VALUES(content),
                  format=VALUES(format), skill=VALUES(skill), icon=VALUES(icon)
                """,
                id, str(it.get("title")), str(it.get("description")), str(it.get("category")),
                str(it.get("tags")), content, str(it.get("skill")), str(it.get("icon")));
    }

    private String read(ClassPathResource r) throws Exception {
        if (!r.exists()) return "";
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
