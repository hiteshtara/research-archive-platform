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
    void preservesNullAndOrdinaryArchiveValues() {
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("National Science Foundation"))
                .isEqualTo("National Science Foundation");
    }
}
