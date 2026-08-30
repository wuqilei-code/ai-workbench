package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.admin.McpRequest;
import com.aiworkbench.dto.admin.McpVo;
import com.aiworkbench.entity.McpConfig;
import com.aiworkbench.mapper.McpConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MCP Server 配置管理。user_id=0 为管理员公共配置(所有用户共享),user_id>0 为用户私有配置。
 * 仅支持 remote(HTTP/SSE URL);local 已废弃,读写时忽略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpConfigService {

    private static final Long PUBLIC_USER = 0L;
    /** remote: 远程 MCP server(HTTP/SSE URL);openapi: OpenAPI/Swagger 文档地址,批量转工具;
     *  manual: 手动接口组(base_url + 内联接口清单),不依赖 Swagger */
    public static final String TYPE_REMOTE = "remote";
    public static final String TYPE_OPENAPI = "openapi";
    public static final String TYPE_MANUAL = "manual";
    private static final java.util.Set<String> TYPES =
            java.util.Set.of(TYPE_REMOTE, TYPE_OPENAPI, TYPE_MANUAL);
    /** name 标识:小写 kebab-case(与 mcp_config.uk_mcp_user_name 唯一键约束一致) */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private final McpConfigMapper mcpConfigMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ---------- User CRUD (user_id>0) ----------

    public List<McpVo> listUser(Long userId) {
        return mcpConfigMapper.selectList(new LambdaQueryWrapper<McpConfig>()
                .eq(McpConfig::getUserId, userId)
                .orderByAsc(McpConfig::getSortOrder)
                .orderByDesc(McpConfig::getId)).stream().map(this::toVo).toList();
    }

    public McpVo createUser(Long userId, McpRequest req) {
        return create(userId, req);
    }

    public McpVo updateUser(Long userId, Long id, McpRequest req) {
        McpConfig c = getOwned(userId, id);
        return update(c, req);
    }

    public void deleteUser(Long userId, Long id) {
        McpConfig c = getOwned(userId, id);
        mcpConfigMapper.deleteById(c.getId());
    }

    private McpConfig getOwned(Long userId, Long id) {
        McpConfig c = mcpConfigMapper.selectById(id);
        if (c == null || !c.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return c;
    }

    // ---------- Admin CRUD (user_id=0) ----------

    public List<McpVo> listPublic() {
        return mcpConfigMapper.selectList(new LambdaQueryWrapper<McpConfig>()
                .eq(McpConfig::getUserId, PUBLIC_USER)
                .orderByAsc(McpConfig::getSortOrder)
                .orderByDesc(McpConfig::getId)).stream().map(this::toVo).toList();
    }

    public McpVo createPublic(McpRequest req) {
        return create(PUBLIC_USER, req);
    }

    public McpVo updatePublic(Long id, McpRequest req) {
        McpConfig c = getPublic(id);
        return update(c, req);
    }

    public void deletePublic(Long id) {
        McpConfig c = getPublic(id);
        mcpConfigMapper.deleteById(c.getId());
    }

    private McpConfig getPublic(Long id) {
        McpConfig c = mcpConfigMapper.selectById(id);
        if (c == null || !c.getUserId().equals(PUBLIC_USER)) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return c;
    }

    /**
     * 该用户生效的启用的 MCP server 配置(管理员公共 + 用户私有合并),供 agent-service 加载工具。
     * 按 name 排序保证请求体稳定,避免二次握手每次都变。
     */
    public List<McpConfig> enabledForUser(Long userId) {
        return mcpConfigMapper.selectList(new LambdaQueryWrapper<McpConfig>()
                .eq(McpConfig::getEnabled, 1)
                .in(McpConfig::getUserId, PUBLIC_USER, userId)
                .in(McpConfig::getType, TYPE_REMOTE, TYPE_OPENAPI, TYPE_MANUAL)
                .orderByAsc(McpConfig::getSortOrder)
                .orderByAsc(McpConfig::getId));
    }

    // ---------- shared ----------

    private McpVo create(Long ownerId, McpRequest req) {
        String name = req.getName() == null ? "" : req.getName().trim();
        if (!SLUG.matcher(name).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "MCP 名称需为小写字母/数字/连字符(如 my-server)");
        }
        if (!TYPES.contains(req.getType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "类型需为 remote(HTTP/SSE)、openapi(OpenAPI 文档地址)或 manual(手动接口)");
        }
        String url = req.getUrl() == null ? "" : req.getUrl().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "URL 需以 http(s):// 开头");
        }
        validateHeaders(req.getHeaders());
        validateConfig(TYPE_MANUAL.equals(req.getType()) ? req.getConfig() : null);
        Long dup = mcpConfigMapper.selectCount(new LambdaQueryWrapper<McpConfig>()
                .eq(McpConfig::getUserId, ownerId).eq(McpConfig::getName, name));
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "同名 MCP server 已存在");
        }
        McpConfig c = new McpConfig();
        c.setUserId(ownerId);
        c.setName(name);
        c.setType(req.getType());
        c.setUrl(url);
        c.setHeaders(processHeadersIn(req.getHeaders()));
        c.setConfig(processConfigIn(TYPE_MANUAL.equals(req.getType()) ? req.getConfig() : null));
        c.setEnabled(req.getEnabled() != null ? req.getEnabled() : 1);
        c.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        mcpConfigMapper.insert(c);
        return toVo(c);
    }

    private McpVo update(McpConfig c, McpRequest req) {
        if (req.getType() != null && !TYPES.contains(req.getType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "类型需为 remote(HTTP/SSE)、openapi(OpenAPI 文档地址)或 manual(手动接口)");
        }
        if (req.getType() != null && !req.getType().equals(c.getType())) {
            c.setType(req.getType());
        }
        if (req.getUrl() != null) {
            String url = req.getUrl().trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "URL 需以 http(s):// 开头");
            }
            c.setUrl(url);
        }
        // headers:null=不修改,非 null=覆盖(含清空)
        if (req.getHeaders() != null) {
            validateHeaders(req.getHeaders());
            c.setHeaders(processHeadersIn(req.getHeaders()));
        }
        // config:null=不修改;manual 类型下非 null=覆盖(含清空)
        if (req.getConfig() != null) {
            validateConfig(c.getType());
            c.setConfig(processConfigIn(req.getConfig()));
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            String name = req.getName().trim();
            if (!SLUG.matcher(name).matches()) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                        "MCP 名称需为小写字母/数字/连字符(如 my-server)");
            }
            if (!name.equals(c.getName())) {
                Long dup = mcpConfigMapper.selectCount(new LambdaQueryWrapper<McpConfig>()
                        .eq(McpConfig::getUserId, c.getUserId()).eq(McpConfig::getName, name));
                if (dup != null && dup > 0) {
                    throw new BusinessException(ResultCode.CONFLICT.getCode(), "同名 MCP server 已存在");
                }
                c.setName(name);
            }
        }
        if (req.getEnabled() != null) c.setEnabled(req.getEnabled());
        if (req.getSortOrder() != null) c.setSortOrder(req.getSortOrder());
        mcpConfigMapper.updateById(c);
        return toVo(c);
    }

    private void validateHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) return;
        try {
            objectMapper.readValue(headersJson, Map.class);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "headers 需为 JSON 对象(如 {\"Authorization\":\"Bearer xxx\"})");
        }
    }

    /** 归一化 headers:去掉空白;null/空串/{} 统一存 null。 */
    private String processHeadersIn(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) return null;
        try {
            Map<?, ?> m = objectMapper.readValue(headersJson, Map.class);
            if (m.isEmpty()) return null;
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return headersJson;
        }
    }

    /** 校验手动接口清单:manual 类型必填且为 JSON 数组;其他类型忽略(null)。 */
    private void validateConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) return;
        try {
            Object v = objectMapper.readValue(configJson, Object.class);
            if (!(v instanceof java.util.List<?>)) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                        "接口清单需为 JSON 数组");
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "接口清单需为合法 JSON 数组");
        }
    }

    /** 归一化接口清单:null/空串统一存 null;合法数组按紧凑 JSON 存储。 */
    private String processConfigIn(String configJson) {
        if (configJson == null || configJson.isBlank()) return null;
        try {
            Object v = objectMapper.readValue(configJson, Object.class);
            if (!(v instanceof java.util.List<?> list) || list.isEmpty()) return null;
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            return configJson;
        }
    }

    private McpVo toVo(McpConfig c) {
        return McpVo.builder()
                .id(c.getId())
                .userId(c.getUserId())
                .name(c.getName())
                .type(c.getType())
                .url(c.getUrl())
                .headers(c.getHeaders())
                .config(c.getConfig())
                .enabled(c.getEnabled())
                .sortOrder(c.getSortOrder())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}