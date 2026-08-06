package com.aiworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    private String secret;
    /** token 有效期(分钟) */
    private long ttlMinutes = 1440;
}
