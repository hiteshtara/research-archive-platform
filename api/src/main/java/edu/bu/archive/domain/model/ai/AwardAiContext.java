package edu.bu.archive.domain.model.ai;

import java.util.List;

public record AwardAiContext(
        String awardNumber,
        Long currentAwardId,
        List<AwardAiContextRecord> records,
        boolean truncated
) {
    public AwardAiContext {
        records = List.copyOf(records);
    }
}
