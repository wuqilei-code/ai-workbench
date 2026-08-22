package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.chat.PublishSkillRequest;
import com.aiworkbench.dto.skill.UserSkillRequest;
import com.aiworkbench.dto.skill.UserSkillVo;
import com.aiworkbench.entity.SkillMarketplace;
import com.aiworkbench.entity.UserSkill;
import com.aiworkbench.mapper.SkillMarketplaceMapper;
import com.aiworkbench.mapper.UserSkillMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/** 用户技能(user_skill):自建/安装;私有/公开;安装款只读。文件同步到 deepagents 技能根供 agent 加载。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSkillService {

    private final UserSkillMapper userSkillMapper;
    private final SkillMarketplaceMapper skillMarketplaceMapper;
    private final SkillMarketplaceService skillMarketplaceService;
    private final WorkspaceService workspaceService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    public List<UserSkillVo> list(Long userId) {
        return userSkillMapper.selectList(new LambdaQueryWrapper<UserSkill>()
                .eq(UserSkill::getUserId, userId)
                .orderByDesc(UserSkill::getId)).stream().map(this::toVo).toList();
    }

    public UserSkill getOwned(Long userId, Long id) {
        UserSkill s = userSkillMapper.selectById(id);
        if (s == null || !s.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return s;
    }

    public UserSkillVo create(Long userId, UserSkillRequest req) {
        String name = req.getName() == null ? "" : req.getName().trim();
        if (!SLUG.matcher(name).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "技能名需为小写字母/数字/连字符(如 note-summarizer)");
        }
        Long dup = userSkillMapper.selectCount(new LambdaQueryWrapper<UserSkill>()
                .eq(UserSkill::getUserId, userId).eq(UserSkill::getName, name));
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "技能名已存在");
        }
        String content = ensureFrontmatter(name, req.getDescription(), req.getContent());
        UserSkill s = new UserSkill();
        s.setUserId(userId);
        s.setName(name);
        s.setDescription(req.getDescription() == null ? "" : req.getDescription());
        s.setContent(content);
        s.setFiles(encodeFiles(req.getFiles()));
        s.setVisibility(publicFlag(req.getVisibility()));
        s.setSource("self");
        userSkillMapper.insert(s);
        writeSkillFiles(userId, name, content, req.getFiles());
        syncMarketplace(userId, s, true);
        return toVo(s);
    }

    public UserSkillVo update(Long userId, Long id, UserSkillRequest req) {
        UserSkill s = getOwned(userId, id);
        requireEditable(s);
        if (req.getDescription() != null) s.setDescription(req.getDescription());
        if (req.getContent() != null) s.setContent(req.getContent());
        if (req.getVisibility() != null) s.setVisibility(publicFlag(req.getVisibility()));
        if (req.getFiles() != null) s.setFiles(encodeFiles(req.getFiles()));
        userSkillMapper.updateById(s);
        // 落盘:先清旧附加文件,再写 SKILL.md + 新附加文件
        if (req.getFiles() != null) {
            for (com.aiworkbench.dto.chat.SkillFileVo f : decodeFiles(s.getFiles())) {
                if (f == null || f.getPath() == null || f.getPath().isBlank()) continue;
                workspaceService.deleteFile(userId, "skills/" + s.getName() + "/" + sanitizeRel(f.getPath()));
            }
        }
        writeSkillFiles(userId, s.getName(), ensureFrontmatter(s.getName(), s.getDescription(), s.getContent()),
                decodeFiles(s.getFiles()));
        syncMarketplace(userId, s, s.getVisibility() != null && s.getVisibility() == 1);
        return toVo(s);
    }

    public void delete(Long userId, Long id) {
        UserSkill s = getOwned(userId, id);
        // 安装款不可编辑,但允许删除本地副本(便于重新安装)
        userSkillMapper.deleteById(id);
        try {
            workspaceService.deleteFile(userId, "skills/" + s.getName());
        } catch (Exception e) {
            log.debug("delete skill dir failed: {}", e.toString());
        }
        removeMarketplace(userId, s.getName());
    }

    /**
     * agent create_skill 工具专用:创建或全量更新技能(SKILL.md + 附加文件 + 入库一次完成)。
     * 护栏:内置平台技能拒改;市场安装款只读;agent 新建默认私有。
     * files 为全量语义(null 视为空 = 清空附加文件),先删旧附加文件再写新包,盘/库不漂移。
     */
    @org.springframework.transaction.annotation.Transactional
    public UserSkillVo upsertFromAgent(Long userId, UserSkillRequest req) {
        String name = req.getName() == null ? "" : req.getName().trim();
        if (!SLUG.matcher(name).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "技能名需为小写字母/数字/连字符(如 note-summarizer)");
        }
        if (SkillService.BUILTIN_NAMES.contains(name)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "平台内置技能不可修改");
        }
        String content = ensureFrontmatter(name, req.getDescription(), req.getContent());
        java.util.List<com.aiworkbench.dto.chat.SkillFileVo> files =
                req.getFiles() == null ? List.of() : req.getFiles();

        UserSkill s = userSkillMapper.selectOne(new LambdaQueryWrapper<UserSkill>()
                .eq(UserSkill::getUserId, userId).eq(UserSkill::getName, name));
        boolean created = false;
        if (s == null) {
            s = new UserSkill();
            s.setUserId(userId);
            s.setName(name);
            s.setDescription(req.getDescription() == null ? "" : req.getDescription());
            s.setVisibility(0);
            s.setSource("self");
            created = true;
        } else {
            requireEditable(s);
            if (req.getDescription() != null) s.setDescription(req.getDescription());
        }
        // 先清旧附加文件(files 全量语义),再换内容与文件清单
        for (com.aiworkbench.dto.chat.SkillFileVo f : decodeFiles(s.getFiles())) {
            if (f == null || f.getPath() == null || f.getPath().isBlank()) continue;
            try {
                workspaceService.deleteFile(userId, "skills/" + name + "/" + sanitizeRel(f.getPath()));
            } catch (Exception e) {
                log.debug("clean old skill file failed: {}", e.toString());
            }
        }
        s.setContent(content);
        s.setFiles(encodeFiles(files.isEmpty() ? null : files));
        if (created) {
            userSkillMapper.insert(s);
        } else {
            userSkillMapper.updateById(s);
        }
        writeSkillFiles(userId, name, content, files);
        syncMarketplace(userId, s, s.getVisibility() != null && s.getVisibility() == 1);
        return toVo(s);
    }

    private void requireEditable(UserSkill s) {
        if ("installed".equals(s.getSource())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "从市场安装的技能只读,不可编辑/删除");
        }
    }

    private void writeFile(Long userId, String name, String content) {
        workspaceService.writeFile(userId, "skills/" + name + "/SKILL.md", content);
    }

    /** 写技能包:SKILL.md + 附加文件(路径安全校验:拒绝 .. / 绝对路径)。 */
    private void writeSkillFiles(Long userId, String name, String sklmd,
                                 List<com.aiworkbench.dto.chat.SkillFileVo> files) {
        workspaceService.writeFile(userId, "skills/" + name + "/SKILL.md", sklmd);
        if (files == null) return;
        for (com.aiworkbench.dto.chat.SkillFileVo f : files) {
            if (f == null) continue;
            String rel = sanitizeRel(f.getPath());
            if (rel == null || rel.isBlank() || "SKILL.md".equals(rel) || rel.startsWith("SKILL.md/")) continue;
            workspaceService.writeFile(userId, "skills/" + name + "/" + rel,
                    f.getContent() == null ? "" : f.getContent());
        }
    }

    private String sanitizeRel(String path) {
        String rel = (path == null ? "" : path).replace('\\', '/').replaceAll("^/+", "");
        if (rel.contains("..") || rel.isEmpty()) return null;
        return rel;
    }

    private String encodeFiles(List<com.aiworkbench.dto.chat.SkillFileVo> files) {
        if (files == null || files.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(files);
        } catch (Exception e) {
            return null;
        }
    }

    private List<com.aiworkbench.dto.chat.SkillFileVo> decodeFiles(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<com.aiworkbench.dto.chat.SkillFileVo>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private void syncMarketplace(Long userId, UserSkill s, boolean publish) {
        try {
            if (publish) {
                PublishSkillRequest req = new PublishSkillRequest();
                req.setName(s.getName());
                req.setDescription(s.getDescription() == null ? "" : s.getDescription());
                req.setContent(s.getContent());
                req.setCategory("other");
                req.setIcon("🛠️");
                req.setFiles(decodeFiles(s.getFiles()));
                skillMarketplaceService.publish(userId, req);
            } else {
                removeMarketplace(userId, s.getName());
            }
        } catch (Exception e) {
            log.warn("sync marketplace failed for skill {}: {}", s.getName(), e.toString());
        }
    }

    private void removeMarketplace(Long userId, String name) {
        SkillMarketplace m = skillMarketplaceMapper.selectOne(new LambdaQueryWrapper<SkillMarketplace>()
                .eq(SkillMarketplace::getUserId, userId).eq(SkillMarketplace::getName, name));
        if (m != null) {
            skillMarketplaceService.deleteOwn(userId, m.getId());
        }
    }

    private String ensureFrontmatter(String name, String desc, String content) {
        String c = content == null ? "" : content.trim();
        if (c.startsWith("---")) {
            return c;
        }
        String d = (desc == null || desc.isBlank()) ? "用户自定义技能。" : desc.replace("\n", " ").trim();
        return "---\nname: " + name + "\ndescription: " + d + "\n---\n\n" + c;
    }

    private int publicFlag(Integer v) {
        return (v != null && v == 1) ? 1 : 0;
    }

    private UserSkillVo toVo(UserSkill s) {
        return UserSkillVo.builder()
                .id(s.getId()).name(s.getName()).description(s.getDescription())
                .content(s.getContent()).files(decodeFiles(s.getFiles()))
                .visibility(s.getVisibility()).source(s.getSource())
                .createdAt(s.getCreatedAt()).updatedAt(s.getUpdatedAt())
                .build();
    }
}