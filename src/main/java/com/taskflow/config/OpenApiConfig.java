package com.taskflow.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI taskflowOpenApi() {
        return new OpenAPI().info(new Info()
                .title("TaskFlow Pro API")
                .version("0.0.1")
                .description("""
                        Log in with POST /api/auth/login. Your browser keeps the session cookie, so every \
                        other call on this page is then made as that user. Writes (POST, PUT, DELETE) send \
                        the CSRF token automatically."""));
    }
}
