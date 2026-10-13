package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.CryptoService;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.admin.ModelRequest;
import com.aiworkbench.dto.admin.ModelVo;
import com.aiworkbench.dto.admin.ProviderRequest;
import com.aiworkbench.dto.admin.ProviderVo;
import com.aiworkbench.entity.ModelConfig;
import com.aiworkbench.entity.ProviderConfig;
import com.aiworkbench.mapper.ModelConfigMapper;
import com.aiworkbench.mapper.ProviderConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider/Model 配置管理(deepagents 单轨,仅 CRUD 供前端模型选择器/管理页用)。
 * <p>
 * 关联键说明:model_config.provider_id 存的是**逻辑 provider id 字符串**(如 "openai"),
 * 与 provider_config.provider_id 对应,而非 provider_config.id。
 * <p>
 * apiKey 经 {@link CryptoService} AES-GCM 加密落库(密文带 enc: 前缀),列表返回脱敏。
 * 已不再渲染任何 opencode 配置文件(执行环境由 agent-service 侧配置)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderConfigService {

    private final ProviderConfigMapper providerConfigMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final CryptoService crypto;

    // ---------- 查询 ----------

    public List<ProviderVo> listAll() {
        List<ProviderConfig> providers = providerConfigMapper.selectList(
                new LambdaQueryWrapper<ProviderConfig>().orderByAsc(ProviderConfig::getSortOrder));
        List<ModelConfig> allModels = modelConfigMapper.selectList(
                new LambdaQueryWrapper<ModelConfig>().orderByAsc(ModelConfig::getSortOrder));
        // 按 provider_config.id 分组模型(模型表存的是逻辑 providerId,需匹配 provider.providerId)
        Map<String, List<ModelVo>> modelsByLogical = new LinkedHashMap<>();
        for (ModelConfig m : allModels) {
            modelsByLogical.computeIfAbsent(m.getProviderId(), k -> new ArrayList<>())
                    .add(toModelVo(m));
        }
        return providers.stream()
                .map(p -> toProviderVo(p, modelsByLogical.getOrDefault(p.getProviderId(), List.of())))
                .toList();
    }

    /** GET /api/models:启用的 provider + 启用的 model */
    public List<ProviderVo> listAvailable() {
        List<ProviderConfig> providers = providerConfigMapper.selectList(
                new LambdaQueryWrapper<ProviderConfig>()
                        .eq(ProviderConfig::getEnabled, 1)
                        .orderByAsc(ProviderConfig::getSortOrder));
        List<ProviderVo> result = new ArrayList<>();
        for (ProviderConfig p : providers) {
            List<ModelConfig> models = modelConfigMapper.selectList(
                    new LambdaQueryWrapper<ModelConfig>()
                            .eq(ModelConfig::getProviderId, p.getProviderId())
                            .eq(ModelConfig::getEnabled, 1)
                            .orderByAsc(ModelConfig::getSortOrder));
            result.add(toProviderVo(p, models.stream().map(this::toModelVo).toList()));
        }
        return result;
    }

    // ---------- 运行时模型解析 ----------

    /** 供 agent 调用使用的运行时模型配置(baseUrl/解密后的 apiKey/modelId)。 */
    public record RuntimeModel(String providerId, String modelId, String baseUrl, String apiKey) {}

    /**
     * 解析「实际调用」应使用的模型运行时配置。
     * <p>
     * 入参为会话所选的 providerId / modelId;缺失或不可用时,回退到第一个启用的 provider
     * 及其第一个启用模型。若系统尚未配置任何启用的 provider,返回 {@code null}
     * (交由 agent-service 回退到自身环境变量 AGENT_MODEL*)。
     * <p>
     * apiKey 在此解密为明文,仅经内网(X-Internal-Token 保护)传递给 agent-service。
     */
    public RuntimeModel resolveRuntimeModel(String providerId, String modelId) {
        ProviderConfig p = null;
        if (providerId != null && !providerId.isBlank()) {
            p = providerConfigMapper.selectOne(new LambdaQueryWrapper<ProviderConfig>()
                    .eq(ProviderConfig::getProviderId, providerId)
                    .eq(ProviderConfig::getEnabled, 1));
        }
        if (p == null) {
            p = providerConfigMapper.selectOne(new LambdaQueryWrapper<ProviderConfig>()
                    .eq(ProviderConfig::getEnabled, 1)
                    .orderByAsc(ProviderConfig::getSortOrder)
                    .last("LIMIT 1"));
        }
        if (p == null) {
            return null;
        }
        String mid = (modelId != null && !modelId.isBlank()) ? modelId : null;
        if (mid == null) {
            ModelConfig m = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfig>()
                    .eq(ModelConfig::getProviderId, p.getProviderId())
                    .eq(ModelConfig::getEnabled, 1)
                    .orderByAsc(ModelConfig::getSortOrder)
                    .last("LIMIT 1"));
            mid = (m != null) ? m.getModelId() : null;
        }
        return new RuntimeModel(p.getProviderId(), mid, p.getBaseUrl(), crypto.decrypt(p.getApiKey()));
    }

    // ---------- Provider CRUD ----------

    public ProviderVo createProvider(ProviderRequest req) {
        if (providerConfigMapper.selectOne(new LambdaQueryWrapper<ProviderConfig>()
                .eq(ProviderConfig::getProviderId, req.getProviderId())) != null) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "providerId 已存在");
        }
        ProviderConfig p = new ProviderConfig();
        p.setProviderId(req.getProviderId());
        p.setName(req.getName());
        p.setBaseUrl(req.getBaseUrl());
        p.setNpm(req.getNpm());
        p.setApiKey(crypto.encrypt(req.getApiKey()));
        p.setEnvVarName(req.getEnvVarName() != null && !req.getEnvVarName().isBlank()
                ? req.getEnvVarName() : defaultEnvVar(req.getProviderId()));
        p.setEnabled(req.getEnabled() != null ? req.getEnabled() : 1);
        p.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        providerConfigMapper.insert(p);
        return toProviderVo(p, List.of());
    }

    public ProviderVo updateProvider(Long id, ProviderRequest req) {
        ProviderConfig p = providerConfigMapper.selectById(id);
        if (p == null) throw new BusinessException(ResultCode.NOT_FOUND);
        p.setName(req.getName());
        p.setBaseUrl(req.getBaseUrl());
        // npm: null=不修改,非 null=覆盖(含清空),与 apiKey/envVarName 语义一致
        if (req.getNpm() != null) p.setNpm(req.getNpm());
        if (req.getEnvVarName() != null) p.setEnvVarName(req.getEnvVarName());
        // apiKey:null=不修改,非 null=覆盖(含清空)。覆盖时加密落库
        if (req.getApiKey() != null) p.setApiKey(crypto.encrypt(req.getApiKey()));
        if (req.getEnabled() != null) p.setEnabled(req.getEnabled());
        if (req.getSortOrder() != null) p.setSortOrder(req.getSortOrder());
        providerConfigMapper.updateById(p);
        return toProviderVo(p, null);
    }

    public void deleteProvider(Long id) {
        ProviderConfig p = providerConfigMapper.selectById(id);
        if (p == null) return;
        modelConfigMapper.delete(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getProviderId, p.getProviderId()));
        providerConfigMapper.deleteById(id);
    }

    // ---------- Model CRUD ----------

    public ModelVo createModel(ModelRequest req) {
        ProviderConfig provider = providerConfigMapper.selectOne(new LambdaQueryWrapper<ProviderConfig>()
                .eq(ProviderConfig::getProviderId, req.getProviderId()));
        if (provider == null) throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "provider 不存在");
        if (modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getProviderId, req.getProviderId())
                .eq(ModelConfig::getModelId, req.getModelId())) != null) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "modelId 已存在");
        }
        ModelConfig m = new ModelConfig();
        m.setProviderId(req.getProviderId());
        m.setModelId(req.getModelId());
        m.setName(req.getName());
        m.setEnabled(req.getEnabled() != null ? req.getEnabled() : 1);
        m.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        m.setContextLimit(req.getContextLimit());
        modelConfigMapper.insert(m);
        return toModelVo(m);
    }

    public void deleteModel(Long id) {
        modelConfigMapper.deleteById(id);
    }

    // ---------- mapping ----------

    private ProviderVo toProviderVo(ProviderConfig p, List<ModelVo> models) {
        return ProviderVo.builder()
                .id(p.getId())
                .providerId(p.getProviderId())
                .name(p.getName())
                .baseUrl(p.getBaseUrl())
                .npm(p.getNpm())
                .apiKeyMasked(crypto.mask(p.getApiKey()))
                .envVarName(p.getEnvVarName())
                .enabled(p.getEnabled())
                .sortOrder(p.getSortOrder())
                .models(models)
                .build();
    }

    private ModelVo toModelVo(ModelConfig m) {
        return ModelVo.builder()
                .id(m.getId())
                .providerId(m.getProviderId())
                .modelId(m.getModelId())
                .name(m.getName())
                .enabled(m.getEnabled())
                .sortOrder(m.getSortOrder())
                .contextLimit(m.getContextLimit())
                .build();
    }

    private String defaultEnvVar(String providerId) {
        String upper = providerId.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        return upper + "_API_KEY";
    }
}