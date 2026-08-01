package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDate;

public record AwardReportTermRow(
        Long awardReportTermId,
        String reportClassCode,
        String reportCode,
        String frequencyCode,
        String frequencyBaseCode,
        String ospDistributionCode,
        LocalDate dueDate
) {
}
