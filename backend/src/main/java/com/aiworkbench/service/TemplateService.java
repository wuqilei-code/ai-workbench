package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.chat.TemplateRequest;
import com.aiworkbench.dto.chat.TemplateVo;
import com.aiworkbench.entity.PromptTemplate;
import com.aiworkbench.entity.UserTemplate;
import com.aiworkbench.mapper.PromptTemplateMapper;
import com.aiworkbench.mapper.UserTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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

    /** 新用户首启默认启用的内置模板数量(前 N 个按 sort_order) */
    private static final int DEFAULT_ENABLE_COUNT = 6;
    /** 首页最大展示数 */
    private static final int HOME_MAX = 12;
    /** 无启用时的兜底内置数量 */
    private static final int HOME_FALLBACK = 6;

    /** 当前用户的模板列表(内置 + 自建),各带 enabled 状态。 */
    public List<TemplateVo> listMine(Long userId) {
        ensureDefaults(userId);
        List<PromptTemplate> builtin = templateMapper.selectList(
                new LambdaQueryWrapper<PromptTemplate>()
                        .isNull(PromptTemplate::getUserId)
                        .orderByAsc(PromptTemplate::getSortOrder));
        List<PromptTemplate> mine = templateMapper.selectList(
                new LambdaQueryWrapper<PromptTemplate>()
                        .eq(PromptTemplate::getUserId, userId)
                        .orderByAsc(PromptTemplate::getSortOrder));
        Set<Long> enabledIds = enabledTemplateIds(userId);

        List<TemplateVo> out = new ArrayList<>();
        for (PromptTemplate t : builtin) out.add(toVo(t, true, enabledIds.contains(t.getId())));
        for (PromptTemplate t : mine) out.add(toVo(t, false, enabledIds.contains(t.getId())));
        return out;
    }

    /** 首页展示的模板:用户启用的(最多 HOME_MAX 个);无启用时兜底内置前几个。 */
    public List<TemplateVo> homeTemplates(Long userId) {
        ensureDefaults(userId);
        List<UserTemplate> uts = userTemplateMapper.selectList(
                new LambdaQueryWrapper<UserTemplate>()
                        .eq(UserTemplate::getUserId, userId)
                        .eq(UserTemplate::getEnabled, 1)
                        .orderByAsc(UserTemplate::getSortOrder)
                        .last("limit " + HOME_MAX));
        if (uts.isEmpty()) {
            // 兜底:内置前几个(首启已 ensureDefaults,理论上不会走到,防御)
            List<PromptTemplate> builtin = templateMapper.selectList(
                    new LambdaQueryWrapper<PromptTemplate>()
                            .isNull(PromptTemplate::getUserId)
                            .orderByAsc(PromptTemplate::getSortOrder)
                            .last("limit " + HOME_FALLBACK));
            return builtin.stream().map(t -> toVo(t, true, true)).toList();
        }
        List<Long> ids = uts.stream().map(UserTemplate::getTemplateId).toList();
        List<PromptTemplate> templates = templateMapper.selectByIds(ids);
        Map<Long, PromptTemplate> byId = templates.stream().collect(Collectors.toMap(PromptTemplate::getId, t -> t));
        List<TemplateVo> out = new ArrayList<>();
        for (UserTemplate ut : uts) {
            PromptTemplate t = byId.get(ut.getTemplateId());
            if (t != null) {
                out.add(toVo(t, t.getUserId() == null, true));
            }
        }
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
            // 限制首页最多启用 HOME_MAX(12)个:已启用达到上限且该模板未启用时拒绝
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

    /** 新用户首启:若没有任何 user_template 记录,自动启用内置前 DEFAULT_ENABLE_COUNT 个。 */
    public void ensureDefaults(Long userId) {
        Long count = userTemplateMapper.selectCount(new LambdaQueryWrapper<UserTemplate>()
                .eq(UserTemplate::getUserId, userId));
        if (count != null && count > 0) return;
        List<PromptTemplate> builtin = templateMapper.selectList(
                new LambdaQueryWrapper<PromptTemplate>()
                        .isNull(PromptTemplate::getUserId)
                        .orderByAsc(PromptTemplate::getSortOrder)
                        .last("limit " + DEFAULT_ENABLE_COUNT));
        int sort = 10;
        for (PromptTemplate t : builtin) {
            UserTemplate ut = new UserTemplate();
            ut.setUserId(userId);
            ut.setTemplateId(t.getId());
            ut.setEnabled(1);
            ut.setSortOrder(sort);
            userTemplateMapper.insert(ut);
            sort += 10;
        }
        log.info("seeded default templates for user {} ({} enabled)", userId, builtin.size());
    }

    public TemplateVo create(Long userId, TemplateRequest req) {
        PromptTemplate t = new PromptTemplate();
        t.setUserId(userId);
        t.setTitle(req.getTitle());
        t.setCategory(req.getCategory());
        t.setContent(req.getContent());
        t.setIcon(req.getIcon() != null && !req.getIcon().isBlank() ? req.getIcon() : "📋");
        t.setSortOrder(1000);
        templateMapper.insert(t);
        // 自建默认启用
        setEnabled(userId, t.getId(), true);
        return toVo(t, false, true);
    }

    public TemplateVo update(Long userId, Long id, TemplateRequest req) {
        PromptTemplate t = getOwned(userId, id);
        t.setTitle(req.getTitle());
        t.setCategory(req.getCategory());
        t.setContent(req.getContent());
        if (req.getIcon() != null && !req.getIcon().isBlank()) {
            t.setIcon(req.getIcon());
        }
        templateMapper.updateById(t);
        return toVo(t, false, isEnabled(userId, id));
    }

    public void delete(Long userId, Long id) {
        PromptTemplate t = getOwned(userId, id);
        templateMapper.deleteById(t.getId());
        userTemplateMapper.delete(new LambdaQueryWrapper<UserTemplate>()
                .eq(UserTemplate::getUserId, userId)
                .eq(UserTemplate::getTemplateId, id));
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

    private Set<Long> enabledTemplateIds(Long userId) {
        List<UserTemplate> uts = userTemplateMapper.selectList(new LambdaQueryWrapper<UserTemplate>()
                .eq(UserTemplate::getUserId, userId)
                .eq(UserTemplate::getEnabled, 1));
        Set<Long> ids = new HashSet<>();
        for (UserTemplate ut : uts) ids.add(ut.getTemplateId());
        return ids;
    }

    private boolean isEnabled(Long userId, Long templateId) {
        Long c = userTemplateMapper.selectCount(new LambdaQueryWrapper<UserTemplate>()
                .eq(UserTemplate::getUserId, userId)
                .eq(UserTemplate::getTemplateId, templateId)
                .eq(UserTemplate::getEnabled, 1));
        return c != null && c > 0;
    }

    private int nextSortOrder(Long userId) {
        UserTemplate max = userTemplateMapper.selectOne(new LambdaQueryWrapper<UserTemplate>()
                .eq(UserTemplate::getUserId, userId)
                .orderByDesc(UserTemplate::getSortOrder)
                .last("limit 1"));
        return max == null ? 10 : max.getSortOrder() + 10;
    }

    private TemplateVo toVo(PromptTemplate t, boolean builtin, boolean enabled) {
        return TemplateVo.builder()
                .id(t.getId())
                .title(t.getTitle())
                .category(t.getCategory())
                .content(t.getContent())
                .icon(t.getIcon() != null ? t.getIcon() : "📋")
                .builtin(builtin)
                .enabled(enabled)
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}
