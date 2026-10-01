package edu.bu.archive.adapter.in.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import edu.bu.archive.application.authorization.RecordAuthorizationService;

@Configuration
public class AuthorizationWebConfiguration implements WebMvcConfigurer {

    private final org.springframework.beans.factory.ObjectProvider<RecordAuthorizationService> authorization;

    /*
     * ObjectProvider only so partial web-slice test contexts (which do not
     * load AuthorizationConfiguration) still start; the full application
     * always defines RecordAuthorizationService.
     */
    public AuthorizationWebConfiguration(
            org.springframework.beans.factory.ObjectProvider<RecordAuthorizationService> authorization
    ) {
        this.authorization = authorization;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RecordAuthorizationService service = authorization.getIfAvailable();
        if (service != null) {
            registry.addInterceptor(new RecordAuthorizationInterceptor(service)).addPathPatterns("/api/**");
        }
    }
}
