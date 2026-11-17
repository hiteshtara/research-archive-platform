package edu.bu.archive.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.adapter.in.web.InvestigatorController;
import edu.bu.archive.adapter.in.web.IrbArchiveController;
import edu.bu.archive.adapter.in.web.IrbController;
import edu.bu.archive.adapter.in.web.IrbWorkspaceController;

/**
 * The RELEASE configuration: {@code app.irb.api.enabled=false}.
 *
 * <p>Full application context against a migrated, throwaway PostgreSQL with
 * one seeded IRB protocol. Proves that no IRB-only route is registered, that
 * every other module still starts and answers, and that Global Search and the
 * Dashboard still read IRB through their own repositories exactly as before.
 */
@SpringBootTest(properties = "app.irb.api.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class IrbApiReleaseExclusionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static long seededRecordId;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        seededRecordId = IrbApiExclusionFixtures.migrateAndSeed(postgres);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void noHandlerMappingExistsUnderAnyIrbRoutePrefix() {
        assertThat(IrbApiExclusionFixtures.irbPatterns(handlerMapping))
                .as("no route may be registered under /api/irb or /api/investigators "
                        + "when app.irb.api.enabled=false - a new IRB route must carry the flag too")
                .isEmpty();
        assertThat(IrbApiExclusionFixtures.irbControllerHandlers(handlerMapping))
                .as("no Irb*/Investigator* controller handler may be registered")
                .isEmpty();
    }

    @Test
    void noIrbControllerBeanExists() {
        for (Class<?> controller : List.of(IrbController.class, IrbArchiveController.class,
                IrbWorkspaceController.class, InvestigatorController.class)) {
            assertThat(context.getBeanNamesForType(controller))
                    .as(controller.getSimpleName() + " must not be registered")
                    .isEmpty();
        }
    }

    @Test
    void everyIrbRouteReturnsNotFound() throws Exception {
        for (String path : List.of(
                "/api/irb",
                "/api/irb?query=" + IrbApiExclusionFixtures.SEEDED_IRB_TOKEN,
                "/api/irb/record/" + seededRecordId,
                "/api/irb/ZQIRBEXCLUSIONSEED",
                "/api/irb/families",
                "/api/irb/history",
                "/api/irb/history/" + IrbApiExclusionFixtures.SEEDED_PROTOCOL_ID,
                "/api/irb/record/" + seededRecordId + "/workspace",
                "/api/investigators?email=" + IrbApiExclusionFixtures.SEEDED_PI_EMAIL)) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void everyOtherModuleStillHasHandlerMappings() {
        for (String prefix : List.of(
                "/api/awards", "/api/proposals", "/api/negotiations", "/api/subawards",
                "/api/documents", "/api/v1/documents", "/api/dashboard", "/api/global-search")) {
            assertThat(IrbApiExclusionFixtures.hasPatternUnder(handlerMapping, prefix))
                    .as("a handler must still be registered under " + prefix)
                    .isTrue();
        }
    }

    @Test
    void awardsProposalsNegotiationsSubawardsAndDocumentsStillAnswer() throws Exception {
        mockMvc.perform(get("/api/awards/families")).andExpect(status().isOk());
        mockMvc.perform(get("/api/proposals/families")).andExpect(status().isOk());
        mockMvc.perform(get("/api/negotiations")).andExpect(status().isOk());
        mockMvc.perform(get("/api/subawards")).andExpect(status().isOk());
        mockMvc.perform(get("/api/documents/search")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/documents")).andExpect(status().isOk());
    }

    @Test
    void relatedRecordEndpointsStillAnswer() throws Exception {
        // PR #159's related-record scoping path, on a seeded Award.
        mockMvc.perform(get("/api/v1/awards/" + IrbApiExclusionFixtures.SEEDED_AWARD_ID
                        + "/funding-proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void dashboardStillAnswersWithItsIrbFiguresUnchanged() throws Exception {
        // Enforcement is off (default), so the Dashboard's own IRB branch runs
        // exactly as before; the API flag does not touch it.
        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.irb").value(1));
    }

    @Test
    void globalSearchStillAnswersWithItsIrbLegUnchanged() throws Exception {
        // GlobalSearchService reads IRB through GlobalSearchRepository, not the
        // IRB controllers, so the seeded protocol is still found.
        mockMvc.perform(get("/api/global-search")
                        .param("query", IrbApiExclusionFixtures.SEEDED_IRB_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedModules", empty()))
                .andExpect(jsonPath("$.results[*].module", hasItem("IRB")))
                .andExpect(jsonPath("$.failedModules", not(hasItem("IRB"))));
    }
}
