package edu.bu.archive.config;

import edu.bu.archive.adapter.in.web.RequestParameterValidationInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/*
 * Registers the request-parameter character check across the whole API
 * surface, not only the five search endpoints that exposed it (QA
 * TC-017): the same NUL byte in any other parameter - a sponsor filter,
 * a document number - would reach PostgreSQL the same way.
 *
 * Separate from WebCorsConfiguration (the other WebMvcConfigurer) so
 * each one carries a single concern.
 */
@Configuration
public class WebRequestValidationConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry
                .addInterceptor(new RequestParameterValidationInterceptor())
                .addPathPatterns("/api/**");
    }
}
