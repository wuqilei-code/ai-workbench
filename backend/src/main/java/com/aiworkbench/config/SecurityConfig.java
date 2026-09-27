package com.aiworkbench.config;

import com.aiworkbench.common.R;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.security.JwtAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 允许跨域的来源白名单(逗号分隔);留空表示仅同源(默认,最安全)。 */
    @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                           com.aiworkbench.security.InternalApiFilter internalApiFilter,
                                           ObjectMapper objectMapper) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/health",
                    "/api/health/**",
                    "/api/auth/login",
                    "/api/auth/register",
                    "/api/internal/**",
                    "/error",
                    "/ws/**",
                    // OpenAPI 文档(springdoc);OPENAPI_ENABLED=false 时端点不存在,放行无影响
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                ).permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(eh -> eh
                // 未认证(无/无效 token):返回 401 + 统一 R JSON。
                // 默认 Http403ForbiddenEntryPoint 会返回 403,语义错误。
                .authenticationEntryPoint((request, response, ex) -> {
                    log.warn("401 unauthorized: {} {} (remote={})",
                            request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
                    writeJson(response, objectMapper, HttpStatus.UNAUTHORIZED, R.fail(ResultCode.UNAUTHORIZED));
                })
                // 已认证但越权(如非 admin 访问 /api/admin/**):返回 403 + 统一 R JSON。
                .accessDeniedHandler((request, response, ex) -> {
                    var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    Object principal = auth == null ? null : auth.getPrincipal();
                    log.warn("403 forbidden: {} {} principal={} roles={}",
                            request.getMethod(), request.getRequestURI(), principal,
                            auth == null ? null : auth.getAuthorities());
                    writeJson(response, objectMapper, HttpStatus.FORBIDDEN, R.fail(ResultCode.FORBIDDEN));
                })
            )
            .addFilterBefore(internalApiFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeJson(HttpServletResponse response, ObjectMapper objectMapper,
                           HttpStatus status, R<?> body) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http,
                                                       UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) throws Exception {
        AuthenticationManagerBuilder builder = http.getSharedObject(AuthenticationManagerBuilder.class);
        builder.userDetailsService(userDetailsService).passwordEncoder(passwordEncoder);
        return builder.build();
    }

    /**
     * CORS 来源:仅在配置了 app.cors.allowed-origins 时启用跨域白名单;
     * 未配置时返回 null → 不做 CORS 处理(浏览器按同源处理,跨域由浏览器阻拦),
     * 避免同源 POST(浏览器会带 Origin 头)被 CORS 过滤器误判为非法跨域而 403。
     */
    private CorsConfigurationSource corsConfigurationSource() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return request -> null;
        }
        CorsConfiguration cfg = new CorsConfiguration();
        for (String o : allowedOrigins.split(",")) {
            String v = o.trim();
            if (!v.isEmpty()) cfg.addAllowedOriginPattern(v);
        }
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        // token 走 Authorization header,无需携带凭据;关闭 allowCredentials 避免风险。
        cfg.setAllowCredentials(false);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
