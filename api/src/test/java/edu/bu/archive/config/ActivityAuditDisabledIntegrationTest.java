package edu.bu.archive.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.config.ActivityAuditFixture.Module;

/**
 * Collection OFF - the default, and every deployed environment's setting -
 * writes nothing, even with the V089 table present and enforcement on or off.
 * The flag is deliberately NOT set here: this is the default configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(DeferredAsyncExecutorConfiguration.class)
@ActiveProfiles("local")
@Testcontainers
@Tag("database")
class ActivityAuditDisabledIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        ActivityAuditFixture.attachmentStorage(registry);
    }

    @BeforeAll
    static void prepareDatabase() throws Exception {
        ActivityAuditFixture.prepare(postgres);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeferredAsyncExecutorConfiguration.DeferredAsyncExecutor asyncExecutor;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AuthorizationProperties authorization;

    @AfterEach
    void enforcementOff() {
        authorization.setEnforcementEnabled(false);
    }

    @ParameterizedTest(name = "enforcement={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("flag OFF: views, refusals, downloads and exports in every module write nothing")
    void flagOffWritesNothing(boolean enforced) throws Exception {
        authorization.setEnforcementEnabled(enforced);
        if (enforced) {
            authorization.setVersionScope(AuthorizationPolicy.VersionScope.FAMILY_WIDE);
            authorization.setDepartmentMatch(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
            authorization.setResearchStaffRoles(Set.of("PI"));
            authorization.setContactDerivation(AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT);
            authorization.setMaxSignInAge(Duration.ofHours(8));
        }
        for (Module module : Module.values()) {
            for (String path : new String[] {
                    module.view(module.permittedId),
                    module.view(module.deniedId),
                    module.download(module.permittedId, 4242L)}) {
                exchange(get(path).with(ActivityAuditFixture.linkedUser()));
            }
        }
        exchange(get("/api/v1/awards/%d/report.pdf".formatted(Module.AWARD.permittedId))
                .with(ActivityAuditFixture.linkedUser()));

        assertThat(jdbc.sql("SELECT count(*) FROM authz.activity_audit").query(Long.class).single())
                .as("with app.authorization.activity-audit.enabled unset, nothing is recorded")
                .isZero();
    }

    /**
     * One request, run strictly sequentially: the dispatch, then (for a
     * StreamingResponseBody download or report) the queued body on this
     * thread, then the async dispatch. See DeferredAsyncExecutorConfiguration.
     */
    private org.springframework.mock.web.MockHttpServletResponse exchange(
            org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            asyncExecutor.runPending();
            return mockMvc.perform(org.springframework.test.web.servlet.request
                    .MockMvcRequestBuilders.asyncDispatch(result)).andReturn().getResponse();
        }
        return result.getResponse();
    }
}
