package com.aiworkbench.config;

import com.aiworkbench.common.ratelimit.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 限流拦截器注册。
 * <p>
 * CORS 统一由 {@link SecurityConfig} 的 CorsConfigurationSource 处理(按 app.cors.allowed-origins 白名单;
 * 未配置则仅同源)。这里不再注册 MVC CORS,避免与安全层不一致。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/health", "/api/health/**",
                        // SSE 长连接:不限流(刷新/切会话会重建连接,避免被 429 打断)
                        "/api/chat/sessions/*/events");
    }
}
