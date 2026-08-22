package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.dto.admin.ModelRequest;
import com.aiworkbench.dto.admin.ModelVo;
import com.aiworkbench.dto.admin.ProviderRequest;
import com.aiworkbench.dto.admin.ProviderVo;
import com.aiworkbench.service.ProviderConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminProviderController {

    private final ProviderConfigService providerConfigService;

    @GetMapping("/providers")
    public R<List<ProviderVo>> listProviders() {
        return R.ok(providerConfigService.listAll());
    }

    @PostMapping("/providers")
    @AuditLog(action = "创建Provider")
    public R<ProviderVo> createProvider(@Valid @RequestBody ProviderRequest req) {
        return R.ok(providerConfigService.createProvider(req));
    }

    @PutMapping("/providers/{id}")
    @AuditLog(action = "更新Provider", targetSpel = "#id")
    public R<ProviderVo> updateProvider(@PathVariable Long id, @Valid @RequestBody ProviderRequest req) {
        return R.ok(providerConfigService.updateProvider(id, req));
    }

    @DeleteMapping("/providers/{id}")
    @AuditLog(action = "删除Provider", targetSpel = "#id")
    public R<Void> deleteProvider(@PathVariable Long id) {
        providerConfigService.deleteProvider(id);
        return R.ok();
    }

    @PostMapping("/models")
    @AuditLog(action = "创建模型")
    public R<ModelVo> createModel(@Valid @RequestBody ModelRequest req) {
        return R.ok(providerConfigService.createModel(req));
    }

    @DeleteMapping("/models/{id}")
    @AuditLog(action = "删除模型", targetSpel = "#id")
    public R<Void> deleteModel(@PathVariable Long id) {
        providerConfigService.deleteModel(id);
        return R.ok();
    }
}
