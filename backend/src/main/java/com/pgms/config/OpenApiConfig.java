package com.pgms.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI pgmsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PG Management & Operations Platform API")
                        .version("v1")
                        .description("""
                                Transactional API for multi-property PG/co-living operations.
                                Authenticate with POST /api/v1/auth/login and use the access token as a Bearer token.
                                All errors share one envelope: {timestamp, status, error, code, message, path, correlationId}.
                                Payment and refund creation require an Idempotency-Key header."""))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
