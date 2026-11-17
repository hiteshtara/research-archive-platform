package edu.bu.archive.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Today's behaviour, unchanged: with {@code app.irb.api.enabled} not set by
 * anything but application.yml's own default, every IRB-only route is still
 * registered and still answers. This is what local and DEV run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class IrbApiDefaultRegistrationIntegrationTest {

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
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void theFlagDefaultsToTrue() {
        assertThat(environment.getProperty("app.irb.api.enabled", Boolean.class))
                .as("application.yml must default app.irb.api.enabled to true")
                .isTrue();
    }

    @Test
    void everyIrbRouteIsStillRegistered() {
        assertThat(IrbApiExclusionFixtures.irbPatterns(handlerMapping))
                .as("the IRB-only route set; a change here must be reflected in "
                        + "docs/security/IRB_RELEASE_EXCLUSION.md")
                .isEqualTo(IrbApiExclusionFixtures.EXPECTED_IRB_PATTERNS);
    }

    @Test
    void irbRoutesStillAnswer() throws Exception {
        mockMvc.perform(get("/api/irb/families")).andExpect(status().isOk());
        mockMvc.perform(get("/api/irb/history")).andExpect(status().isOk());
        mockMvc.perform(get("/api/irb/record/" + seededRecordId))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/investigators")
                        .param("email", IrbApiExclusionFixtures.SEEDED_PI_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historicalStudyCount").value(1));
    }

    @Test
    void sharedSurfacesStillServeIrbUnchanged() throws Exception {
        // Enforcement off and flag at its default: today's behaviour.
        mockMvc.perform(get("/api/documents/search").param("module", "IRB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].documentNumber")
                        .value(IrbApiExclusionFixtures.SEEDED_IRB_DOCUMENT));
        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.irb").value(1))
                .andExpect(jsonPath("$.documents").value(2));
        mockMvc.perform(get("/api/global-search")
                        .param("query", IrbApiExclusionFixtures.SEEDED_IRB_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedModules", empty()))
                .andExpect(jsonPath("$.results[*].module", hasItem("IRB")));
    }
}
