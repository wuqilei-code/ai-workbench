package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.CryptoService;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.common.ShellExec;
import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.dto.admin.ModelRequest;
import com.aiworkbench.dto.admin.ModelVo;
import com.aiworkbench.dto.admin.ProviderRequest;
import com.aiworkbench.dto.admin.ProviderVo;
import com.aiworkbench.entity.ModelConfig;
import com.aiworkbench.entity.ProviderConfig;
import com.aiworkbench.mapper.ModelConfigMapper;
import com.aiworkbench.mapper.ProviderConfigMapper;
import com.aiworkbench.service.opencode.process.OpencodeProcessManager;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provider/Model 配置管理 + applyConfig(渲染 opencode.json/env + 重启 opencode)。
 * <p>
 * 关联键说明:model_config.provider_id 存的是**逻辑 provider id 字符串**(如 "openai"),
 * 与 provider_config.provider_id 对应,而非 provider_config.id。
 * <p>
 * Phase 7:apiKey 经 {@link CryptoService} AES-GCM 加密落库(密文带 enc: 前缀),
 * apply 时解密写入 opencode.env,列表返回脱敏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderConfigService {

    private final ProviderConfigMapper providerConfigMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final OpencodeProperties props;
    private final ObjectMapper objectMapper;
    private final CryptoService crypto;
    private final OpencodeProcessManager processManager;

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
        modelConfigMapper.insert(m);
        return toModelVo(m);
    }

    public void deleteModel(Long id) {
        modelConfigMapper.deleteById(id);
    }

    // ---------- Apply:渲染模板 + 扇出到活跃用户进程 ----------

    /**
     * 从 DB 渲染 opencode.json + opencode.env(解密 key)写到模板真相源
     * ({@code /data/opencode/template/},root 0700,env 0600),然后对每个活跃用户的 opencode 进程
     * 执行 restart(其 start 会从模板 seed 新配置)。无活跃进程的用户下次首启时自动拿新模板。
     * <p>
     * Phase 2 前:写 configDir + supervisorctl restart 单 root opencode;
     * Phase 2 起:opencode 由 per-user ProcessManager 管理,无常驻实例,supervisorctl 路径失效,改为扇出。
     */
    public void applyConfig() {
        List<ProviderConfig> providers = providerConfigMapper.selectList(
                new LambdaQueryWrapper<ProviderConfig>()
                        .eq(ProviderConfig::getEnabled, 1)
                        .orderByAsc(ProviderConfig::getSortOrder));

        Map<String, Object> providerMap = new LinkedHashMap<>();
        StringBuilder envBuilder = new StringBuilder();
        for (ProviderConfig p : providers) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", p.getProviderId());
            entry.put("name", p.getName());
            if (p.getNpm() != null && !p.getNpm().isBlank()) {
                entry.put("npm", p.getNpm());
            }
            if (p.getBaseUrl() != null && !p.getBaseUrl().isBlank()) {
                entry.put("url", p.getBaseUrl());
            }
            if (p.getEnvVarName() != null && !p.getEnvVarName().isBlank()) {
                entry.put("env", List.of(p.getEnvVarName()));
                // 解密 apiKey 后写入 opencode.env(明文,仅模板 0700 + env 0600 保护)
                String plainKey = crypto.decrypt(p.getApiKey());
                if (plainKey != null && !plainKey.isBlank()) {
                    envBuilder.append(p.getEnvVarName()).append('=')
                            .append(plainKey).append('\n');
                }
            } else {
                entry.put("env", List.of());
            }
            // models
            List<ModelConfig> models = modelConfigMapper.selectList(
                    new LambdaQueryWrapper<ModelConfig>()
                            .eq(ModelConfig::getProviderId, p.getProviderId())
                            .eq(ModelConfig::getEnabled, 1)
                            .orderByAsc(ModelConfig::getSortOrder));
            Map<String, Object> modelsMap = new LinkedHashMap<>();
            for (ModelConfig m : models) {
                Map<String, Object> mo = new LinkedHashMap<>();
                mo.put("id", m.getModelId());
                mo.put("name", m.getName());
                modelsMap.put(m.getModelId(), mo);
            }
            entry.put("models", modelsMap);
            providerMap.put(p.getProviderId(), entry);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", "https://opencode.ai/config.json");
        root.put("autoCompact", 40000);
        root.put("permission", buildPermissionConfig());
        root.put("provider", providerMap);

        // 写模板真相源
        try {
            Path dir = Paths.get(props.getTemplateDir());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("opencode.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            Path envFile = dir.resolve("opencode.env");
            Files.writeString(envFile, envBuilder.toString());
            ShellExec.run("chmod", "0600", envFile.toString());
        } catch (IOException e) {
            log.error("write opencode template failed", e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "写入 opencode 配置失败");
        }

        // 扇出:对每个活跃用户进程 restart(start 会从最新模板 seed)。
        // 跳过正在生成的用户(restart 会打断活跃对话);被跳过的用户在其进程被空闲回收、下次首启时自动拿新模板。
        Set<Long> active = new HashSet<>(processManager.activeUserIds());
        List<Long> toRestart = new ArrayList<>();
        for (Long userId : active) {
            if (processManager.isStreaming(userId)) {
                log.info("applyConfig: skip user {} (streaming), will pick up new config on next process start", userId);
            } else {
                toRestart.add(userId);
            }
        }
        if (toRestart.isEmpty()) {
            return;
        }
        log.info("applyConfig: template rendered, restarting {} of {} active user process(es)", toRestart.size(), active.size());
        // 并发限流(5):restart 为阻塞操作(健康轮询),串行在活跃用户多时会让 apply 超时。
        int threads = Math.min(5, toRestart.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "apply-config-fanout");
            t.setDaemon(true);
            return t;
        });
        try {
            List<java.util.concurrent.Callable<Void>> tasks = toRestart.stream()
                    .map(userId -> (java.util.concurrent.Callable<Void>) () -> {
                        try {
                            processManager.restart(userId);
                        } catch (Exception e) {
                            log.warn("applyConfig fan-out restart failed for user {}: {}", userId, e.toString());
                        }
                        return null;
                    }).toList();
            pool.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("applyConfig fan-out interrupted");
        } finally {
            pool.shutdown();
        }
    }

    /**
     * 构建 opencode permission 配置:高危 bash 命令需用户确认(ask),系统/pip 类直接拒绝(deny),
     * 其余默认放行(allow)。opencode bash 规则匹配"解析后的命令前缀",通配符 * 匹配任意字符,
     * <b>最后匹配的规则优先</b>,故通配 "*" 置首、具体规则置后。
     * <p>
     * 注意:此配置需与 {@code docker/opencode/opencode.json.tpl} 的 permission 段保持一致
     * (tpl 用于首启播种,本方法用于 applyConfig 渲染 live 配置)。
     */
    private Map<String, Object> buildPermissionConfig() {
        // bash 命令规则:顺序敏感(后者覆盖前者),通配 "*" 在前,高危命令在后
        Map<String, Object> bash = new LinkedHashMap<>();
        bash.put("*", "allow");                 // 默认放行(python/ls/cat/grep/mkdir 等正常命令)
        bash.put("rm *", "ask");                // 删除文件 → 确认
        bash.put("rmdir *", "ask");
        bash.put("mv *", "ask");                // 移动/重命名 → 确认
        bash.put("chmod *", "ask");             // 改权限 → 确认
        bash.put("chown *", "ask");
        bash.put("curl *", "ask");              // 网络下载 → 确认
        bash.put("wget *", "ask");
        bash.put("git push *", "ask");          // 推送 → 确认
        bash.put("git reset --hard*", "ask");   // 破坏性 git → 确认
        bash.put("git checkout *", "ask");
        bash.put("git clean *", "ask");
        bash.put("sudo *", "deny");             // 系统提权 → 拒绝(per-user uid 本也无权限)
        bash.put("su *", "deny");
        bash.put("apt *", "deny");              // 包管理 → 拒绝
        bash.put("apt-get *", "deny");
        bash.put("pip *", "deny");              // 恢复 pip → 拒绝(配合镜像层删 pip 的防御纵深)
        bash.put("pip3 *", "deny");
        bash.put("python* -m pip*", "deny");
        bash.put("python* -m ensurepip*", "deny");

        Map<String, Object> permission = new LinkedHashMap<>();
        permission.put("bash", bash);
        permission.put("external_directory", "ask"); // 访问工作区外路径 → 确认
        return permission;
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
                .build();
    }

    private String defaultEnvVar(String providerId) {
        String upper = providerId.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        return upper + "_API_KEY";
    }
}
