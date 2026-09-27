package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.entity.PromptTemplate;
import com.aiworkbench.entity.TemplateMarketplace;
import com.aiworkbench.entity.User;
import com.aiworkbench.entity.UserTemplateInstall;
import com.aiworkbench.mapper.PromptTemplateMapper;
import com.aiworkbench.mapper.TemplateMarketplaceMapper;
import com.aiworkbench.mapper.UserMapper;
import com.aiworkbench.mapper.UserTemplateInstallMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板市场:用户把自建模板分享到市场,他人安装到自己的模板库。
 * 安装 = 在 prompt_template 建一条 user_id=当前用户的记录(来源=市场),并登记 user_template_install。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateMarketService {

    private final TemplateMarketplaceMapper marketMapper;
    private final UserTemplateInstallMapper installMapper;
    private final PromptTemplateMapper templateMapper;
    private final UserMapper userMapper;

    /** 市场列表(新→旧),带 installed(当前用户是否已安装)/ mine(是否本人发布)。 */
    public List<Map<String, Object>> list(Long userId) {
        List<TemplateMarketplace> all = marketMapper.selectList(
                new LambdaQueryWrapper<TemplateMarketplace>().orderByDesc(TemplateMarketplace::getId));
        Map<Long, Long> installedIds = new HashMap<>();
        for (UserTemplateInstall it : installMapper.selectList(
                new LambdaQueryWrapper<UserTemplateInstall>().eq(UserTemplateInstall::getUserId, userId))) {
            installedIds.put(it.getMarketplaceId(), it.getTemplateId());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TemplateMarketplace m : all) {
            out.add(toVo(m, installedIds.containsKey(m.getId()), m.getUserId().equals(userId)));
        }
        return out;
    }

    /** 我发布的模板。 */
    public List<Map<String, Object>> listMinePublished(Long userId) {
        List<TemplateMarketplace> mine = marketMapper.selectList(
                new LambdaQueryWrapper<TemplateMarketplace>()
                        .eq(TemplateMarketplace::getUserId, userId)
                        .orderByDesc(TemplateMarketplace::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (TemplateMarketplace m : mine) out.add(toVo(m, true, true));
        return out;
    }

    /** 分享(发布)一个自有模板到市场。 */
    public Map<String, Object> publish(Long userId, Long templateId) {
        PromptTemplate t = templateMapper.selectById(templateId);
        if (t == null) throw new BusinessException(ResultCode.NOT_FOUND);
        if (t.getUserId() == null || !t.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只能分享自己创建的模板");
        }
        if ("installed".equals(t.getSource())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "市场安装的模板不可再次分享");
        }
        User author = userMapper.selectById(userId);
        TemplateMarketplace m = new TemplateMarketplace();
        m.setUserId(userId);
        m.setAuthorName(author != null && author.getUsername() != null ? author.getUsername() : "");
        m.setTitle(t.getTitle());
        m.setDescription(t.getDescription());
        m.setCategory(t.getCategory());
        m.setTags(t.getTags());
        m.setContent(t.getContent());
        m.setFormat(t.getFormat() != null ? t.getFormat() : "prompt");
        m.setSkill(t.getSkill());
        m.setIcon(t.getIcon());
        m.setInstalls(0);
        marketMapper.insert(m);
        return toVo(m, true, true);
    }

    /** 取消分享(仅本人发布的可下架)。 */
    public void unpublish(Long userId, Long id) {
        TemplateMarketplace m = marketMapper.selectById(id);
        if (m == null) throw new BusinessException(ResultCode.NOT_FOUND);
        if (!m.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只能下架自己发布的模板");
        }
        marketMapper.deleteById(id);
        installMapper.delete(new LambdaQueryWrapper<UserTemplateInstall>()
                .eq(UserTemplateInstall::getMarketplaceId, id));
    }

    /** 安装到我的模板库(已安装则报冲突)。 */
    @Transactional
    public void install(Long userId, Long id) {
        TemplateMarketplace m = marketMapper.selectById(id);
        if (m == null) throw new BusinessException(ResultCode.NOT_FOUND);
        Long dup = installMapper.selectCount(new LambdaQueryWrapper<UserTemplateInstall>()
                .eq(UserTemplateInstall::getUserId, userId)
                .eq(UserTemplateInstall::getMarketplaceId, id));
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "已安装过该模板");
        }
        // 复制到 prompt_template(用户自有),标题加来源避免重名混淆
        PromptTemplate t = new PromptTemplate();
        t.setUserId(userId);
        t.setSource("installed");
        t.setTitle(m.getTitle());
        t.setDescription(m.getDescription());
        t.setCategory(m.getCategory());
        t.setTags(m.getTags());
        t.setContent(m.getContent());
        t.setFormat(m.getFormat());
        t.setSkill(m.getSkill());
        t.setFeatured(0);
        t.setIcon(m.getIcon() == null || m.getIcon().isBlank() ? "📋" : m.getIcon());
        t.setSortOrder(1000);
        templateMapper.insert(t);

        UserTemplateInstall it = new UserTemplateInstall();
        it.setUserId(userId);
        it.setMarketplaceId(id);
        it.setTemplateId(t.getId());
        installMapper.insert(it);

        TemplateMarketplace upd = new TemplateMarketplace();
        upd.setId(id);
        upd.setInstalls((m.getInstalls() == null ? 0 : m.getInstalls()) + 1);
        marketMapper.updateById(upd);
        log.info("[template-market] user {} installed template market id={} -> template id={}", userId, id, t.getId());
    }

    private Map<String, Object> toVo(TemplateMarketplace m, boolean installed, boolean mine) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", m.getId());
        o.put("title", m.getTitle());
        o.put("description", m.getDescription());
        o.put("category", m.getCategory());
        o.put("tags", m.getTags());
        o.put("content", m.getContent());
        o.put("format", m.getFormat());
        o.put("skill", m.getSkill());
        o.put("icon", m.getIcon());
        o.put("authorName", m.getAuthorName());
        o.put("installs", m.getInstalls() == null ? 0 : m.getInstalls());
        o.put("installed", installed);
        o.put("mine", mine);
        o.put("updatedAt", m.getUpdatedAt());
        return o;
    }
}
