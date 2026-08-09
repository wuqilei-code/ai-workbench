package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.chat.TemplateRequest;
import com.aiworkbench.dto.chat.TemplateVo;
import com.aiworkbench.entity.PromptTemplate;
import com.aiworkbench.mapper.PromptTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词模板库:系统内置(全员只读)+ 个人自建。
 * 内置模板 user_id 为 NULL,只读;个人模板按 user_id 隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final PromptTemplateMapper templateMapper;

    /** 当前用户的模板列表:内置(全部) + 个人自建,按 sort_order 排序。 */
    public List<TemplateVo> listMine(Long userId) {
        List<PromptTemplate> builtin = templateMapper.selectList(
                new LambdaQueryWrapper<PromptTemplate>()
                        .isNull(PromptTemplate::getUserId)
                        .orderByAsc(PromptTemplate::getSortOrder));
        List<PromptTemplate> mine = templateMapper.selectList(
                new LambdaQueryWrapper<PromptTemplate>()
                        .eq(PromptTemplate::getUserId, userId)
                        .orderByAsc(PromptTemplate::getSortOrder));
        List<TemplateVo> out = new ArrayList<>();
        for (PromptTemplate t : builtin) out.add(toVo(t, true));
        for (PromptTemplate t : mine) out.add(toVo(t, false));
        return out;
    }

    public TemplateVo create(Long userId, TemplateRequest req) {
        PromptTemplate t = new PromptTemplate();
        t.setUserId(userId);
        t.setTitle(req.getTitle());
        t.setCategory(req.getCategory());
        t.setContent(req.getContent());
        t.setSortOrder(1000);
        templateMapper.insert(t);
        return toVo(t, false);
    }

    public TemplateVo update(Long userId, Long id, TemplateRequest req) {
        PromptTemplate t = getOwned(userId, id);
        t.setTitle(req.getTitle());
        t.setCategory(req.getCategory());
        t.setContent(req.getContent());
        templateMapper.updateById(t);
        return toVo(t, false);
    }

    public void delete(Long userId, Long id) {
        PromptTemplate t = getOwned(userId, id);
        templateMapper.deleteById(t.getId());
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

    private TemplateVo toVo(PromptTemplate t, boolean builtin) {
        return TemplateVo.builder()
                .id(t.getId())
                .title(t.getTitle())
                .category(t.getCategory())
                .content(t.getContent())
                .builtin(builtin)
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}
