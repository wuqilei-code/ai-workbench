package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.dto.admin.ProviderVo;
import com.aiworkbench.service.ProviderConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 可用模型列表(登录用户均可访问,供 ModelSelector) */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

    private final ProviderConfigService providerConfigService;

    @GetMapping
    public R<List<ProviderVo>> available() {
        return R.ok(providerConfigService.listAvailable());
    }
}
