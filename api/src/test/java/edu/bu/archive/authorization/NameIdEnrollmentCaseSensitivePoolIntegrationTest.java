package edu.bu.archive.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import edu.bu.archive.application.authorization.CognitoProfile;

/** A case-SENSITIVE pool: generated usernames keep the NameID's case and are compared exactly. */
@TestPropertySource(properties = "app.authorization.enrollment.username-case-sensitive=true")
class NameIdEnrollmentCaseSensitivePoolIntegrationTest extends NameIdEnrollmentIntegrationTestBase {

    @Override
    boolean poolCaseSensitive() {
        return true;
    }

    @Test
    void aProfileWhoseUsernameIsOnlyACaseVariantOfTheNameIdKeyIsRefused() throws Exception {
        String lowered = "syntheticsaml_abc-pat-nameid";
        POOL.put(lowered, new CognitoProfile("nid-variant", lowered, true,
                List.of(new CognitoProfile.FederatedIdentity(PROVIDER, "AbC-Pat-NameID")), Map.of()));
        assertThat(status("nid-variant", lowered, SESSION_1, "/api/v1/awards/9000102/summary")).isEqualTo(403);
        assertThat(jdbc.sql("SELECT detail->>'outcome' FROM authz.access_audit WHERE detail->>'cognito_subject' = "
                + "'nid-variant' ORDER BY audit_id DESC LIMIT 1").query(String.class).single())
                .isEqualTo("REFUSED_NAMEID_NOT_PROFILE_KEY");
    }
}
