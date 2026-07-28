package edu.bu.archive.domain.model.ai;

import java.util.List;
import java.util.UUID;

public record AwardAiSummaryResult(
        AiResponse response,
        AwardAiCurrentRecord currentRecord,
        List<AwardAiTimelineRecord> timeline,
        UUID correlationId
) {
    public AwardAiSummaryResult {
        timeline = List.copyOf(timeline);
    }
}
