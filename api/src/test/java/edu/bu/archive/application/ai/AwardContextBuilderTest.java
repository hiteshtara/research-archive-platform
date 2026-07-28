package edu.bu.archive.application.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceResponse;
import edu.bu.archive.config.AiProperties;
import edu.bu.archive.domain.model.ai.AwardAiContext;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwardContextBuilderTest {

    @Test
    void preservesEveryPhysicalIdIncludingRepeatedSequences() {
        AwardContextBuilder builder = builder(100, 20_000);
        AwardRowResponse first = row(
                101L,
                2,
                "Approved title",
                "account-secret",
                "sponsor-secret"
        );
        AwardRowResponse second = row(
                102L,
                2,
                "Contact person@example.edu token=TOP-SECRET "
                        + "jdbc:postgresql://db/archive",
                "other-account",
                "other-sponsor-number"
        );

        AwardAiContext context = builder.build(
                family(List.of(first, second))
        );

        assertThat(context.records())
                .extracting(record -> record.awardId())
                .containsExactly(101L, 102L);
        assertThat(context.records())
                .extracting(record -> record.sequenceNumber())
                .containsExactly(2, 2);
        assertThat(context.truncated()).isFalse();

        String serialized = serialize(context);
        assertThat(serialized)
                .contains("Approved title")
                .doesNotContain("account-secret")
                .doesNotContain("sponsor-secret")
                .doesNotContain("other-account")
                .doesNotContain("other-sponsor-number")
                .doesNotContain("person@example.edu")
                .doesNotContain("TOP-SECRET")
                .doesNotContain("jdbc:postgresql");
    }

    @Test
    void recordLimitIsDeterministicAndDisclosed() {
        List<AwardRowResponse> rows = List.of(
                row(101L, 3, "First", null, null),
                row(102L, 2, "Second", null, null),
                row(103L, 1, "Third", null, null)
        );

        AwardAiContext first = builder(2, 20_000)
                .build(family(rows));
        AwardAiContext second = builder(2, 20_000)
                .build(family(rows));

        assertThat(first).isEqualTo(second);
        assertThat(first.records())
                .extracting(record -> record.awardId())
                .containsExactly(101L, 102L);
        assertThat(first.truncated()).isTrue();
    }

    @Test
    void serializedContextLimitIsDeterministicAndDisclosed() {
        List<AwardRowResponse> rows = List.of(
                row(101L, 2, "Short", null, null),
                row(
                        102L,
                        1,
                        "X".repeat(2_000),
                        null,
                        null
                )
        );
        AwardContextBuilder roomyBuilder = builder(100, 20_000);
        AwardAiContext firstOnly =
                roomyBuilder.build(family(List.of(rows.getFirst())));
        int exactFirstLength =
                roomyBuilder.serializedLength(firstOnly);

        AwardContextBuilder limited =
                builder(100, exactFirstLength + 10);
        AwardAiContext first = limited.build(family(rows));
        AwardAiContext second = limited.build(family(rows));

        assertThat(first).isEqualTo(second);
        assertThat(first.records())
                .extracting(record -> record.awardId())
                .containsExactly(101L);
        assertThat(first.truncated()).isTrue();
        assertThat(limited.serializedLength(first))
                .isLessThanOrEqualTo(exactFirstLength + 10);
    }

    private AwardContextBuilder builder(
            int maxRecords,
            int maxChars
    ) {
        AiProperties properties = new AiProperties();
        properties.setMaxRecords(maxRecords);
        properties.setMaxSerializedContextChars(maxChars);
        return new AwardContextBuilder(
                new SensitiveFieldRedactor(),
                new ObjectMapper().findAndRegisterModules(),
                properties
        );
    }

    private String serialize(
            AwardAiContext context
    ) {
        try {
            return new ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsString(context);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private AwardFamilyResponse family(
            List<AwardRowResponse> rows
    ) {
        AwardSequenceResponse sequence =
                new AwardSequenceResponse(2, true, rows);
        return new AwardFamilyResponse(
                "A-100",
                rows.getFirst(),
                List.of(sequence)
        );
    }

    private AwardRowResponse row(
            Long awardId,
            int sequenceNumber,
            String title,
            String accountNumber,
            String sponsorAwardNumber
    ) {
        return new AwardRowResponse(
                awardId,
                "A-100",
                sequenceNumber,
                title,
                "ACTIVE",
                "ACTIVE",
                "Sponsor",
                "Prime Sponsor",
                "Lead Unit",
                accountNumber,
                sponsorAwardNumber,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2025, 1, 1),
                true,
                awardId == 101L
        );
    }
}
