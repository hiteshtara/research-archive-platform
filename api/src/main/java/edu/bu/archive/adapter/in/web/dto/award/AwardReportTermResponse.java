package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDate;
import java.util.List;

public record AwardReportTermResponse(
        Long awardReportTermId,
        String reportClassCode,
        String reportCode,
        String frequencyCode,
        String frequencyBaseCode,
        String ospDistributionCode,
        LocalDate dueDate,
        List<AwardReportTermRecipientResponse> recipients
) {
}
