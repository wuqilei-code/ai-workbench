package com.aiworkbench.config;

import com.aiworkbench.common.TtlCache;
import com.aiworkbench.dto.admin.UsageStatsVo;
import com.aiworkbench.dto.my.MyStatsVo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 仪表盘聚合短缓存:仪表盘每次请求会重算大量 SQL 聚合,高频率刷新时重复扫库。
 * 用无依赖的 TTL 缓存(默认 30s)挡住重复计算,时效损失可忽略。
 */
@Configuration
public class StatsCacheConfig {

    @Bean
    public TtlCache<String, MyStatsVo> myStatsCache() {
        return new TtlCache<>(30_000L);
    }

    @Bean
    public TtlCache<String, UsageStatsVo> adminStatsCache() {
        return new TtlCache<>(30_000L);
    }
}
