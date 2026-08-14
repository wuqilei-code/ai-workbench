package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.admin.McpRequest;
import com.aiworkbench.dto.admin.McpVo;
import com.aiworkbench.entity.McpConfig;
import com.aiworkbench.mapper.McpConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置管理。
 * <ul>
 *   <li>公共配置:userId=0,由管理员在「MCP Server 管理」维护,所有用户共享;</li>
 *   <li>私有配置:userId&gt;0,每个用户可在「我的 MCP」维护,仅自己生效。</li>
 * </ul>
 * 渲染 opencode.json 的 mcp 段时,公共配置与用户私有配置合并(私有 name 覆盖公共同名项)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpConfigService {

    private final McpConfigMapper mcpConfigMapper;
    private final ObjectMapper objectMapper;

    // ---------- 查询 ----------

    /** 某用户可见的全部 MCP:其私有配置(userId=userId)。公共配置单独用 listPublic。 */
    public List<McpVo> listPrivate(Long userId) {
        return mcpConfigMapper.selectList(new LambdaQueryWrapper<McpConfig>()
                        .eq(McpConfig::getUserId, userId)
                        .orderByAsc(McpConfig::getSortOrder))
                .stream().map(this::toVo).toList();
    }

    /** 管理员公共配置(userId=0)。 */
    public List<McpVo> listPublic() {
        return mcpConfigMapper.selectList(new LambdaQueryWrapper<McpConfig>()
                        .eq(McpConfig::getUserId, 0L)
                        .orderByAsc(McpConfig::getSortOrder))
                .stream().map(this::toVo).toList();
    }

    // ---------- CRUD ----------

    /** 管理员新增公共 MCP(userId=0)。 */
    public McpVo createPublic(McpRequest req) {
        return create(0L, req);
    }

    /** 用户新增自己的私有 MCP(userId 由服务端强制指定)。 */
    public McpVo createPrivate(Long userId, McpRequest req) {
        return create(userId, req);
    }

    private McpVo create(Long userId, McpRequest req) {
        validate(req);
        if (mcpConfigMapper.selectOne(new LambdaQueryWrapper<McpConfig>()
                .eq(McpConfig::getUserId, userId)
                .eq(McpConfig::getName, req.getName())) != null) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "MCP server 名称已存在");
        }
        McpConfig c = new McpConfig();
        c.setUserId(userId);
        c.setName(req.getName());
        c.setType(req.getType());
        c.setUrl(req.getUrl());
        c.setHeaders(req.getHeaders());
        c.setEnabled(req.getEnabled() != null ? req.getEnabled() : 1);
        c.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        mcpConfigMapper.insert(c);
        return toVo(c);
    }

    /** 管理员更新公共 MCP(仅 userId=0 记录可改)。 */
    public McpVo updatePublic(Long id, McpRequest req) {
        return update(0L, id, req);
    }

    /** 用户更新自己的私有 MCP(仅归属该用户的记录可改)。 */
    public McpVo updatePrivate(Long userId, Long id, McpRequest req) {
        return update(userId, id, req);
    }

    private McpVo update(Long userId, Long id, McpRequest req) {
        McpConfig c = mcpConfigMapper.selectOne(new LambdaQueryWrapper<McpConfig>()
                .eq(McpConfig::getId, id)
                .eq(McpConfig::getUserId, userId));
        if (c == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        validate(req);
        // 改名撞名(排除自身)冲突检查
        McpConfig dup = mcpConfigMapper.selectOne(new LambdaQueryWrapper<McpConfig>()
                .eq(McpConfig::getUserId, userId)
                .eq(McpConfig::getName, req.getName())
                .ne(McpConfig::getId, id));
        if (dup != null) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "MCP server 名称已存在");
        }
        c.setName(req.getName());
        c.setType(req.getType());
        c.setUrl(req.getUrl());
        c.setHeaders(req.getHeaders());
        if (req.getEnabled() != null) c.setEnabled(req.getEnabled());
        if (req.getSortOrder() != null) c.setSortOrder(req.getSortOrder());
        mcpConfigMapper.updateById(c);
        return toVo(c);
    }

    /** 管理员删除公共 MCP(仅 userId=0 记录可删)。 */
    public void deletePublic(Long id) {
        delete(0L, id);
    }

    /** 用户删除自己的私有 MCP(仅归属该用户的记录可删)。 */
    public void deletePrivate(Long userId, Long id) {
        delete(userId, id);
    }

    private void delete(Long userId, Long id) {
        McpConfig c = mcpConfigMapper.selectOne(new LambdaQueryWrapper<McpConfig>()
                .eq(McpConfig::getId, id)
                .eq(McpConfig::getUserId, userId));
        if (c != null) {
            mcpConfigMapper.deleteById(id);
        }
    }

    // ---------- 渲染(供 applyConfig / PerUserConfigWriter) ----------

    /** 渲染模板的 mcp 段:仅公共配置(userId=0)。 */
    public Map<String, Object> renderMcpSection() {
        return renderMcpSection(null);
    }

    /**
     * 渲染某用户实际生效的 mcp 段:公共配置叠加该用户私有配置(私有同名覆盖公共)。
     * userId=null 时仅公共配置(applyConfig 写模板真相源用)。
     */
    public Map<String, Object> renderMcpSection(Long userId) {
        Map<String, Object> mcpConfig = new LinkedHashMap<>();
        appendSection(mcpConfig, listPublic());
        if (userId != null) {
            appendSection(mcpConfig, listPrivate(userId));
        }
        return mcpConfig;
    }

    private void appendSection(Map<String, Object> mcpConfig, List<McpVo> list) {
        for (McpVo vo : list) {
            if (vo.getEnabled() == null || vo.getEnabled() != 1) {
                continue;
            }
            // 仅支持 remote MCP(HTTP/SSE URL);历史 local 配置不再渲染
            if (!"remote".equals(vo.getType())) {
                continue;
            }
            McpConfig c = toEntity(vo);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("enabled", true);
            entry.put("type", "remote");
            if (c.getUrl() != null && !c.getUrl().isBlank()) {
                entry.put("url", c.getUrl());
            }
            Map<String, Object> headers = parseJsonMap(c.getHeaders());
            if (!headers.isEmpty()) {
                entry.put("headers", headers);
            }
            mcpConfig.put(c.getName(), entry);
        }
    }

    // ---------- 内部 ----------

    private void validate(McpRequest req) {
        if (!"remote".equals(req.getType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "type 只能是 remote");
        }
        if (req.getUrl() == null || req.getUrl().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "remote 类型必须提供 url");
        }
    }

    /** 解析 JSON 字符串为 Map(空/非法返回空 Map,不抛错,渲染时忽略)。 */
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> m = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return m != null ? m : Map.of();
        } catch (Exception e) {
            log.warn("parse MCP json field failed (ignored): {}", e.toString());
            return Map.of();
        }
    }

    private McpVo toVo(McpConfig c) {
        return McpVo.builder()
                .id(c.getId())
                .userId(c.getUserId())
                .name(c.getName())
                .type(c.getType())
                .command(c.getCommand())
                .url(c.getUrl())
                .headers(c.getHeaders())
                .environment(c.getEnvironment())
                .enabled(c.getEnabled())
                .sortOrder(c.getSortOrder())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private McpConfig toEntity(McpVo vo) {
        McpConfig c = new McpConfig();
        c.setId(vo.getId());
        c.setUserId(vo.getUserId());
        c.setName(vo.getName());
        c.setType(vo.getType());
        c.setCommand(vo.getCommand());
        c.setUrl(vo.getUrl());
        c.setHeaders(vo.getHeaders());
        c.setEnvironment(vo.getEnvironment());
        c.setEnabled(vo.getEnabled());
        c.setSortOrder(vo.getSortOrder());
        return c;
    }
}