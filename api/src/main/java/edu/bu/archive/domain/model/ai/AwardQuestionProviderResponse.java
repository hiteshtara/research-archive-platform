package edu.bu.archive.domain.model.ai;

import java.util.List;

public record AwardQuestionProviderResponse(
        List<String> supportIds,
        List<AiCitation> citations,
        String provider,
        String model,
        Long inputTokenCount,
        Long outputTokenCount
) {
    public AwardQuestionProviderResponse {
        supportIds = List.copyOf(supportIds);
        citations = List.copyOf(citations);
    }
}
