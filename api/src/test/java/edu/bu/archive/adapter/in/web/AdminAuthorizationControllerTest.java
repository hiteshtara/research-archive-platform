package edu.bu.archive.adapter.in.web;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import edu.bu.archive.application.authorization.AuthorizationPolicy;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The administrative HTTP surface.
 *
 * <p>Standalone MockMvc rather than {@code @WebMvcTest}: these assertions are
 * about routing, status codes and the service-layer gate, none of which need a
 * Spring context, and the controller deliberately carries no security
 * annotations to test.
 */
class AdminAuthorizationControllerTest {

    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/pool";
    private static final String ADMIN_SUBJECT = "cognito-admin";
    private static final String ORDINARY_SUBJECT = "cognito-ordinary";
    private static final InstitutionalIdentifier ADMIN = new InstitutionalIdentifier("inst-admin");
    private static final InstitutionalIdentifier ORDINARY = new InstitutionalIdentifier("inst-ordinary");

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
        audit = new RecordingAdminAuditSink();
        grants = new InMemoryAccessGrantRepository();

        properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(true);

        caller = cognito(ADMIN_SUBJECT);
    }

    @Nested
    @DisplayName("While enforcement is off the surface does not exist")
    class EnforcementDisabled {

        @Test
        void everyAdministrativeEndpointIs404() throws Exception {
            properties.setEnforcementEnabled(false);
            adminRoles.bootstrap(ADMIN, "operator", "test");
            MockMvc mvc = mvc();

            mvc.perform(get("/api/v1/admin/administrators")).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/admin/identities").param("q", "x")).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/admin/identities/inst-subject")).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/admin/audit")).andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/admin/identities/inst-subject/grants")
                            .contentType("application/json")
                            .content("{\"type\":\"CENTRAL\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void nothingIsWrittenWhileTheSurfaceIsAbsent() throws Exception {
            properties.setEnforcementEnabled(false);
            adminRoles.bootstrap(ADMIN, "operator", "test");

            mvc().perform(post("/api/v1/admin/identities/inst-subject/grants")
                    .contentType("application/json")
                    .content("{\"type\":\"CENTRAL\"}"));

            assertThat(grants.findForGrantee(new InstitutionalIdentifier("inst-subject"))).isEmpty();
            assertThat(audit.events()).isEmpty();
        }
    }

    @Nested
    @DisplayName("A non-administrator is refused with 403")
    class NonAdministrator {

        @Test
        void anAuthenticatedOrdinaryUserGets403OnEveryEndpoint() throws Exception {
            adminRoles.bootstrap(ADMIN, "operator", "test");
            caller = cognito(ORDINARY_SUBJECT);
            MockMvc mvc = mvc();

            mvc.perform(get("/api/v1/admin/administrators"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NOT_AN_ARCHIVE_ADMINISTRATOR"));
            mvc.perform(get("/api/v1/admin/identities").param("q", "x"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/identities/inst-subject"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/audit"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/identities/inst-subject/grants")
                            .contentType("application/json").content("{\"type\":\"CENTRAL\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/admin/identities/inst-subject/grants/1"))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/admin/identities/inst-subject/suspension")
                            .contentType("application/json").content("{\"suspended\":true}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/administrators")
                            .contentType("application/json")
                            .content("{\"institutionalIdentifier\":\"inst-x\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/admin/administrators/inst-admin"))
                    .andExpect(status().isForbidden());

            assertThat(audit.events())
                    .as("a refused request must leave no audit row")
                    .isEmpty();
        }

        @Test
        void anUnresolvableCallerIsAlsoRefusedWith403() throws Exception {
            adminRoles.bootstrap(ADMIN, "operator", "test");
            caller = cognito("cognito-unknown");

            mvc().perform(get("/api/v1/admin/administrators"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void holdingTheAdminRoleWithoutALinkIsStillRefused() throws Exception {
            // The role is keyed on the institutional identity; without a resolved
            // link there is no institutional identity, so there is nobody to check.
            adminRoles.bootstrap(ADMIN, "operator", "test");
            links.revokeAll(cognito(ADMIN_SUBJECT));

            mvc().perform(get("/api/v1/admin/administrators"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("An administrator can manage access")
    class Administrator {

        @BeforeEach
        void bootstrap() {
            adminRoles.bootstrap(ADMIN, "operator", "test");
        }

        @Test
        void grantingReturns201AndLabelsAnIoGrantAsSapAccount() throws Exception {
            mvc().perform(post("/api/v1/admin/identities/inst-subject/grants")
                            .contentType("application/json")
                            .content("{\"type\":\"IO\",\"ioValue\":\"9500012345\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("IO"))
                    .andExpect(jsonPath("$.label").value("SAP Account 9500012345"))
                    .andExpect(jsonPath("$.scopeNote").value(
                            org.hamcrest.Matchers.containsString("Awards only")))
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        void anUnknownGrantTypeIs400() throws Exception {
            mvc().perform(post("/api/v1/admin/identities/inst-subject/grants")
                            .contentType("application/json")
                            .content("{\"type\":\"SUPERUSER\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void revokingAGrantThatDoesNotExistIs404() throws Exception {
            mvc().perform(delete("/api/v1/admin/identities/inst-subject/grants/999"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void describingAnIdentityWithNoAccessSaysSo() throws Exception {
            mvc().perform(get("/api/v1/admin/identities/inst-subject"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.noArchiveAccess").value(true))
                    .andExpect(jsonPath("$.accessNote").value(
                            org.hamcrest.Matchers.containsString("grants nothing on its own")));
        }

        @Test
        void anAdministratorWithNoGrantIsReportedAsHavingNoArchiveAccess() throws Exception {
            mvc().perform(get("/api/v1/admin/identities/" + ADMIN.value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.administrator").value(true))
                    .andExpect(jsonPath("$.noArchiveAccess").value(true))
                    .andExpect(jsonPath("$.accessNote").value(
                            org.hamcrest.Matchers.containsString("grants no access to records")));
        }

        @Test
        void revokingTheLastAdministratorIs409() throws Exception {
            mvc().perform(delete("/api/v1/admin/administrators/" + ADMIN.value()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LAST_ARCHIVE_ADMINISTRATOR"));
        }

        @Test
        void suspensionReturns204AndIsReflectedImmediately() throws Exception {
            MockMvc mvc = mvc();
            mvc.perform(put("/api/v1/admin/identities/inst-subject/suspension")
                            .contentType("application/json")
                            .content("{\"suspended\":true,\"reason\":\"under review\"}"))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/api/v1/admin/identities/inst-subject"))
                    .andExpect(jsonPath("$.suspended").value(true));
        }
    }

    @Nested
    @DisplayName("The administrator gate does not depend on record-level policy")
    class IndependentOfRecordEnforcement {

        /*
         * The point of these: ARCHIVE_ADMIN is checked against
         * authz.admin_role_member and nothing else. It must not inherit the
         * record-level policy's state - otherwise a half-configured
         * deployment could either lock administrators out of the tool they
         * need to fix it, or (far worse) let the gate fall open.
         */

        @Test
        void anAdministratorIsStillRecognisedWhenNoRecordPolicyIsConfigured() throws Exception {
            // properties.policy() is empty: no version scope, no department
            // match, no research-staff roles. Record access would deny
            // everything; administration must still work.
            adminRoles.bootstrap(ADMIN, "operator", "test");
            assertThat(properties.policy()).isEmpty();

            mvc().perform(get("/api/v1/admin/administrators"))
                    .andExpect(status().isOk());
        }

        @Test
        void aNonAdministratorIsStillRefusedWhenNoRecordPolicyIsConfigured() throws Exception {
            adminRoles.bootstrap(ADMIN, "operator", "test");
            caller = cognito(ORDINARY_SUBJECT);
            assertThat(properties.policy()).isEmpty();

            mvc().perform(get("/api/v1/admin/administrators"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aFullyConfiguredRecordPolicyDoesNotMakeAnyoneAnAdministrator() throws Exception {
            // The inverse direction: a permissive record policy must not leak
            // into administration. This caller would be a legitimate archive
            // user and is still not an administrator.
            configureACompleteRecordPolicy();
            adminRoles.bootstrap(ADMIN, "operator", "test");
            caller = cognito(ORDINARY_SUBJECT);

            mvc().perform(get("/api/v1/admin/administrators"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void thereAreNoAdministratorsAtAllBeforeOneIsBootstrapped() throws Exception {
            // Nobody has bootstrapped: every caller, including one holding a
            // resolved identity, is refused. The role cannot be self-granted
            // and there is no HTTP route to the first one.
            configureACompleteRecordPolicy();

            mvc().perform(get("/api/v1/admin/administrators"))
                    .andExpect(status().isForbidden());
            mvc().perform(post("/api/v1/admin/administrators")
                            .contentType("application/json")
                            .content("{\"institutionalIdentifier\":\"inst-admin\"}"))
                    .andExpect(status().isForbidden());
        }

        private void configureACompleteRecordPolicy() {
            properties.setVersionScope(AuthorizationPolicy.VersionScope.values()[0]);
            properties.setDepartmentMatch(AuthorizationPolicy.DepartmentMatch.values()[0]);
            properties.setResearchStaffRoles(new java.util.LinkedHashSet<>(java.util.List.of("PI")));
            properties.setContactDerivation(AuthorizationPolicy.ContactDerivation.values()[0]);
            assertThat(properties.policy()).isPresent();
        }
    }

    @Test
    void anAdministratorHoldingNoGrantIsNotGivenRecordAccessByTheAdminApi() throws Exception {
        // Rule 8, from the HTTP side: reading and writing administration must
        // never create a grant for the actor.
        adminRoles.bootstrap(ADMIN, "operator", "test");
        MockMvc mvc = mvc();

        mvc.perform(get("/api/v1/admin/administrators")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/administrators")
                        .contentType("application/json")
                        .content("{\"institutionalIdentifier\":\"inst-second\"}"))
                .andExpect(status().isCreated());

        assertThat(grants.findForGrantee(ADMIN))
                .as("administering access must never grant the administrator access")
                .isEmpty();
        assertThat(grants.findForGrantee(new InstitutionalIdentifier("inst-second")))
                .as("being appointed an administrator must never create a grant")
                .isEmpty();
    }

    @Test
    void thereIsNoBootstrapEndpoint() throws Exception {
        adminRoles.bootstrap(ADMIN, "operator", "test");

        // The first administrator cannot be granted by an existing administrator,
        // so an HTTP route to it would bypass the whole model (decision D-ID-2).
        mvc().perform(post("/api/v1/admin/bootstrap")
                        .contentType("application/json")
                        .content("{\"institutionalIdentifier\":\"inst-anyone\"}"))
                .andExpect(status().isNotFound());
    }

    private MockMvc mvc() {
        ArchiveAdminService service = new ArchiveAdminService(
                adminRoles, audit, grants,
                new InMemoryAdminIdentityRepository(), new InMemoryAdminAuditRepository());
        CurrentIdentityProvider identities = () -> Optional.ofNullable(caller);
        AdminAuthorizationController controller = new AdminAuthorizationController(
                service, identities, new IdentityResolver(links), properties,
                Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC));

        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static ValidatedCognitoIdentity cognito(String subject) {
        return new ValidatedCognitoIdentity(ISSUER, subject);
    }
}
