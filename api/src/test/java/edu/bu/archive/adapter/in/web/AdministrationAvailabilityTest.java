package edu.bu.archive.adapter.in.web;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.IdentityLinkStatus;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminAuditRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminIdentityRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminRoleRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryIdentityLinkRepository;
import edu.bu.archive.application.authorization.fixtures.RecordingAdminAuditSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Administration availability is independent of record-level enforcement.
 *
 * <p>The thing these tests exist to prevent is a quiet re-coupling: someone
 * reading {@code isEnforcementEnabled()} in the admin gate again, or deriving
 * one flag from the other. Every case below therefore pins the record flag to
 * {@code false} and asserts the administrative behaviour anyway.
 */
class AdministrationAvailabilityTest {

    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/pool";
    private static final String ADMIN_SUBJECT = "cognito-admin";
    private static final String ORDINARY_SUBJECT = "cognito-ordinary";
    private static final InstitutionalIdentifier ADMIN = new InstitutionalIdentifier("inst-admin");
    private static final InstitutionalIdentifier ORDINARY = new InstitutionalIdentifier("inst-ordinary");

    /** The four read endpoints. */
    private static final List<String> READS = List.of(
            "/api/v1/admin/identities?q=someone",
            "/api/v1/admin/identities/inst-admin",
            "/api/v1/admin/audit",
            "/api/v1/admin/administrators");

    private InMemoryIdentityLinkRepository links;
    private InMemoryAdminRoleRepository adminRoles;
    private RecordingAdminAuditSink audit;
    private InMemoryAccessGrantRepository grants;
    private AuthorizationProperties properties;
    private ValidatedCognitoIdentity caller;

    @BeforeEach
    void setUp() {
        links = new InMemoryIdentityLinkRepository();
        links.link(cognito(ADMIN_SUBJECT), ADMIN.value(), null, "admin", IdentityLinkStatus.ACTIVE);
        links.link(cognito(ORDINARY_SUBJECT), ORDINARY.value(), null, "ordinary", IdentityLinkStatus.ACTIVE);

        adminRoles = new InMemoryAdminRoleRepository();
        adminRoles.bootstrap(ADMIN, "operator", "test");
        audit = new RecordingAdminAuditSink();
        grants = new InMemoryAccessGrantRepository();

        properties = new AuthorizationProperties();
        // Record enforcement stays OFF in every test in this class.
        properties.setEnforcementEnabled(false);
        caller = cognito(ADMIN_SUBJECT);
    }

    // ---- the flag matrix -------------------------------------------------

    @Nested
    @DisplayName("Neither flag turns record-level enforcement on")
    class RecordEnforcementUntouched {

        @Test
        void noCombinationOfAdministrationFlagsEnablesRecordEnforcement() {
            for (boolean administration : new boolean[] {false, true}) {
                for (boolean writes : new boolean[] {false, true}) {
                    AuthorizationProperties p = new AuthorizationProperties();
                    p.setEnforcementEnabled(false);
                    p.setAdministrationEnabled(administration);
                    p.setAdministrationWritesEnabled(writes);

                    assertThat(p.isEnforcementEnabled())
                            .as("administration=%s writes=%s must not enable record enforcement",
                                    administration, writes)
                            .isFalse();
                }
            }
        }

        @Test
        void writesCannotBeOnWhileTheSurfaceIsClosed() {
            AuthorizationProperties p = new AuthorizationProperties();
            p.setAdministrationEnabled(false);
            p.setAdministrationWritesEnabled(true);

            // A writes-only misconfiguration must not expose anything.
            assertThat(p.isAdministrationWritesEnabled()).isFalse();
        }

        @Test
        void recordEnforcementDoesNotOpenTheAdministrativeSurface() throws Exception {
            // The old behaviour, now explicitly refuted: enforcement alone is
            // no longer sufficient to reach the administrative API.
            properties.setEnforcementEnabled(true);
            properties.setAdministrationEnabled(false);

            for (String read : READS) {
                mvc().perform(get(read)).andExpect(status().isNotFound());
            }
        }
    }

    // ---- administration disabled ----------------------------------------

    @Nested
    @DisplayName("administration-enabled=false: all 12 endpoints are 404")
    class SurfaceClosed {

        @BeforeEach
        void closed() {
            properties.setAdministrationEnabled(false);
            properties.setAdministrationWritesEnabled(false);
        }

        @Test
        void allFourReadsAre404() throws Exception {
            for (String read : READS) {
                mvc().perform(get(read)).andExpect(status().isNotFound());
            }
        }

        @Test
        void allEightWritesAre404() throws Exception {
            expectAllWrites(status().isNotFound());
        }

        @Test
        void nothingIsAudited() throws Exception {
            expectAllWrites(status().isNotFound());
            assertThat(audit.events()).isEmpty();
        }
    }

    // ---- read-only phase -------------------------------------------------

    @Nested
    @DisplayName("Read-only: reads answer, writes are 409, enforcement stays off")
    class ReadOnly {

        @BeforeEach
        void readOnly() {
            properties.setAdministrationEnabled(true);
            properties.setAdministrationWritesEnabled(false);
        }

        @Test
        void theFourReadsAnswerForAnAdministrator() throws Exception {
            for (String read : READS) {
                mvc().perform(get(read)).andExpect(status().isOk());
            }
        }

        @Test
        void theEightWritesAreRefusedWith409AndACode() throws Exception {
            expectAllWrites(status().isConflict());
        }

        @Test
        void aRefusedWriteCarriesTheDisabledCode() throws Exception {
            mvc().perform(post("/api/v1/admin/identities/inst-ordinary/grants")
                            .contentType("application/json")
                            .content("{\"type\":\"IO\",\"ioValue\":\"9500012345\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ADMINISTRATION_WRITES_DISABLED"));
        }

        @Test
        void aRefusedWriteAuditsNothingAndGrantsNothing() throws Exception {
            expectAllWrites(status().isConflict());

            assertThat(audit.events())
                    .as("a refused write must leave no audit row")
                    .isEmpty();
            assertThat(grants.findForGrantee(ORDINARY))
                    .as("a refused write must create no grant")
                    .isEmpty();
        }

        @Test
        void aNonAdministratorGets403OnTheReads() throws Exception {
            // 403, not 404: with administration enabled the surface exists.
            caller = cognito(ORDINARY_SUBJECT);
            for (String read : READS) {
                mvc().perform(get(read)).andExpect(status().isForbidden());
            }
        }

        @Test
        void aNonAdministratorCannotWriteEither() throws Exception {
            caller = cognito(ORDINARY_SUBJECT);
            // The writes gate runs before the actor is resolved, so this is
            // 409 rather than 403. Either way nothing is written.
            expectAllWrites(status().isConflict());
            assertThat(audit.events()).isEmpty();
        }
    }

    // ---- writes enabled (Phase 3 shape, not enabled anywhere) ------------

    @Nested
    @DisplayName("Writes enabled: the gate stops refusing, enforcement still off")
    class WritesEnabled {

        @Test
        void aWriteIsNoLongerRefusedByTheAvailabilityGate() throws Exception {
            properties.setAdministrationEnabled(true);
            properties.setAdministrationWritesEnabled(true);

            mvc().perform(post("/api/v1/admin/identities/inst-ordinary/grants")
                            .contentType("application/json")
                            .content("{\"type\":\"IO\",\"ioValue\":\"9500012345\"}"))
                    .andExpect(status().isCreated());

            // And record enforcement is still off, so the grant changes
            // nothing a research user can see.
            assertThat(properties.isEnforcementEnabled()).isFalse();
        }
    }

    // ---- helpers ---------------------------------------------------------

    private void expectAllWrites(org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(post("/api/v1/admin/identities/inst-ordinary/links")
                .contentType("application/json")
                .content("{\"cognitoIssuer\":\"" + ISSUER + "\",\"cognitoSubject\":\"s\","
                        + "\"method\":\"MANUAL\",\"reason\":\"x\"}")).andExpect(expected);
        mvc.perform(delete("/api/v1/admin/identities/inst-ordinary/links/1?reason=x"))
                .andExpect(expected);
        mvc.perform(put("/api/v1/admin/identities/inst-ordinary/suspension")
                .contentType("application/json")
                .content("{\"suspended\":true,\"reason\":\"x\"}")).andExpect(expected);
        mvc.perform(post("/api/v1/admin/identities/inst-ordinary/grants")
                .contentType("application/json")
                .content("{\"type\":\"IO\",\"ioValue\":\"9500012345\"}"))
                .andExpect(expected);
        mvc.perform(patch("/api/v1/admin/identities/inst-ordinary/grants/1")
                .contentType("application/json")
                .content("{\"includeDescendants\":true}")).andExpect(expected);
        mvc.perform(delete("/api/v1/admin/identities/inst-ordinary/grants/1?reason=x"))
                .andExpect(expected);
        mvc.perform(post("/api/v1/admin/administrators")
                .contentType("application/json")
                .content("{\"institutionalIdentifier\":\"inst-ordinary\",\"reason\":\"x\"}"))
                .andExpect(expected);
        mvc.perform(delete("/api/v1/admin/administrators/inst-ordinary?reason=x"))
                .andExpect(expected);
    }

    private static ValidatedCognitoIdentity cognito(String subject) {
        return new ValidatedCognitoIdentity(ISSUER, subject);
    }

    private MockMvc mvc() {
        ArchiveAdminService service = new ArchiveAdminService(
                adminRoles, audit, grants,
                new InMemoryAdminIdentityRepository(), new InMemoryAdminAuditRepository());
        CurrentIdentityProvider identities = () -> Optional.ofNullable(caller);
        AdminAuthorizationController controller = new AdminAuthorizationController(
                service, identities, new IdentityResolver(links), properties,
                Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC));

        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
