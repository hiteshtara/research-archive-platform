package edu.bu.archive.domain.model.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AwardAiContextRecord(
        Long awardId,
        Integer sequenceNumber,
        AwardAiContextChanges changes,
        List<String> clearedFields
) {
    public AwardAiContextRecord {
        clearedFields = clearedFields == null
                ? null
                : List.copyOf(clearedFields);
    }
}
