package com.aiworkbench.common;

import com.aiworkbench.entity.ProviderConfig;
import com.aiworkbench.mapper.ProviderConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把历史明文 apiKey 迁移为 AES 密文(带 enc: 前缀)。
 * 对 apiKey 非空且无 enc: 前缀的记录加密回写,兼容冒烟测试等存量数据,无需手动清库。
 */
@Slf4j
@Order(20)
@Component
@RequiredArgsConstructor
public class AesKeyMigrationRunner implements ApplicationRunner {

    private final ProviderConfigMapper providerConfigMapper;
    private final CryptoService cryptoService;

    @Override
    public void run(ApplicationArguments args) {
        List<ProviderConfig> all = providerConfigMapper.selectList(null);
        int migrated = 0;
        for (ProviderConfig p : all) {
            String k = p.getApiKey();
            if (k != null && !k.isEmpty() && !k.startsWith("enc:")) {
                p.setApiKey(cryptoService.encrypt(k));
                providerConfigMapper.updateById(p);
                migrated++;
            }
        }
        if (migrated > 0) {
            log.info("AES key migration: {} provider apiKey(s) encrypted", migrated);
        }
    }
}
