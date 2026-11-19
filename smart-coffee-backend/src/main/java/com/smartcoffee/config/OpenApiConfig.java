package com.smartcoffee.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc baseline. The bearer scheme documents the real mechanism only:
 * management and profile endpoints authenticate with the short-lived access
 * token sent as {@code Authorization: Bearer <jwt>} (JwtAuthenticationFilter).
 * The refresh token lives exclusively in an HttpOnly cookie on the
 * {@code /api/v1/auth/**} endpoints and is deliberately NOT part of the
 * Swagger "try it out" scheme, so the docs can never encourage passing it
 * anywhere it does not belong. No global security requirement is declared —
 * public catalog endpoints stay unauthenticated and per-operation docs state
 * the exact permission each guarded endpoint enforces.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SMART Coffee & Bakery API")
                        .version("v1")
                        .description("Modular-monolith REST API. All responses use the ApiResponse/PaginatedResponse "
                                + "envelope; errors carry a stable machine code (e.g. VERSION_CONFLICT, "
                                + "STORE_ACCESS_DENIED) and a requestId. Administrative endpoints live under "
                                + "/api/v1/management/** and enforce an exact permission code server-side."))
                .components(new Components().addSecuritySchemes("accessToken",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Short-lived access token (Authorization header)")));
    }
}
