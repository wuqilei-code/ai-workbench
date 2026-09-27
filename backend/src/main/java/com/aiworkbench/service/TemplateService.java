package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.chat.TemplateRequest;
import com.aiworkbench.dto.chat.TemplateVo;
import com.aiworkbench.entity.PromptTemplate;
import com.aiworkbench.entity.UserTemplate;
import com.aiworkbench.entity.UserTemplateFavorite;
import com.aiworkbench.entity.UserTemplateInstall;
import com.aiworkbench.mapper.PromptTemplateMapper;
import com.aiworkbench.mapper.UserTemplateFavoriteMapper;
import com.aiworkbench.mapper.UserTemplateInstallMapper;
import com.aiworkbench.mapper.UserTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提示词模板库:系统内置(全员只读,可启停)+ 个人自建。
 * 用户通过 user_template 关系表决定哪些模板在首页展示;无任何启用时兜底显示内置前几个。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final PromptTemplateMapper templateMapper;
    private final UserTemplateMapper userTemplateMapper;
    private final UserTemplateFavoriteMapper favoriteMapper;
    private final UserTemplateInstallMapper installMapper;

    /** 首页最大展示/启用数 */
    private static final int HOME_MAX = 12;

    /** 当前用户的模板列表(内置 + 自建),各带 enabled/pinned 状态。 */
    public List<TemplateVo> listMine(Long userId) {
        List<PromptTemplate> builtin = templateMapper.selectList(
                new LambdaQueryWrapper<PromptTemplate>()
                        .isNull(PromptTemplate::getUserId)
                        .orderByAsc(PromptTemplate::getSortOrder));
        List<PromptTemplate> mine = templateMapper.selectList(
                new LambdaQueryWrapper<PromptTemplate>()
                        .eq(PromptTemplate::getUserId, userId)
                        .orderByAsc(PromptTemplate::getSortOrder));
        Map<Long, UserTemplate> utMap = userTemplateMap(userId);
        Set<Long> favIds = favoriteIds(userId);

        List<TemplateVo> out = new ArrayList<>();
        for (PromptTemplate t : builtin) out.add(toVo(t, true, utMap.get(t.getId()), favIds.contains(t.getId())));
        for (PromptTemplate t : mine) out.add(toVo(t, false, utMap.get(t.getId()), favIds.contains(t.getId())));
        return out;
    }

    /** 启用/停用一个模板(内置或自建)。自建被停用仍保留在列表,只是不在首页。 */
    public void setEnabled(Long userId, Long templateId, boolean enabled) {
        PromptTemplate t = templateMapper.selectById(templateId);
        if (t == null) throw new BusinessException(ResultCode.NOT_FOUND);
        UserTemplate ut = userTemplateMapper.selectOne(new LambdaQueryWrapper<UserTemplate>()
                .eq(UserTemplate::getUserId, userId)
                .eq(UserTemplate::getTemplateId, templateId));
        if (enabled) {
            // 限制首页最多启用 HOME_MAX 个:已启用达到上限且该模板未启用时拒绝
            Long currentEnabled = userTemplateMapper.selectCount(new LambdaQueryWrapper<UserTemplate>()
                    .eq(UserTemplate::getUserId, userId)
                    .eq(UserTemplate::getEnabled, 1));
            boolean alreadyEnabled = ut != null && ut.getEnabled() != null && ut.getEnabled() == 1;
            if (!alreadyEnabled && currentEnabled != null && currentEnabled >= HOME_MAX) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                        "首页最多启用 " + HOME_MAX + " 个模板,请先停用其他模板");
            }
            if (ut == null) {
                ut = new UserTemplate();
                ut.setUserId(userId);
                ut.setTemplateId(templateId);
                ut.setEnabled(1);
                ut.setSortOrder(nextSortOrder(userId));
                userTemplateMapper.insert(ut);
            } else if (ut.getEnabled() == null || ut.getEnabled() != 1) {
                ut.setEnabled(1);
                userTemplateMapper.update(null, new LambdaUpdateWrapper<UserTemplate>()
                        .eq(UserTemplate::getUserId, userId)
                        .eq(UserTemplate::getTemplateId, templateId)
                        .set(UserTemplate::getEnabled, 1));
            }
        } else {
            if (ut != null) {
                userTemplateMapper.update(null, new LambdaUpdateWrapper<UserTemplate>()
                        .eq(UserTemplate::getUserId, userId)
                        .eq(UserTemplate::getTemplateId, templateId)
                        .set(UserTemplate::getEnabled, 0));
            }
        }
    }

    /** 新建个人模板。 */
    public TemplateVo create(Long userId, TemplateRequest req) {
        PromptTemplate t = new PromptTemplate();
        t.setUserId(userId);
        t.setTitle(req.getTitle());
        t.setCategory(req.getCategory());
        t.setDescription(req.getDescription());
        t.setTags(req.getTags());
        t.setContent(req.getContent());
        t.setFormat(req.getFormat() != null && !req.getFormat().isBlank() ? req.getFormat() : "prompt");
        t.setSkill(req.getSkill());
        t.setFeatured(0);
        t.setSource("self");
        t.setThumb(req.getThumb());
        t.setIcon(req.getIcon() != null && !req.getIcon().isBlank() ? req.getIcon() : "📋");
        t.setSortOrder(1000);
        templateMapper.insert(t);
        // 自建模板默认**不自动启用**(首页启用名额有限,HOME_MAX);用户可在列表里手动启用。
        // 已启用名额未满时可自动启用,满了也不报错。
        try {
            Long cnt = userTemplateMapper.selectCount(new LambdaQueryWrapper<UserTemplate>()
                    .eq(UserTemplate::getUserId, userId)
                    .eq(UserTemplate::getEnabled, 1));
            if (cnt == null || cnt < HOME_MAX) {
                setEnabled(userId, t.getId(), true);
                return toVo(t, false, userTemplateRow(userId, t.getId()), false);
            }
        } catch (Exception ignored) {
            // 自动启用失败不影响创建
        }
        return toVo(t, false, null, false);
    }

    public TemplateVo update(Long userId, Long id, TemplateRequest req) {
        PromptTemplate t = getOwned(userId, id);
        if ("installed".equals(t.getSource())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "市场安装的模板只读,仅可查看/使用");
        }
        t.setTitle(req.getTitle());
        t.setCategory(req.getCategory());
        t.setDescription(req.getDescription());
        t.setTags(req.getTags());
        t.setContent(req.getContent());
        if (req.getFormat() != null && !req.getFormat().isBlank()) t.setFormat(req.getFormat());
        if (req.getSkill() != null) t.setSkill(req.getSkill());
        if (req.getThumb() != null) t.setThumb(req.getThumb());
        if (req.getIcon() != null && !req.getIcon().isBlank()) {
            t.setIcon(req.getIcon());
        }
        templateMapper.updateById(t);
        return toVo(t, false, userTemplateRow(userId, id), isFavorited(userId, id));
    }

    public void delete(Long userId, Long id) {
        PromptTemplate t = getOwned(userId, id);
        templateMapper.deleteById(t.getId());
        userTemplateMapper.delete(new LambdaQueryWrapper<UserTemplate>()
                .eq(UserTemplate::getUserId, userId)
                .eq(UserTemplate::getTemplateId, id));
        // 若为市场安装款,同步清除安装记录,允许再次从市场安装
        installMapper.delete(new LambdaQueryWrapper<UserTemplateInstall>()
                .eq(UserTemplateInstall::getTemplateId, id));
    }

    /**
     * 保存当前用户模板的自定义顺序:按传入 id 顺序重写 sort_order(每次 +10),
     * 仅作用于归属该用户的模板(内置/他人模板忽略,防越权)。
     */
    public void reorder(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        int sort = 10;
        for (Long id : ids) {
            if (id == null) continue;
            templateMapper.update(null, new LambdaUpdateWrapper<PromptTemplate>()
                    .eq(PromptTemplate::getId, id)
                    .eq(PromptTemplate::getUserId, userId)
                    .set(PromptTemplate::getSortOrder, sort));
            sort += 10;
        }
    }

    /**
     * 置顶/取消置顶自己的模板(自建或市场安装)。置顶时间与顺序用于问答页每类「最多 3 条」的展示排序。
     */
    public void pin(Long userId, Long templateId, boolean pinned) {
        PromptTemplate t = templateMapper.selectById(templateId);
        if (t == null) throw new BusinessException(ResultCode.NOT_FOUND);
        if (t.getUserId() == null || !t.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只能置顶自己的模板");
        }
        UserTemplate ut = userTemplateRow(userId, templateId);
        if (pinned) {
            if (ut == null) {
                ut = new UserTemplate();
                ut.setUserId(userId);
                ut.setTemplateId(templateId);
                ut.setEnabled(0);
                ut.setSortOrder(nextSortOrder(userId));
                ut.setPinned(1);
                ut.setPinnedAt(LocalDateTime.now());
                ut.setPinOrder(nextPinOrder(userId));
                userTemplateMapper.insert(ut);
            } else if (ut.getPinned() == null || ut.getPinned() != 1) {
                int order = nextPinOrder(userId);
                userTemplateMapper.update(null, new LambdaUpdateWrapper<UserTemplate>()
                        .eq(UserTemplate::getUserId, userId)
                        .eq(UserTemplate::getTemplateId, templateId)
                        .set(UserTemplate::getPinned, 1)
                        .set(UserTemplate::getPinnedAt, LocalDateTime.now())
                        .set(UserTemplate::getPinOrder, order));
            }
        } else if (ut != null && ut.getPinned() != null && ut.getPinned() == 1) {
            userTemplateMapper.update(null, new LambdaUpdateWrapper<UserTemplate>()
                    .eq(UserTemplate::getUserId, userId)
                    .eq(UserTemplate::getTemplateId, templateId)
                    .set(UserTemplate::getPinned, 0));
        }
    }

    /** 取当前用户自建模板;内置(id 属于系统)或他人模板一律拒绝。 */
    private PromptTemplate getOwned(Long userId, Long id) {
        PromptTemplate t = templateMapper.selectById(id);
        if (t == null) throw new BusinessException(ResultCode.NOT_FOUND);
        if (t.getUserId() == null || !t.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "内置模板或他人模板不可修改");
        }
        return t;
    }

    /** 当前用户在 user_template 中的关系行(templateId → 行)。 */
    private Map<Long, UserTemplate> userTemplateMap(Long userId) {
        List<UserTemplate> uts = userTemplateMapper.selectList(new LambdaQueryWrapper<UserTemplate>()
                .eq(UserTemplate::getUserId, userId));
        Map<Long, UserTemplate> m = new HashMap<>();
        for (UserTemplate ut : uts) m.put(ut.getTemplateId(), ut);
        return m;
    }

    private UserTemplate userTemplateRow(Long userId, Long templateId) {
        return userTemplateMapper.selectOne(new LambdaQueryWrapper<UserTemplate>()
                .eq(UserTemplate::getUserId, userId)
                .eq(UserTemplate::getTemplateId, templateId));
    }

    private int nextPinOrder(Long userId) {
        UserTemplate max = userTemplateMapper.selectOne(new LambdaQueryWrapper<UserTemplate>()
                .eq(UserTemplate::getUserId, userId)
                .orderByDesc(UserTemplate::getPinOrder)
                .last("limit 1"));
        return max == null || max.getPinOrder() == null ? 1 : max.getPinOrder() + 1;
    }

    private boolean isEnabled(Long userId, Long templateId) {
        Long c = userTemplateMapper.selectCount(new LambdaQueryWrapper<UserTemplate>()
                .eq(UserTemplate::getUserId, userId)
                .eq(UserTemplate::getTemplateId, templateId)
                .eq(UserTemplate::getEnabled, 1));
        return c != null && c > 0;
    }

    private Set<Long> favoriteIds(Long userId) {
        List<UserTemplateFavorite> favs = favoriteMapper.selectList(
                new LambdaQueryWrapper<UserTemplateFavorite>().eq(UserTemplateFavorite::getUserId, userId));
        Set<Long> ids = new HashSet<>();
        for (UserTemplateFavorite f : favs) ids.add(f.getTemplateId());
        return ids;
    }

    private boolean isFavorited(Long userId, Long templateId) {
        Long c = favoriteMapper.selectCount(new LambdaQueryWrapper<UserTemplateFavorite>()
                .eq(UserTemplateFavorite::getUserId, userId)
                .eq(UserTemplateFavorite::getTemplateId, templateId));
        return c != null && c > 0;
    }

    /** 收藏/取消收藏一个模板;favorite 为空时取反。返回最终是否已收藏。 */
    public boolean toggleFavorite(Long userId, Long templateId, Boolean favorite) {
        PromptTemplate t = templateMapper.selectById(templateId);
        if (t == null) throw new BusinessException(ResultCode.NOT_FOUND);
        boolean want = favorite != null ? favorite : !isFavorited(userId, templateId);
        if (want) {
            if (!isFavorited(userId, templateId)) {
                UserTemplateFavorite f = new UserTemplateFavorite();
                f.setUserId(userId);
                f.setTemplateId(templateId);
                favoriteMapper.insert(f);
            }
        } else {
            favoriteMapper.delete(new LambdaQueryWrapper<UserTemplateFavorite>()
                    .eq(UserTemplateFavorite::getUserId, userId)
                    .eq(UserTemplateFavorite::getTemplateId, templateId));
        }
        return want;
    }

    private int nextSortOrder(Long userId) {
        UserTemplate max = userTemplateMapper.selectOne(new LambdaQueryWrapper<UserTemplate>()
                .eq(UserTemplate::getUserId, userId)
                .orderByDesc(UserTemplate::getSortOrder)
                .last("limit 1"));
        return max == null ? 10 : max.getSortOrder() + 10;
    }

    private TemplateVo toVo(PromptTemplate t, boolean builtin, UserTemplate ut, boolean favorited) {
        boolean enabled = ut != null && ut.getEnabled() != null && ut.getEnabled() == 1;
        boolean pinned = ut != null && ut.getPinned() != null && ut.getPinned() == 1;
        return TemplateVo.builder()
                .id(t.getId())
                .title(t.getTitle())
                .category(t.getCategory())
                .description(t.getDescription())
                .tags(t.getTags())
                .content(t.getContent())
                .format(t.getFormat() != null ? t.getFormat() : "prompt")
                .skill(t.getSkill())
                .featured(t.getFeatured() != null && t.getFeatured() == 1)
                .thumb(t.getThumb())
                .icon(t.getIcon() != null ? t.getIcon() : "📋")
                .builtin(builtin)
                .source(t.getSource() != null ? t.getSource() : "self")
                .enabled(enabled)
                .favorited(favorited)
                .pinned(pinned)
                .pinOrder(ut != null ? ut.getPinOrder() : null)
                .pinnedAt(ut != null ? ut.getPinnedAt() : null)
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}
