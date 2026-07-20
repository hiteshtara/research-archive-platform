package edu.bu.archive.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebCorsConfiguration implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public WebCorsConfiguration(
            @Value(
                    "${app.cors.allowed-origins:"
                            + "http://localhost:5173,"
                            + "https://main.d33qc0afy3ltcj.amplifyapp.com"
            )
            List<String> allowedOrigins
    ) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(
            CorsRegistry registry
    ) {
        registry
                .addMapping("/api/**")
                .allowedOrigins(
                        allowedOrigins.toArray(String[]::new)
                )
                .allowedMethods(
                        "GET",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "OPTIONS"
                )
                .allowedHeaders(
                        "Authorization",
                        "Content-Type",
                        "Accept"
                )
                .exposedHeaders(
                        "Location"
                )
                .allowCredentials(true)
                .maxAge(3600);
    }
}
