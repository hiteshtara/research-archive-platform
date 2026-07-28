package edu.bu.archive.domain.model.ai;

import java.util.List;

public record AiResponse(
        String summary,
        List<AiCitation> citations,
        String provider,
        String model,
        Long inputTokenCount,
        Long outputTokenCount
) {
    public AiResponse {
        citations = List.copyOf(citations);
    }
}
