package edu.bu.archive.domain.model.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AwardAiContextChanges(
        String title,
        String status,
        String awardSequenceStatus,
        String sponsor,
        String primeSponsor,
        String leadUnit,
        LocalDate beginDate,
        LocalDate closeoutDate
) {
}
