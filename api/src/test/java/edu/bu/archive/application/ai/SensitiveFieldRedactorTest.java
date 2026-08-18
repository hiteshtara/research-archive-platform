package edu.bu.archive.application.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveFieldRedactorTest {

    private final SensitiveFieldRedactor redactor =
            new SensitiveFieldRedactor();

    @Test
    void redactsSensitivePatternsWithoutChangingApprovedText() {
        String value = redactor.redact(
                "Research award contact person@example.edu "
                        + "phone (617) 555-0100 "
                        + "password=hunter2 "
                        + "jdbc:postgresql://db/archive "
                        + "X-Amz-Signature=abcdef&safe=yes"
        );

        assertThat(value)
                .doesNotContain("person@example.edu")
                .doesNotContain("617")
                .doesNotContain("hunter2")
                .doesNotContain("jdbc:postgresql")
                .doesNotContain("abcdef")
                .contains("Research award")
                .contains("safe=yes");
    }

    @Test
    void redactsHttpAuthorizationHeadersEmbeddedInArchivedTransactionDumps() {
        // Matches the real shape found in archived Award SAP transmission
        // sentData/returnedData - a serialized HTTP headers map
        // (java.util.Map#toString style) whose Authorization entry
        // carries a real Basic-auth credential (base64(username:password),
        // trivially reversible - not just an opaque token). Synthetic
        // credential value only.
        String value = redactor.redact(
                "Headers: {SOAPAction=[urn:example], "
                        + "Authorization=[Basic c3ludGhldGljOnBhc3N3b3JkMTIz], "
                        + "Accept=[*/*]}"
        );

        assertThat(value)
                .doesNotContain("Basic c3ludGhldGljOnBhc3N3b3JkMTIz")
                .contains("SOAPAction=[urn:example]")
                .contains("Accept=[*/*]")
                .contains("[REDACTED]");
    }

    @Test
    void redactsBearerAndDigestAuthorizationHeadersOnASeparateLine() {
        String value = redactor.redact(
                "GET /api/x HTTP/1.1\n"
                        + "Authorization: Bearer synthetic-token-abc123\n"
                        + "Host: example.edu"
        );

        assertThat(value)
                .doesNotContain("synthetic-token-abc123")
                .contains("GET /api/x HTTP/1.1")
                .contains("Host: example.edu");
    }

    @Test
    void preservesNullAndOrdinaryArchiveValues() {
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("National Science Foundation"))
                .isEqualTo("National Science Foundation");
    }
}
