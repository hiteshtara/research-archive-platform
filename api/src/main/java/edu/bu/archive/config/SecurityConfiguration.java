package edu.bu.archive.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnProperty(
        name = "app.security.enabled",
        havingValue = "true",
        matchIfMissing = true
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
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Converter<Jwt, ? extends AbstractAuthenticationToken>
                    jwtAuthenticationConverter
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/error"
                        ).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(
                                        jwtAuthenticationConverter
                                )
                        )
                );

        return http.build();
    }

    @Bean
    NimbusJwtDecoder jwtDecoder(
            @Value("${app.security.cognito.issuer-uri}")
            String issuerUri,
            @Value("${app.security.cognito.client-id}")
            String clientId
    ) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withIssuerLocation(issuerUri).build();

        var issuerValidator =
                JwtValidators.createDefaultWithIssuer(issuerUri);

        var tokenUseValidator = new JwtClaimValidator<String>(
                "token_use",
                "access"::equals
        );

        var clientIdValidator = new JwtClaimValidator<String>(
                "client_id",
                clientId::equals
        );

        decoder.setJwtValidator(
                new org.springframework.security.oauth2.core
                        .DelegatingOAuth2TokenValidator<>(
                                issuerValidator,
                                tokenUseValidator,
                                clientIdValidator
                        )
        );

        return decoder;
    }

    @Bean
    Converter<Jwt, ? extends AbstractAuthenticationToken>
    jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes =
                new JwtGrantedAuthoritiesConverter();

        scopes.setAuthorityPrefix("SCOPE_");
        scopes.setAuthoritiesClaimName("scope");

        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities =
                    new java.util.ArrayList<>(
                            scopes.convert(jwt)
                    );

            List<String> groups =
                    jwt.getClaimAsStringList("cognito:groups");

            if (groups != null) {
                groups.stream()
                        .map(group -> "ROLE_" + group)
                        .map(org.springframework.security.core.authority
                                .SimpleGrantedAuthority::new)
                        .forEach(authorities::add);
            }

            return authorities;
        });

        return converter;
    }
}
