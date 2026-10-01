package edu.bu.archive.authorization;

import org.springframework.test.context.TestPropertySource;

/** A case-INSENSITIVE pool (Cognito's default for new pools): generated usernames are lowercased. */
@TestPropertySource(properties = "app.authorization.enrollment.username-case-sensitive=false")
class NameIdEnrollmentCaseInsensitivePoolIntegrationTest extends NameIdEnrollmentIntegrationTestBase {

    @Override
    boolean poolCaseSensitive() {
        return false;
    }
}
