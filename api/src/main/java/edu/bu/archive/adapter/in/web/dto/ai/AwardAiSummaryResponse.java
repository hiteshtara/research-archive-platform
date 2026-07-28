package edu.bu.archive.adapter.in.web.dto.ai;

import edu.bu.archive.domain.model.ai.AiResponse;

import java.util.List;

public record AwardAiSummaryResponse(
        String overview,
        AwardAiCurrentRecordResponse currentRecord,
        List<AwardAiTimelineRecordResponse> timeline,
        List<String> notableChanges,
        String archiveAssessment,
        List<AiCitationResponse> citations,
        String provider,
        String model,
        String correlationId
) {
    public AwardAiSummaryResponse {
        timeline = List.copyOf(timeline);
        notableChanges = List.copyOf(notableChanges);
        citations = List.copyOf(citations);
    }

    public static AwardAiSummaryResponse from(
            edu.bu.archive.domain.model.ai.AwardAiSummaryResult result
    ) {
        AiResponse response = result.response();
        return new AwardAiSummaryResponse(
                response.overview(),
                AwardAiCurrentRecordResponse.from(
                        result.currentRecord()
                ),
                result.timeline()
                        .stream()
                        .map(AwardAiTimelineRecordResponse::from)
                        .toList(),
                response.notableChanges(),
                response.archiveAssessment(),
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
