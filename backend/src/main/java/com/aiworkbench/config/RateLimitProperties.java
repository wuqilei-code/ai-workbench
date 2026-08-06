package com.aiworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流配置(令牌桶,内存版,按用户/IP 维度)。
 */
@Data
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {
    /** 桶容量(瞬时最大请求数) */
    private int capacity = 60;
    /** 每个 refill 周期补充的令牌数 */
    private int refillTokens = 60;
    /** refill 周期(秒) */
    private int refillPeriodSeconds = 60;
}
