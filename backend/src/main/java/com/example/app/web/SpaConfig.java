package com.example.app.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the React build (copied to classpath:/static by the Dockerfile) and sends the app's own routes, such as
 * /pedidos/42, to index.html so that React Router can handle them. API routes and missing files stay 404.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    static class SpaFallbackResolver extends PathResourceResolver {

        private static final Resource INDEX = new ClassPathResource("static/index.html");

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            boolean apiRoute = resourcePath.equals("api") || resourcePath.startsWith("api/");
            boolean looksLikeFile =
                    resourcePath.substring(resourcePath.lastIndexOf('/') + 1).contains(".");
            if (apiRoute || looksLikeFile || !INDEX.exists()) {
                return null;
            }
            return INDEX;
        }
    }
}
