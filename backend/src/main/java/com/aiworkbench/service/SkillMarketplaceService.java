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
import java.util.Set;

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

    /** deepagents 技能根虚拟前缀:install 写入 用户技能根 users/<id>/skills/<name>/ */
    private static final String SKILLS_ROOT = "skills";

    private final SkillMarketplaceMapper marketplaceMapper;
    private final SkillInstallMapper installMapper;
    private final com.aiworkbench.mapper.UserSkillMapper userSkillMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;

    /**
     * 广场列表:按安装数倒序;附当前用户的 installed / mine 标记。
     * <p>
     * <b>installed 以 user_skill(本地技能库)为准</b>,而不是 skill_install:发布者自己的技能
     * 也会在本地库里(见 publish),若只看 skill_install 会把"已拥有"错标成"未安装"。
     * 顺带在读取时做一次<b>存量自愈</b>(见 repairOwnSkills),修掉历史遗留的不一致。
     */
    public List<MarketplaceSkillVo> list(Long userId) {
        List<SkillMarketplace> rows = marketplaceMapper.selectList(
                new LambdaQueryWrapper<SkillMarketplace>().orderByDesc(SkillMarketplace::getInstalls));
        if (rows.isEmpty()) return List.of();

        repairOwnSkills(userId, rows);

        Set<String> localNames = localNames(userId);

        List<MarketplaceSkillVo> out = new ArrayList<>();
        for (SkillMarketplace s : rows) {
            out.add(toVo(s, localNames.contains(s.getName()), s.getUserId().equals(userId), false));
        }
        return out;
    }

    /** 本地技能库里的技能名集合(user_skill 是"我拥有哪些技能"的事实源)。 */
    private Set<String> localNames(Long userId) {
        return userSkillMapper.selectList(
                        new LambdaQueryWrapper<com.aiworkbench.entity.UserSkill>()
                                .eq(com.aiworkbench.entity.UserSkill::getUserId, userId))
                .stream().map(com.aiworkbench.entity.UserSkill::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 存量自愈(幂等,失败仅记日志,不阻断列表):
     * <ol>
     *   <li><b>幽灵安装</b>:skill_install 有记录、但 user_skill 已被删(库和盘都没了)。
     *       旧逻辑据此把 installed 标成 true,导致"市场里显示已安装、工作区里却没有"。
     *       这里删掉这类残留安装记录,技能即可重新安装。</li>
     *   <li><b>只发布未入库</b>:市场里有我发布的技能、但 user_skill 没有(旧 publish 只写市场表)。
     *       这里补登记进本地库,让"市场-我的"与"工作区-我的"计数一致。</li>
     * </ol>
     */
    private void repairOwnSkills(Long userId, List<SkillMarketplace> rows) {
        Set<String> local;
        try {
            local = localNames(userId);
        } catch (Exception e) {
            return;
        }
        // 1) 幽灵安装:skill_install 有、user_skill 无
        try {
            List<SkillInstall> installs = installMapper.selectList(
                    new LambdaQueryWrapper<SkillInstall>().eq(SkillInstall::getUserId, userId));
            for (SkillInstall it : installs) {
                if (local.contains(it.getSkillName())) continue;
                installMapper.deleteById(it.getId());
                log.info("[skill] 清理幽灵安装记录: user={} name={}", userId, it.getSkillName());
            }
        } catch (Exception e) {
            log.warn("[skill] 幽灵安装清理失败: user={} err={}", userId, e.toString());
        }
        // 2) 只发布未入库:补写本地库 + 落盘
        for (SkillMarketplace s : rows) {
            if (!s.getUserId().equals(userId) || local.contains(s.getName())) continue;
            try {
                registerLocal(userId, s, deserializeFiles(s.getFiles()));
                log.info("[skill] 发布未入库已修复: user={} name={}", userId, s.getName());
            } catch (Exception e) {
                log.warn("[skill] 发布未入库修复失败: user={} name={} err={}",
                        userId, s.getName(), e.toString());
            }
        }
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
    /**
     * 发布到技能市场。
     * <p>
     * <b>同时登记进发布者自己的技能库</b>({@code user_skill} + 落盘):发布是"入库"行为,
     * 若只写市场表,发布者会在「市场-我的」看到它,却在「工作区技能」和「选择技能」里看不到
     * (后者走 磁盘 ∩ user_skill),两处计数长期不一致。
     */
    @Transactional
    public MarketplaceSkillVo publish(Long userId, PublishSkillRequest req) {
        Long dup = marketplaceMapper.selectCount(
                new LambdaQueryWrapper<SkillMarketplace>().eq(SkillMarketplace::getName, req.getName()));
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "市场已存在同名技能「" + req.getName() + "」");
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
        // 发布即入库:写 user_skill + 落盘(source=self,公开)。
        // 不复用 UserSkillService(它反向依赖本类,构造器注入会成环),沿用 install() 的直写模式。
        registerLocal(userId, s, req.getFiles() != null ? req.getFiles() : deserializeFiles(s.getFiles()));
        return toVo(s, true, true, true);
    }

    /** 把我自己的市场技能登记进本地库(user_skill)+ 落盘;已存在则覆盖更新。 */
    private void registerLocal(Long userId, SkillMarketplace s,
                               List<SkillFileVo> files) {
        String skMd = withIconFrontmatter(s.getContent(), s.getIcon());
        String filesJson;
        try {
            filesJson = objectMapper.writeValueAsString(files == null ? List.of() : files);
        } catch (Exception e) {
            filesJson = "[]";
        }
        com.aiworkbench.entity.UserSkill local = userSkillMapper.selectOne(
                new LambdaQueryWrapper<com.aiworkbench.entity.UserSkill>()
                        .eq(com.aiworkbench.entity.UserSkill::getUserId, userId)
                        .eq(com.aiworkbench.entity.UserSkill::getName, s.getName()));
        if (local == null) {
            local = new com.aiworkbench.entity.UserSkill();
            local.setUserId(userId);
            local.setName(s.getName());
            local.setDescription(s.getDescription() == null ? "" : s.getDescription());
            local.setContent(skMd);
            local.setFiles(filesJson);
            local.setVisibility(1);
            local.setSource("self");
            local.setSourceSkillId(s.getId());
            userSkillMapper.insert(local);
        } else {
            local.setDescription(s.getDescription() == null ? "" : s.getDescription());
            local.setContent(skMd);
            local.setFiles(filesJson);
            local.setVisibility(1);
            userSkillMapper.updateById(local);
        }
        writeLocalFiles(userId, s.getName(), skMd, files);
    }

    /** 落盘技能包(须持技能同步令牌);失败则删掉半成品目录,保持"盘上 ⇔ 库里"一致。 */
    private void writeLocalFiles(Long userId, String name, String skMd, List<SkillFileVo> files) {
        WorkspaceService.SkillSyncScope sync = workspaceService.openSkillSync();
        try {
            String dir = SKILLS_ROOT + "/" + name;
            workspaceService.writeFile(userId, dir + "/SKILL.md", skMd);
            if (files == null) return;
            for (SkillFileVo f : files) {
                if (f == null || f.getPath() == null || f.getPath().isBlank()) continue;
                String rel = f.getPath().replace('\\', '/');
                if (rel.startsWith("/") || rel.contains("..") || "SKILL.md".equals(rel)) continue;
                if ("base64".equals(f.getEncoding()) && f.getContent() != null) {
                    workspaceService.writeBinaryFile(userId, dir + "/" + rel,
                            java.util.Base64.getDecoder().decode(f.getContent()));
                } else {
                    workspaceService.writeFile(userId, dir + "/" + rel, f.getContent() == null ? "" : f.getContent());
                }
            }
        } catch (RuntimeException e) {
            try {
                workspaceService.deleteFile(userId, SKILLS_ROOT + "/" + name);
            } catch (Exception ignored) {
                // 补偿失败仅记录,不掩盖原始异常
            }
            throw e;
        } finally {
            sync.close();
        }
    }

    /** 上传技能包(.zip / .skill,内含根级 SKILL.md):解析后发布到广场。 */
    @Transactional
    public MarketplaceSkillVo uploadPackage(Long userId, byte[] data) {
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName().replace('\\', '/');
                if (n.contains("..") || n.startsWith("/") || n.contains("__MACOSX/") || n.endsWith(".DS_Store")) continue;
                entries.put(n, zin.readAllBytes());
            }
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "无法解析技能包(需为 zip / .skill 格式)");
        }
        if (entries.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "技能包为空");
        }
        // 去掉所有条目共同的顶层目录(常见于 zip 打包)
        String prefix = commonTopDir(entries.keySet());
        java.util.Map<String, byte[]> norm = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, byte[]> en : entries.entrySet()) {
            String rel = prefix.isEmpty() ? en.getKey() : en.getKey().substring(prefix.length());
            if (!rel.isBlank()) norm.put(rel, en.getValue());
        }
        // 定位 SKILL.md(优先根级)
        String skillPath = null;
        for (String p : norm.keySet()) {
            if ("SKILL.md".equalsIgnoreCase(p)) { skillPath = p; break; }
        }
        if (skillPath == null) {
            for (String p : norm.keySet()) {
                if (p.toLowerCase().endsWith("/skill.md") && (skillPath == null || p.length() < skillPath.length())) {
                    skillPath = p;
                }
            }
        }
        if (skillPath == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "技能包需包含根级 SKILL.md 文件");
        }
        String content = new String(norm.get(skillPath), java.nio.charset.StandardCharsets.UTF_8);
        String[] meta = parseFrontmatter(content);
        String name = meta[0];
        String desc = meta[1];
        if (name == null || name.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "SKILL.md 缺少 name(frontmatter)");
        }
        if (!name.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "技能名需为小写字母/数字,单词用连字符分隔:" + name);
        }
        if (desc == null || desc.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "SKILL.md 缺少 description(frontmatter)");
        }

        String skillDir = skillPath.contains("/") ? skillPath.substring(0, skillPath.lastIndexOf('/') + 1) : "";
        List<SkillFileVo> files = new ArrayList<>();
        for (java.util.Map.Entry<String, byte[]> en : norm.entrySet()) {
            String p = en.getKey();
            if (p.equals(skillPath)) continue;
            if (!skillDir.isEmpty() && !p.startsWith(skillDir)) continue; // 只保留 SKILL.md 同目录下的文件
            String rel = skillDir.isEmpty() ? p : p.substring(skillDir.length());
            if (rel.isBlank() || rel.toLowerCase().endsWith("skill.md")) continue;
            SkillFileVo f = new SkillFileVo();
            f.setPath(rel);
            byte[] bytes = en.getValue();
            if (isProbablyText(bytes)) {
                f.setContent(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            } else {
                f.setContent(java.util.Base64.getEncoder().encodeToString(bytes));
                f.setEncoding("base64");
            }
            files.add(f);
        }

        PublishSkillRequest req = new PublishSkillRequest();
        req.setName(name);
        req.setDescription(desc.length() > 1024 ? desc.substring(0, 1024) : desc);
        req.setCategory("other");
        req.setIcon("");
        req.setContent(content);
        req.setFiles(files);
        return publish(userId, req);
    }

    /** 所有路径共同的顶层目录(用于剥离 zip 打包目录);无则返回空串。 */
    private static String commonTopDir(java.util.Collection<String> paths) {
        String prefix = null;
        for (String p : paths) {
            int i = p.indexOf('/');
            if (i <= 0) return "";
            String top = p.substring(0, i + 1);
            if (prefix == null) prefix = top;
            else if (!prefix.equals(top)) return "";
        }
        return prefix == null ? "" : prefix;
    }

    private static boolean isProbablyText(byte[] b) {
        int n = Math.min(b.length, 4096);
        for (int i = 0; i < n; i++) {
            if (b[i] == 0) return false;
        }
        return true;
    }

    /** 从 SKILL.md frontmatter 提取 name/description(逐行简单解析,避免引入 YAML 依赖)。 */
    private static String[] parseFrontmatter(String content) {
        String name = null;
        String desc = null;
        String[] lines = content.split("\r?\n");
        boolean inFm = lines.length > 0 && lines[0].trim().equals("---");
        if (inFm) {
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.trim().equals("---")) break;
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String k = line.substring(0, c).trim().toLowerCase();
                String v = line.substring(c + 1).trim();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1);
                }
                if (k.equals("name") && name == null) name = v;
                else if (k.equals("description") && desc == null) desc = v;
            }
        }
        return new String[]{name, desc};
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

        // 先规整附加文件清单(过滤非法路径/重复 SKILL.md),作为 user_skill.files 一并落库,
        // 否则「查看已安装技能」时只读编辑器拿不到附加文件,只剩 SKILL.md
        List<SkillFileVo> toSave = new ArrayList<>();
        for (SkillFileVo f : deserializeFiles(s.getFiles())) {
            if (f == null || f.getPath() == null || f.getPath().isBlank()) continue;
            String rel = f.getPath().replace('\\', '/').replaceAll("^/+", "");
            if (rel.contains("..") || rel.startsWith("/") || rel.isEmpty()) continue;
            // SKILL.md 已由 content 写入,附加文件里再带一个会重复覆盖,跳过
            if ("SKILL.md".equals(rel)) continue;
            toSave.add(f);
        }

        // 硬性约束:user_skill 记录是事实源,必须先落库成功才允许写文件;失败则整个安装回滚
        com.aiworkbench.entity.UserSkill us = new com.aiworkbench.entity.UserSkill();
        us.setUserId(userId);
        us.setName(name);
        us.setDescription(s.getDescription() == null ? "" : s.getDescription());
        us.setContent(withIconFrontmatter(s.getContent(), s.getIcon()));
        us.setFiles(serializeFiles(toSave));
        us.setVisibility(0);
        us.setSource("installed");
        us.setSourceSkillId(id);
        userSkillMapper.insert(us);

        SkillInstall it = new SkillInstall();
        it.setUserId(userId);
        it.setSkillId(id);
        it.setSkillName(name);
        installMapper.insert(it);

        // 持同步令牌写盘(skills/ 写闸只放行已入库的变更)
        int written = 0;
        WorkspaceService.SkillSyncScope sync = workspaceService.openSkillSync();
        try {
            workspaceService.writeFile(userId, dir + "/SKILL.md", withIconFrontmatter(s.getContent(), s.getIcon()));
            written++;
            for (SkillFileVo f : toSave) {
                String rel = f.getPath().replace('\\', '/').replaceAll("^/+", "");
                if ("base64".equals(f.getEncoding()) && f.getContent() != null) {
                    workspaceService.writeBinaryFile(userId, dir + "/" + rel,
                            java.util.Base64.getDecoder().decode(f.getContent()));
                } else {
                    workspaceService.writeFile(userId, dir + "/" + rel,
                            f.getContent() == null ? "" : f.getContent());
                }
                written++;
            }
        } catch (RuntimeException e) {
            // 补偿:清掉半成品目录,保证"盘上有技能文件 ⇔ 库里有记录"不因失败被破坏
            try { workspaceService.deleteFile(userId, dir); } catch (Exception ignored) {}
            throw e;
        } finally {
            sync.close();
        }

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
