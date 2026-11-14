package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.me.CapabilitiesResponse;
import edu.bu.archive.application.me.CapabilitiesService;
import edu.bu.archive.config.SecurityConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * The web-layer half of the contract's test table
 * (docs/security/CAPABILITIES_ENDPOINT_CONTRACT.md section 7): the 401
 * cases, the response shape, no-store, and - the one that matters most -
 * that the endpoint is NOT 404 with enforcement off, which is the property
 * the administrator API tree cannot satisfy and the reason this endpoint
 * exists at all.
 *
 * app.authorization.enforcement-enabled is deliberately absent from the
 * properties below, so it is false: these tests run in the state the
 * endpoint has to work in.
 */
@WebMvcTest(MeCapabilitiesController.class)
@Import({SecurityConfiguration.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "app.security.enabled=true",
        "app.security.cognito.issuer-uri=https://issuer.example",
        "app.security.cognito.client-id=test-client"
})
class MeCapabilitiesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CapabilitiesService capabilities;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // --- Case: no token, and an invalid/expired one -> 401 ---

    @Test
    void withoutATokenTheEndpointIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/me/capabilities"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anInvalidOrExpiredTokenIsUnauthorized() throws Exception {
        // BadJwtException, not a bare JwtException: the resource server maps
        // a BAD token to InvalidBearerTokenException -> 401, while a plain
        // JwtException means "the decoder itself failed" and becomes a
        // 500-class AuthenticationServiceException. An expired token is the
        // former, so this is the exception an expiry actually produces.
        when(jwtDecoder.decode("expired-token"))
                .thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException(
                        "Jwt expired at 2020-01-01T00:00:00Z"));

        mockMvc.perform(get("/api/v1/me/capabilities")
                        .header("Authorization", "Bearer expired-token"))
                .andExpect(status().isUnauthorized());
    }

    // --- Case: endpoint reachable with enforcement OFF (NOT 404) ---

    @Test
    void withEnforcementOffTheEndpointIsReachableAndNotFourOhFour() throws Exception {
        when(capabilities.current()).thenReturn(CapabilitiesResponse.denied());

        mockMvc.perform(get("/api/v1/me/capabilities").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void aNonAdministratorIsTwoHundredNotForbidden() throws Exception {
        // "You are not an administrator" is a normal answer. A 403 here
        // would reintroduce the exact ambiguity this endpoint removes.
        when(capabilities.current()).thenReturn(CapabilitiesResponse.denied());

        mockMvc.perform(get("/api/v1/me/capabilities").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.administrator").value(false))
                .andExpect(jsonPath("$.enforcementEnabled").value(false))
                .andExpect(jsonPath("$.administrationAvailable").value(false));
    }

    // --- Case: response body shape - exactly three boolean keys ---

    @Test
    void theResponseHasExactlyFourBooleanKeysAndNothingElse() throws Exception {
        when(capabilities.current()).thenReturn(CapabilitiesResponse.administrator(true, true, false));

        mockMvc.perform(get("/api/v1/me/capabilities").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(4)))
                .andExpect(jsonPath("$.administrator").isBoolean())
                .andExpect(jsonPath("$.enforcementEnabled").isBoolean())
                .andExpect(jsonPath("$.administrationAvailable").isBoolean())
                .andExpect(jsonPath("$.administrationWritesAvailable").isBoolean())
                // Nothing that could identify the caller or leak a grant.
                .andExpect(jsonPath("$.institutionalIdentifier").doesNotExist())
                .andExpect(jsonPath("$.subject").doesNotExist())
                .andExpect(jsonPath("$.grants").doesNotExist())
                .andExpect(jsonPath("$.auditingEnabled").doesNotExist());
    }

    // --- Caching ---

    @Test
    void theResponseIsNoStore() throws Exception {
        when(capabilities.current()).thenReturn(CapabilitiesResponse.administrator(false, true, false));

        mockMvc.perform(get("/api/v1/me/capabilities").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    // --- Administrator, enforcement on and off, end to end ---

    @Test
    void anAdministratorWithEnforcementOffStillGetsAUsableAdministrationScreen() throws Exception {
        when(capabilities.current()).thenReturn(CapabilitiesResponse.administrator(false, true, false));

        mockMvc.perform(get("/api/v1/me/capabilities").with(jwt()))
                .andExpect(jsonPath("$.administrator").value(true))
                .andExpect(jsonPath("$.enforcementEnabled").value(false))
                // The decoupling, asserted: administration is available even
                // though record-level enforcement is off. This assertion was
                // the inverse before, which encoded the old single-flag
                // behaviour rather than a requirement.
                .andExpect(jsonPath("$.administrationAvailable").value(true))
                // Read-only: the surface answers, writes do not.
                .andExpect(jsonPath("$.administrationWritesAvailable").value(false));
    }

    @Test
    void administrationStaysUnavailableWhenOnlyEnforcementIsOn() throws Exception {
        // Enforcement alone no longer opens the administrative surface.
        when(capabilities.current()).thenReturn(CapabilitiesResponse.administrator(true, false, false));

        mockMvc.perform(get("/api/v1/me/capabilities").with(jwt()))
                .andExpect(jsonPath("$.administrator").value(true))
                .andExpect(jsonPath("$.enforcementEnabled").value(true))
                .andExpect(jsonPath("$.administrationAvailable").value(false))
                .andExpect(jsonPath("$.administrationWritesAvailable").value(false));
    }

    @Test
    void anAdministratorWithWritesEnabledReportsWritesAvailable() throws Exception {
        // The Phase 3 shape, reported correctly. Not enabled anywhere.
        when(capabilities.current()).thenReturn(CapabilitiesResponse.administrator(false, true, true));

        mockMvc.perform(get("/api/v1/me/capabilities").with(jwt()))
                .andExpect(jsonPath("$.administrator").value(true))
                .andExpect(jsonPath("$.enforcementEnabled").value(false))
                .andExpect(jsonPath("$.administrationAvailable").value(true))
                .andExpect(jsonPath("$.administrationWritesAvailable").value(true));
    }
}
