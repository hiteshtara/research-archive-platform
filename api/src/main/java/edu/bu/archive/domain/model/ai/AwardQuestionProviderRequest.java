package edu.bu.archive.domain.model.ai;

import java.util.List;

public record AwardQuestionProviderRequest(
        String systemPrompt,
        String question,
        String awardNumber,
        List<AwardQuestionSupport> supports,
        boolean contextTruncated
) {
    public AwardQuestionProviderRequest {
        supports = List.copyOf(supports);
    }
}
