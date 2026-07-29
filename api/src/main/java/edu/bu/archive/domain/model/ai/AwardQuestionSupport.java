package edu.bu.archive.domain.model.ai;

import java.util.List;

public record AwardQuestionSupport(
        String supportId,
        String description,
        List<AiCitation> citations
) {
    public AwardQuestionSupport {
        citations = List.copyOf(citations);
    }
}
