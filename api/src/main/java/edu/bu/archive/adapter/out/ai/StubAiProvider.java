package edu.bu.archive.adapter.out.ai;

import edu.bu.archive.application.port.out.AiProvider;
import edu.bu.archive.domain.model.ai.AiCitation;
import edu.bu.archive.domain.model.ai.AiRequest;
import edu.bu.archive.domain.model.ai.AiResponse;
import edu.bu.archive.domain.model.ai.AwardAiContextRecord;

import java.util.List;

public class StubAiProvider implements AiProvider {

    @Override
    public String providerName() {
        return "stub";
    }

    @Override
    public String modelName() {
        return "deterministic-award-summary-v1";
    }

    @Override
    public AiResponse generate(
            AiRequest request
    ) {
        List<AwardAiContextRecord> records =
                request.awardContext().records();

        if (records.isEmpty()) {
            return new AiResponse(
                    "The supplied archive context is insufficient "
                            + "to summarize this award.",
                    List.of(),
                    "The supplied archive context is insufficient "
                            + "for an archive assessment.",
                    List.of(),
                    providerName(),
                    modelName(),
                    null,
                    null
            );
        }

        AwardAiContextRecord current = records.stream()
                .filter(record ->
                        record.awardId().equals(
                                request.awardContext()
                                        .currentAwardId()
                        )
                )
                .findFirst()
                .orElse(records.getLast());

        String summary = "Award "
                + request.awardContext().awardNumber()
                + " has "
                + records.size()
                + " supplied historical archive record(s). "
                + "The primary current record is sequence "
                + current.sequenceNumber()
                + " with status "
                + valueOrUnknown(currentStatus(
                        records,
                        current.awardId()
                ))
                + "."
                + (request.awardContext().truncated()
                        ? " The supplied context was truncated."
                        : "");

        List<AiCitation> citations = List.of(
                new AiCitation(
                        "award",
                        String.valueOf(current.awardId()),
                        request.awardContext().awardNumber(),
                        current.sequenceNumber()
                )
        );

        return new AiResponse(
                summary,
                List.of(
                        "The deterministic stub detected "
                                + records.size()
                                + " supplied historical record(s)."
                ),
                request.awardContext().truncated()
                        ? "The supplied archive context was truncated."
                        : "The supplied archive context was complete.",
                citations,
                providerName(),
                modelName(),
                null,
                null
        );
    }

    private String valueOrUnknown(
            String value
    ) {
        return value == null || value.isBlank()
                ? "unknown"
                : value;
    }

    private String currentStatus(
            List<AwardAiContextRecord> records,
            Long currentAwardId
    ) {
        String status = null;
        for (AwardAiContextRecord record : records) {
            if (record.clearedFields() != null
                    && record.clearedFields().contains("status")) {
                status = null;
            }
            if (record.changes() != null
                    && record.changes().status() != null) {
                status = record.changes().status();
            }
            if (record.awardId().equals(currentAwardId)) {
                return status;
            }
        }
        return status;
    }
}
