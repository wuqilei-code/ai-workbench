package com.aiworkbench.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 配置调用 opencode 的 WebClient.Builder(Phase 2:per-user 多实例)。
 * <p>
 * 不再绑定单一 baseUrl:每用户 opencode 监听 127.0.0.1:&lt;port&gt;,由
 * {@link com.aiworkbench.service.opencode.process.OpencodeProcessManager} 用 {@code .clone().baseUrl(...)}
 * 派生实例 client。Basic auth 头 + codecs 在此预置,clone 时保留。
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder opencodeWebClientBuilder(OpencodeProperties props) {
        String credentials = props.getUsername() + ":" + props.getPassword();
        String basic = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return WebClient.builder()
                .defaultHeader("Authorization", "Basic " + basic)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));
    }
}
