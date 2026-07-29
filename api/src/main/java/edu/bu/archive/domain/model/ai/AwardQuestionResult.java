package edu.bu.archive.domain.model.ai;

import java.util.List;
import java.util.UUID;

public record AwardQuestionResult(
        String answer,
        String answerType,
        List<AiCitation> citations,
        String provider,
        String model,
        UUID correlationId
) {
    public AwardQuestionResult {
        citations = List.copyOf(citations);
    }
}
