package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.chat.MarketplaceSkillVo;
import com.aiworkbench.dto.chat.PublishSkillRequest;
import com.aiworkbench.dto.chat.SkillFileVo;
import com.aiworkbench.entity.SkillInstall;
import com.aiworkbench.entity.SkillMarketplace;
import com.aiworkbench.entity.User;
import com.aiworkbench.mapper.SkillInstallMapper;
import com.aiworkbench.mapper.SkillMarketplaceMapper;
import com.aiworkbench.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能广场:用户发布自建技能 → 广场,其他用户浏览/安装到自己的工作区。
 * <p>
 * 安装 = 把 SKILL.md(及其附加文件)写入目标用户工作区 <code>.opencode/skills/&lt;name&gt;/</code>,
 * 记录 skill_install 并递增 installs。同一用户对同一技能名不可重复安装(安装后可删除本地再装)。
 * <p>
 * 技能不止 SKILL.md:还可以携带附加文件(files,JSON 字符串列),如
 * scripts/*.py、data/*.csv|json、references/*.md 等,安装时一并写入,让技能成为真正可运行的包。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillMarketplaceService {

    private static final String SKILLS_ROOT = ".opencode/skills";

    private final SkillMarketplaceMapper marketplaceMapper;
    private final SkillInstallMapper installMapper;
    private final WorkspaceService workspaceService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    /** 广场列表:按安装数倒序;附当前用户的 installed / mine 标记。 */
    public List<MarketplaceSkillVo> list(Long userId) {
        List<SkillMarketplace> rows = marketplaceMapper.selectList(
                new LambdaQueryWrapper<SkillMarketplace>().orderByDesc(SkillMarketplace::getInstalls));
        if (rows.isEmpty()) return List.of();

        List<SkillInstall> mine = installMapper.selectList(
                new LambdaQueryWrapper<SkillInstall>().eq(SkillInstall::getUserId, userId));
        Map<String, Boolean> installedNames = new HashMap<>();
        for (SkillInstall it : mine) installedNames.put(it.getSkillName(), true);

        List<MarketplaceSkillVo> out = new ArrayList<>();
        for (SkillMarketplace s : rows) {
            out.add(toVo(s, installedNames.containsKey(s.getName()), s.getUserId().equals(userId), false));
        }
        return out;
    }

    public MarketplaceSkillVo detail(Long userId, Long id) {
        SkillMarketplace s = marketplaceMapper.selectById(id);
        if (s == null) throw new BusinessException(ResultCode.NOT_FOUND);
        boolean installed = installMapper.selectCount(
                new LambdaQueryWrapper<SkillInstall>()
                        .eq(SkillInstall::getUserId, userId)
                        .eq(SkillInstall::getSkillName, s.getName())) > 0;
        return toVo(s, installed, s.getUserId().equals(userId), true);
    }

    /** 发布:在线填写 SKILL.md + 可选附加文件。同名技能已存在则拒绝。 */
    @Transactional
    public MarketplaceSkillVo publish(Long userId, PublishSkillRequest req) {
        Long dup = marketplaceMapper.selectCount(
                new LambdaQueryWrapper<SkillMarketplace>().eq(SkillMarketplace::getName, req.getName()));
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "广场已存在同名技能「" + req.getName() + "」");
        }
        User author = userMapper.selectById(userId);
        SkillMarketplace s = new SkillMarketplace();
        s.setUserId(userId);
        s.setAuthorName(author != null && author.getUsername() != null ? author.getUsername() : "");
        s.setName(req.getName());
        s.setDescription(req.getDescription());
        s.setCategory(req.getCategory() == null || req.getCategory().isBlank() ? "other" : req.getCategory());
        s.setIcon(req.getIcon() == null ? "" : req.getIcon());
        s.setContent(req.getContent());
        s.setFiles(serializeFiles(req.getFiles()));
        s.setInstalls(0);
        marketplaceMapper.insert(s);
        return toVo(s, false, true, true);
    }

    /** 安装:把 SKILL.md + 附加文件写入当前用户工作区 .opencode/skills/<name>/。 */
    @Transactional
    public void install(Long userId, Long id) {
        SkillMarketplace s = marketplaceMapper.selectById(id);
        if (s == null) throw new BusinessException(ResultCode.NOT_FOUND);
        String name = s.getName();
        Long dup = installMapper.selectCount(
                new LambdaQueryWrapper<SkillInstall>()
                        .eq(SkillInstall::getUserId, userId)
                        .eq(SkillInstall::getSkillName, name));
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(),
                    "已安装过技能「" + name + "」(如需重装,请先在技能页删除本地副本)");
        }
        String dir = SKILLS_ROOT + "/" + name;
        int written = 0;
        workspaceService.writeFile(userId, dir + "/SKILL.md", withIconFrontmatter(s.getContent(), s.getIcon()));
        written++;
        List<SkillFileVo> files = deserializeFiles(s.getFiles());
        for (SkillFileVo f : files) {
            if (f == null || f.getPath() == null || f.getPath().isBlank()) continue;
            String rel = f.getPath().replace('\\', '/').replaceAll("^/+", "");
            if (rel.contains("..") || rel.startsWith("/") || rel.isEmpty()) continue;
            workspaceService.writeFile(userId, dir + "/" + rel,
                    f.getContent() == null ? "" : f.getContent());
            written++;
        }

        SkillInstall it = new SkillInstall();
        it.setUserId(userId);
        it.setSkillId(id);
        it.setSkillName(name);
        installMapper.insert(it);

        SkillMarketplace upd = new SkillMarketplace();
        upd.setId(id);
        upd.setInstalls((s.getInstalls() == null ? 0 : s.getInstalls()) + 1);
        marketplaceMapper.updateById(upd);
        log.info("[skill-marketplace] user {} installed skill {} (id={}, files={})", userId, name, id, written);
    }

    /** 删除自己发布的技能(他人安装的记录保留,但不再可安装)。 */
    @Transactional
    public void deleteOwn(Long userId, Long id) {
        SkillMarketplace s = marketplaceMapper.selectById(id);
        if (s == null) throw new BusinessException(ResultCode.NOT_FOUND);
        if (!s.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只能删除自己发布的技能");
        }
        marketplaceMapper.deleteById(id);
    }

    private MarketplaceSkillVo toVo(SkillMarketplace s, boolean installed, boolean mine, boolean withContent) {
        return MarketplaceSkillVo.builder()
                .id(s.getId())
                .authorName(s.getAuthorName())
                .name(s.getName())
                .description(s.getDescription())
                .category(s.getCategory() == null ? "other" : s.getCategory())
                .icon(s.getIcon() == null ? "" : s.getIcon())
                .content(withContent ? s.getContent() : null)
                .files(withContent ? deserializeFiles(s.getFiles()) : null)
                .installs(s.getInstalls() == null ? 0 : s.getInstalls())
                .installed(installed)
                .mine(mine)
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    private String serializeFiles(List<SkillFileVo> files) {
        if (files == null || files.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(files);
        } catch (Exception e) {
            log.warn("serialize skill files failed: {}", e.toString());
            return "[]";
        }
    }

    private List<SkillFileVo> deserializeFiles(String filesJson) {
        if (filesJson == null || filesJson.isBlank()) return new ArrayList<>();
        try {
            List<SkillFileVo> list = objectMapper.readValue(filesJson, new TypeReference<List<SkillFileVo>>() {});
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("deserialize skill files failed: {}", e.toString());
            return new ArrayList<>();
        }
    }

    /**
     * 把广场技能的 icon 注入 SKILL.md 的 frontmatter,让工作区技能列表能直接读取到图标。
     * 已存在 icon 字段或没有 frontmatter 时原样返回。
     */
    private String withIconFrontmatter(String content, String icon) {
        if (content == null || icon == null || icon.isBlank()) return content;
        if (!content.startsWith("---")) return content;
        int end = content.indexOf("\n---", 4);
        if (end < 0) return content;
        String front = content.substring(4, end);
        if (front.contains("icon:")) return content;
        String updated = content.substring(0, 4) + front + "\nicon: " + icon + content.substring(end);
        return updated;
    }
}
