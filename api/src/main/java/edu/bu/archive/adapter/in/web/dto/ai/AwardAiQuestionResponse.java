package edu.bu.archive.adapter.in.web.dto.ai;

import edu.bu.archive.domain.model.ai.AwardQuestionResult;

import java.util.List;

public record AwardAiQuestionResponse(
        String answer,
        String answerType,
        List<AiCitationResponse> citations,
        String provider,
        String model,
        String correlationId
) {
    public AwardAiQuestionResponse {
        citations = List.copyOf(citations);
    }

    public static AwardAiQuestionResponse from(
            AwardQuestionResult result
    ) {
        return new AwardAiQuestionResponse(
                result.answer(),
                result.answerType(),
                result.citations()
                        .stream()
                        .map(citation -> new AiCitationResponse(
                                citation.recordType(),
                                citation.recordId(),
                                citation.awardNumber(),
                                citation.sequenceNumber()
                        ))
                        .toList(),
                result.provider(),
                result.model(),
                result.correlationId().toString()
        );
    }
}
