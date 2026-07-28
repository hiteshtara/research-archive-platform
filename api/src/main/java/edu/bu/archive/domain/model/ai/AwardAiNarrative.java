package edu.bu.archive.domain.model.ai;

import java.util.List;

public record AwardAiNarrative(
        String overview,
        List<String> notableChanges,
        String archiveAssessment,
        List<AiCitation> citations
) {
    public AwardAiNarrative {
        notableChanges = List.copyOf(notableChanges);
        citations = List.copyOf(citations);
    }

    public static AwardAiNarrative from(
            AiResponse response
    ) {
        return new AwardAiNarrative(
                response.overview(),
                response.notableChanges(),
                response.archiveAssessment(),
                response.citations()
        );
    }
}
