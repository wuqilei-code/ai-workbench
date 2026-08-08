package com.aiworkbench.service;

import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.dto.chat.SkillInfoVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** 内置全局技能只读列表:读取该用户配置目录 ~/.config/opencode/skills/ 下的 SKILL.md(name/description)。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private final OpencodeProperties props;

    /** 内置(全局)技能列表;用户配置未播种时回退到模板目录。 */
    public List<SkillInfoVo> builtinSkills(Long userId) {
        int uid = props.uidOf(userId);
        Path perUser = Paths.get(props.getPerUserDataRoot(), String.valueOf(uid), "home", ".config", "opencode", "skills");
        Path dir = Files.isDirectory(perUser) ? perUser
                : Paths.get(props.getTemplateDir(), "skills");
        return listFrom(dir);
    }

    private List<SkillInfoVo> listFrom(Path skillsDir) {
        List<SkillInfoVo> result = new ArrayList<>();
        if (skillsDir == null || !Files.isDirectory(skillsDir)) {
            return result;
        }
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            List<Path> folders = dirs.filter(Files::isDirectory).sorted().toList();
            for (Path folder : folders) {
                Path sk = folder.resolve("SKILL.md");
                if (!Files.isRegularFile(sk)) {
                    continue;
                }
                String[] fm = parseFrontmatter(sk);
                if (fm[0] == null) {
                    continue; // 无合法 name 的技能,opencode 也不会加载
                }
                result.add(SkillInfoVo.builder()
                        .name(fm[0])
                        .description(fm[1] == null ? "" : fm[1])
                        .build());
            }
        } catch (IOException e) {
            log.warn("list builtin skills failed: {}", e.toString());
        }
        return result;
    }

    /** 解析 SKILL.md 的 YAML frontmatter,返回 {name, description};不合法返回 {null, ...}。 */
    private String[] parseFrontmatter(Path sk) {
        String[] out = { null, null };
        try {
            String content = Files.readString(sk);
            if (!content.startsWith("---")) {
                return out;
            }
            int end = content.indexOf("\n---", 3);
            if (end < 0) {
                return out;
            }
            String fm = content.substring(3, end);
            StringBuilder desc = new StringBuilder();
            boolean inDesc = false;
            for (String line : fm.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) {
                    if (inDesc) desc.append('\n');
                    continue;
                }
                if (t.startsWith("name:")) {
                    out[0] = t.substring(5).trim();
                } else if (t.startsWith("description:")) {
                    inDesc = true;
                    desc.append(t.substring(12).trim());
                } else if (inDesc && (t.startsWith("- ") || t.startsWith("'") || t.startsWith("\"") || t.startsWith("license:")
                        || t.startsWith("compat") || t.startsWith("metadata") || t.startsWith("name:") || t.startsWith("description:"))) {
                    inDesc = false;
                } else if (inDesc) {
                    desc.append('\n').append(t);
                }
            }
            if (desc.length() > 0) {
                out[1] = desc.toString().replaceAll("^['\"]|['\"]$", "").trim();
            }
        } catch (IOException e) {
            log.debug("parse skill frontmatter failed: {} ({})", sk, e.toString());
        }
        return out;
    }
}
