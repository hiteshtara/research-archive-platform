package edu.bu.archive.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

// TEMPORARY diagnostic filter for the Explorer Proposals 404
// investigation - registered at the highest possible precedence so it
// logs before Spring Security's own filter chain runs, proving whether
// a given request reaches the JVM at all versus being intercepted
// upstream. Logs at INFO (no logging-level env var needed). Remove
// once the investigation concludes.
@Configuration
public class TemporaryRequestLoggingConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger("REQUEST_TRACE");

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> temporaryRequestLoggingFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain filterChain
            ) throws ServletException, IOException {
                log.info(
                        "REQUEST: method={} uri={} queryString={} remoteAddr={}",
                        request.getMethod(),
                        request.getRequestURI(),
                        request.getQueryString(),
                        request.getRemoteAddr()
                );
                filterChain.doFilter(request, response);
            }
        };

        FilterRegistrationBean<OncePerRequestFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
