package br.com.sergio.sapiensia.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title and version of the generated API description (served outside production only). */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI sapiensiaOpenApi() {
        return new OpenAPI().info(new Info().title("SapiensIA API").version("1"));
    }
}
