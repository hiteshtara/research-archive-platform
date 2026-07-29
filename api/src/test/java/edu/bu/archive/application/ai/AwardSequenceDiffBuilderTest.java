package edu.bu.archive.application.ai;

import edu.bu.archive.domain.model.ai.AwardAiContext;
import edu.bu.archive.domain.model.ai.AwardAiContextChanges;
import edu.bu.archive.domain.model.ai.AwardAiContextRecord;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwardSequenceDiffBuilderTest {

    private final AwardSequenceDiffBuilder builder =
            new AwardSequenceDiffBuilder();

    @Test
    void comparesOnlyApprovedFieldsAndPreservesPhysicalRows() {
        AwardAiContext context = new AwardAiContext(
                "A-100",
                202L,
                List.of(
                        record(101L, 1, "Approved", "Sponsor A"),
                        record(102L, 1, "Approved", "Sponsor A"),
                        record(201L, 2, "Closed", "Sponsor B"),
                        record(202L, 2, "Closed", "Sponsor B")
                ),
                false
        );

        var supports = builder.compare(context, 1, 2);

        assertThat(supports)
                .extracting(support -> support.supportId())
                .containsExactly(
                        "status:sequence-1:sequence-2",
                        "sponsor:sequence-1:sequence-2"
                );
        assertThat(supports.getFirst().citations())
                .extracting(citation -> citation.recordId())
                .containsExactly("101", "102", "201", "202");
        assertThat(supports.toString())
                .doesNotContain(
                        "accountNumber",
                        "person",
                        "document",
                        "sourceUpdate",
                        "etl"
                );
    }

    @Test
    void missingSequenceProducesNoDiff() {
        AwardAiContext context = new AwardAiContext(
                "A-100",
                101L,
                List.of(record(
                        101L, 1, "Active", "Sponsor"
                )),
                false
        );

        assertThat(builder.compare(context, 1, 99)).isEmpty();
    }

    private AwardAiContextRecord record(
            long awardId,
            int sequence,
            String status,
            String sponsor
    ) {
        return new AwardAiContextRecord(
                awardId,
                sequence,
                new AwardAiContextChanges(
                        null,
                        status,
                        null,
                        sponsor,
                        null,
                        null,
                        null,
                        null
                ),
                null
        );
    }
}
