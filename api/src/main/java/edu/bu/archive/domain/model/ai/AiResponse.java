package edu.bu.archive.domain.model.ai;

import java.util.List;

public record AiResponse(
        String overview,
        List<String> notableChanges,
        String archiveAssessment,
        List<AiCitation> citations,
        String provider,
        String model,
        Long inputTokenCount,
        Long outputTokenCount
) {
    public AiResponse {
        notableChanges = List.copyOf(notableChanges);
        citations = List.copyOf(citations);
    }
}
