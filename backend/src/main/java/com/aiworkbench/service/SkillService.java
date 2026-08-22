package com.aiworkbench.service;

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

/**
 * 内置技能只读列表:读取该用户的 deepagents 技能根
 * {@code workspace/users/<userId>/skills/} 下的 SKILL.md(name/description)。
 * 与 agent-service 的 /skills 虚拟根同址(agent 实际加载的就是这份)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private final WorkspaceService workspaceService;
    private final AgentSkillSeeder agentSkillSeeder;

    /** 顶部"内置技能"列表:仅平台内置(默认加载);用户自定义技能不在此列,出现在用户技能区/会话配置。 */
    public List<SkillInfoVo> builtinSkills(Long userId) {
        agentSkillSeeder.seed(userId);
        Path project = Paths.get(workspaceService.userDir(userId));
        Path skillsDir = project.getParent().resolve("skills");
        return listFrom(skillsDir).stream()
                .filter(s -> BUILTIN_NAMES.contains(s.getName()))
                .toList();
    }

    /** 平台内置技能名(与 agent-service 镜像 skills/ 目录一致;默认每会话加载,不出现在自定义勾选) */
    public static final java.util.Set<String> BUILTIN_NAMES = java.util.Set.of(
            "browser-operate", "create-skill", "docx", "html-report", "pdf", "pptx", "xlsx");

    /** 用户自定义技能(可勾选按会话加载):skills 根下除内置外的技能名。 */
    public java.util.List<String> customSkillNames(Long userId) {
        agentSkillSeeder.seed(userId);
        Path project = Paths.get(workspaceService.userDir(userId));
        Path skillsDir = project.getParent().resolve("skills");
        if (!Files.isDirectory(skillsDir)) return List.of();
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            return dirs.filter(Files::isDirectory)
                    .map(d -> d.getFileName().toString())
                    .filter(n -> !BUILTIN_NAMES.contains(n))
                    .filter(n -> Files.isRegularFile(skillsDir.resolve(n).resolve("SKILL.md")))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("list custom skills failed: {}", e.toString());
            return List.of();
        }
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
                    continue; // 无合法 name 的技能,不会加载
                }
                result.add(SkillInfoVo.builder()
                        .name(fm[0])
                        .description(fm[1] == null ? "" : fm[1])
                        .build());
            }
        } catch (IOException e) {
            log.warn("list skills failed: {}", e.toString());
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