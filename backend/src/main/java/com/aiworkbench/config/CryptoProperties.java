package com.aiworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AES 加密配置(apiKey 落库加密)。
 * 密钥优先从环境变量 APP_AES_KEY 注入(Base64 编码的 32 字节 = AES-256)。
 */
@Data
@ConfigurationProperties(prefix = "app.crypto")
public class CryptoProperties {
    /** AES-256 密钥,Base64 编码的 32 字节;为空时使用内置 dev key(仅开发,生产必须注入) */
    private String aesKey;
}
