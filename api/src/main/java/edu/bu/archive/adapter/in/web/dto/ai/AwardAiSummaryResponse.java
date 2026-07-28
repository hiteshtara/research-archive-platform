package edu.bu.archive.adapter.in.web.dto.ai;

import edu.bu.archive.domain.model.ai.AiResponse;

import java.util.List;

public record AwardAiSummaryResponse(
        String summary,
        List<AiCitationResponse> citations,
        String provider,
        String model,
        String correlationId
) {
    public AwardAiSummaryResponse {
        citations = List.copyOf(citations);
    }

    public static AwardAiSummaryResponse from(
            edu.bu.archive.domain.model.ai.AwardAiSummaryResult result
    ) {
        AiResponse response = result.response();
        return new AwardAiSummaryResponse(
                response.summary(),
                response.citations()
                        .stream()
                        .map(citation ->
                                new AiCitationResponse(
                                        citation.recordType(),
                                        citation.recordId(),
                                        citation.awardNumber(),
                                        citation.sequenceNumber()
                                )
                        )
                        .toList(),
                response.provider(),
                response.model(),
                result.correlationId().toString()
        );
    }
}
