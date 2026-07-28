package edu.bu.archive.domain.model.ai;

import java.util.UUID;

public record AwardAiSummaryResult(
        AiResponse response,
        UUID correlationId
) {
}
