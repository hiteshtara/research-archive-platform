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
                .containsExactly(102L, 101L);
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

    @Test
    void createsChronologicalChangeOrientedCitationContext() {
        List<AwardRowResponse> rows = representativeRows();
        AwardAiContext context = builder(100, 20_000)
                .build(familyBySequence(rows));

        assertThat(context.awardNumber()).isEqualTo("A-100");
        assertThat(context.currentAwardId()).isEqualTo(104L);
        assertThat(context.records())
                .extracting(record -> record.awardId())
                .containsExactly(101L, 102L, 103L, 104L);
        assertThat(context.records())
                .extracting(record -> record.sequenceNumber())
                .containsExactly(1, 2, 3, 4);

        assertThat(context.records().get(0).changes())
                .satisfies(changes -> {
                    assertThat(changes.title())
                            .isEqualTo("Research project");
                    assertThat(changes.status())
                            .isEqualTo("APPROVED");
                    assertThat(changes.sponsor())
                            .isEqualTo("Sponsor");
                    assertThat(changes.leadUnit())
                            .isEqualTo("Lead Unit");
                });
        assertThat(context.records().get(1).changes())
                .isNull();
        assertThat(context.records().get(1).clearedFields())
                .isNull();
        assertThat(context.records().get(2).changes())
                .satisfies(changes -> {
                    assertThat(changes.status())
                            .isEqualTo("ACTIVE");
                    assertThat(changes.closeoutDate())
                            .isEqualTo(LocalDate.of(2026, 1, 1));
                    assertThat(changes.title()).isNull();
                    assertThat(changes.sponsor()).isNull();
                });
        assertThat(context.records().get(3).changes())
                .isNull();
        assertThat(context.records().get(3).clearedFields())
                .containsExactly("sponsor");

        String serialized = serialize(context);
        assertThat(serialized)
                .contains("\"awardId\":102")
                .contains("\"sequenceNumber\":2")
                .doesNotContain("\"current\":")
                .doesNotContain("\"primaryCurrent\":")
                .doesNotContain("\"accountNumber\":")
                .doesNotContain("\"sponsorAwardNumber\":");
    }

    @Test
    void compactContextReducesRepresentativePayloadSizes() {
        AwardFamilyResponse oneSequence = familyBySequence(
                List.of(representativeRows().getFirst())
        );
        AwardFamilyResponse multiSequence =
                familyBySequence(representativeRows());

        int oneBefore = legacySerializedLength(oneSequence);
        int oneAfter = builder(100, 20_000)
                .serializedLength(
                        builder(100, 20_000).build(oneSequence)
                );
        int multiBefore = legacySerializedLength(multiSequence);
        int multiAfter = builder(100, 20_000)
                .serializedLength(
                        builder(100, 20_000).build(multiSequence)
                );

        System.out.printf(
                "Award AI context sizes: oneSequence=%d->%d, "
                        + "multiSequence=%d->%d%n",
                oneBefore,
                oneAfter,
                multiBefore,
                multiAfter
        );
        assertThat(oneAfter).isLessThan(oneBefore);
        assertThat(multiAfter).isLessThan(multiBefore);
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

    private int legacySerializedLength(
            AwardFamilyResponse family
    ) {
        List<LegacyRecord> records = family.sequences()
                .stream()
                .flatMap(sequence -> sequence.rows().stream())
                .map(row -> new LegacyRecord(
                        row.awardId(),
                        row.awardNumber(),
                        row.sequenceNumber(),
                        row.current(),
                        row.primaryCurrent(),
                        row.title(),
                        row.status(),
                        row.awardSequenceStatus(),
                        row.sponsor(),
                        row.primeSponsor(),
                        row.leadUnit(),
                        row.beginDate(),
                        row.closeoutDate()
                ))
                .toList();
        try {
            return new ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsString(new LegacyContext(
                            family.awardNumber(),
                            records,
                            false
                    ))
                    .length();
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

    private List<AwardRowResponse> representativeRows() {
        return List.of(
                detailedRow(
                        101L, 1, "APPROVED", "Sponsor",
                        LocalDate.of(2025, 1, 1), false
                ),
                detailedRow(
                        102L, 2, "APPROVED", "Sponsor",
                        LocalDate.of(2025, 1, 1), false
                ),
                detailedRow(
                        103L, 3, "ACTIVE", "Sponsor",
                        LocalDate.of(2026, 1, 1), false
                ),
                detailedRow(
                        104L, 4, "ACTIVE", null,
                        LocalDate.of(2026, 1, 1), true
                )
        );
    }

    private AwardFamilyResponse familyBySequence(
            List<AwardRowResponse> rows
    ) {
        AwardRowResponse current = rows.getLast();
        return new AwardFamilyResponse(
                "A-100",
                current,
                rows.stream()
                        .map(row -> new AwardSequenceResponse(
                                row.sequenceNumber(),
                                row.current(),
                                List.of(row)
                        ))
                        .toList()
        );
    }

    private AwardRowResponse detailedRow(
            Long awardId,
            int sequenceNumber,
            String status,
            String sponsor,
            LocalDate closeoutDate,
            boolean current
    ) {
        return new AwardRowResponse(
                awardId,
                "A-100",
                sequenceNumber,
                "Research project",
                status,
                "FINAL",
                sponsor,
                null,
                "Lead Unit",
                null,
                null,
                LocalDate.of(2020, 1, 1),
                closeoutDate,
                current,
                current
        );
    }

    private record LegacyContext(
            String awardNumber,
            List<LegacyRecord> records,
            boolean truncated
    ) {
    }

    private record LegacyRecord(
            Long awardId,
            String awardNumber,
            Integer sequenceNumber,
            Boolean current,
            Boolean primaryCurrent,
            String title,
            String status,
            String awardSequenceStatus,
            String sponsor,
            String primeSponsor,
            String leadUnit,
            LocalDate beginDate,
            LocalDate closeoutDate
    ) {
    }
}
