package com.aiworkbench.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档元信息与鉴权方案。
 * <p>
 * 访问:/v3/api-docs(JSON)、/swagger-ui/index.html(UI)。默认开启,可在 SecurityConfig 中按
 * {@code OPENAPI_ENABLED} 开关放行;生产如需收敛,设为 false 即回到"需登录"。
 * 除登录/注册等白名单外,接口需 {@code Authorization: Bearer <JWT>}。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aiWorkbenchOpenApi() {
        final String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("AI Workbench API")
                        .version("0.1.0")
                        .description("多用户 AI 工作台网关 API。多数接口需 Authorization: Bearer <JWT>;"
                                + "内部接口 /api/internal/** 使用 X-Internal-Token。"))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(scheme));
    }
}
