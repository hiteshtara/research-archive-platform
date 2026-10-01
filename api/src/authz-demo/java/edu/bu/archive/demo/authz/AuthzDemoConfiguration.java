package edu.bu.archive.demo.authz;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;

import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.IoResolver;
import edu.bu.archive.application.authorization.IoSqlStrategy;
import edu.bu.archive.application.authorization.RecordModule;

/**
 * SYNTHETIC IDENTITY DEMO - BU FEDERATION NOT CONNECTED.
 *
 * <p>Exists only in builds made with -Pauthz-demo, and activates only under
 * the "authz-demo" Spring profile. It refuses to start unless security is in
 * local permit-all mode, record authorization is enforced, and the database
 * is a loopback, explicitly disposable demo database. It replaces ONLY the
 * authentication step (who is signed in) with a persona selector; identity
 * links, grants, scoping and enforcement are the real production code paths.
 */
@Configuration
@Profile("authz-demo")
public class AuthzDemoConfiguration implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {

    @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:http://localhost:5199}")
    private String[] demoAllowedOrigins;

    /** CORS for the demo-only persona list (the main mapping covers /api/** only). */
    @Override
    public void addCorsMappings(org.springframework.web.servlet.config.annotation.CorsRegistry registry) {
        registry.addMapping("/demo/**").allowedOrigins(demoAllowedOrigins).allowedMethods("GET");
    }


    public static final String DEMO_ISSUER = "https://demo.invalid/synthetic-cognito";
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1");

    public AuthzDemoConfiguration(
            @Value("${app.security.enabled:true}") boolean securityEnabled,
            @Value("${app.authorization.enforcement-enabled:false}") boolean enforcementEnabled,
            @Value("${app.authz-demo.disposable-database:false}") boolean disposableDatabase,
            @Value("${spring.datasource.url:}") String datasourceUrl
    ) {
        if (securityEnabled) {
            throw new IllegalStateException("authz-demo requires app.security.enabled=false (local only)");
        }
        if (!enforcementEnabled) {
            throw new IllegalStateException("authz-demo requires app.authorization.enforcement-enabled=true");
        }
        if (!disposableDatabase) {
            throw new IllegalStateException("authz-demo requires app.authz-demo.disposable-database=true");
        }
        String host = hostOf(datasourceUrl);
        if (!LOOPBACK.contains(host)) {
            throw new IllegalStateException("authz-demo refuses a non-loopback database host: " + host);
        }
    }

    static String hostOf(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return "";
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        return uri.getHost() == null ? "" : uri.getHost();
    }

    @Bean
    @Primary
    CurrentIdentityProvider demoCurrentIdentityProvider() {
        return DemoPersonaFilter::currentIdentity;
    }

    @Bean
    FilterRegistrationBean<DemoPersonaFilter> demoPersonaFilter(JdbcClient jdbc) {
        var registration = new FilterRegistrationBean<>(new DemoPersonaFilter(jdbc));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return registration;
    }

    /** Synthetic IO values (authz_demo.award_io). The real IO field remains unresolved (D-A). */
    @Bean
    @Primary
    IoResolver demoIoResolver(JdbcClient jdbc) {
        return (module, versionKey) -> module != RecordModule.AWARD
                ? Set.of()
                : Set.copyOf(jdbc.sql("SELECT io_value FROM authz_demo.award_io WHERE award_id = CAST(:id AS BIGINT)")
                        .param("id", versionKey).query(String.class).list());
    }

    @Bean
    @Primary
    IoSqlStrategy demoIoSqlStrategy() {
        return (module, rowAlias) -> module == RecordModule.AWARD
                ? Optional.of("EXISTS (SELECT 1 FROM authz_demo.award_io az_io WHERE az_io.award_id = "
                        + rowAlias + ".award_id AND az_io.io_value IN (:az_ios))")
                : Optional.empty();
    }
}
