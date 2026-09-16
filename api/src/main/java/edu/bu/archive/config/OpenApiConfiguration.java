package edu.bu.archive.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI metadata.
 *
 * <p>Two things this fixes for the generated docs:
 *
 * <ul>
 *   <li>Declares a bearer (Cognito JWT) security scheme so Swagger UI shows the
 *       "Authorize" button. Without it there is no way to attach a token in the
 *       UI, and every {@code /api/**} "Try it out" call comes back 401.
 *   <li>Sets a real title/version/description instead of springdoc's generic
 *       "OpenAPI definition / v0".
 * </ul>
 *
 * <p>This is documentation only. It does not change what is enforced - that
 * lives in {@link SecurityConfiguration}, where {@code /api/**} stays
 * authenticated. It just lets a reader of the docs supply a token.
 */
@Configuration
public class OpenApiConfiguration {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI researchArchiveOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("BU Research Archive API")
                        .version("v1")
                        .description(
                                "Read-only REST access to BU's archived Kuali Coeus research "
                                        + "administration records - awards, proposals, subawards, "
                                        + "negotiations and IRB. Endpoints under /api/** require a "
                                        + "Cognito access token."))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description(
                                        "Cognito access token. From the signed-in app, copy the "
                                                + "value of the localStorage key "
                                                + "CognitoIdentityServiceProvider.<clientId>.<user>.accessToken "
                                                + "and paste it here (without the word 'Bearer').")));
    }
}
