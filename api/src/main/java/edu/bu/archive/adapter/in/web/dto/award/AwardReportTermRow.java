package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDate;

/*
 * Repository-internal row, LEFT JOINed to archive.report/report_class/
 * frequency/frequency_base/distribution (V074) - see
 * AwardArchiveRepository.findReportTermRows's header comment. Mapped
 * into AwardReportTermResponse (adding recipients/recipientCount) by
 * AwardArchiveService.findTerms.
 */
public record AwardReportTermRow(
        Long awardReportTermId,
        String reportCode,
        String reportDescription,
        String reportClassCode,
        String reportClassDescription,
        String frequencyCode,
        String frequencyDescription,
        Integer advanceNumberOfDays,
        Integer advanceNumberOfMonths,
        String frequencyBaseCode,
        String frequencyBaseDescription,
        String ospDistributionCode,
        String distributionDescription,
        LocalDate dueDate
) {
}
