package edu.bu.archive.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnProperty(
        name = "app.security.enabled",
        havingValue = "false"
)
/*
 * Only in a servlet application. Every bean here exists to serve HTTP
 * requests, and HttpSecurity does not exist outside a servlet web
 * context - so without this the whole class fails to initialise when the
 * API jar is run with spring.main.web-application-type=none.
 *
 * That is not hypothetical: it is how the ARCHIVE_ADMIN bootstrap runs,
 * deliberately, so no port is open while it holds the credentials to
 * create an administrator. The first real attempt died at
 * "APPLICATION FAILED TO START ... required a bean of type HttpSecurity"
 * before the runner was ever reached.
 *
 * This narrows nothing for the deployed API: a servlet application still
 * gets exactly the same configuration. It only stops servlet security
 * being demanded where there are no servlets.
 */
@ConditionalOnWebApplication(type = Type.SERVLET)
public class LocalSecurityConfiguration {

    @Bean
    SecurityFilterChain localSecurityFilterChain(
            HttpSecurity http
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().permitAll()
                );

        return http.build();
    }
}
