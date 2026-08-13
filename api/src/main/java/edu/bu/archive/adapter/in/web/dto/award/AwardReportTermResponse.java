package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDate;
import java.util.List;

/*
 * LEFT JOINed to archive.report/report_class/frequency/frequency_base/
 * distribution (V074) for readable labels alongside every raw code -
 * see AwardArchiveRepository.findReportTermRows's header comment. None
 * of these lookups have a foreign key from award_report_term, so an
 * unresolved code still comes back with its *Description sibling null
 * rather than the row being dropped; AwardTermsSection.tsx falls back
 * to the raw code in that case. advanceNumberOfDays/advanceNumberOfMonths
 * come from archive.frequency and are frequently both null (e.g. real
 * fixture award_id 2727052's own frequencyCode "5"/"As required") -
 * that is a genuine, live-verified Oracle value, not a load gap.
 * recipientCount always equals recipients.size(); exposed separately so
 * the UI can render "0 recipients" without needing to load the array.
 */
public record AwardReportTermResponse(
        Long awardReportTermId,
        String reportCode,
        String reportDescription,
        String reportClassCode,
        String reportClassDescription,
        String frequencyCode,
        String frequencyDescription,
        String frequencyBaseCode,
        String frequencyBaseDescription,
        String ospDistributionCode,
        String distributionDescription,
        Integer advanceNumberOfDays,
        Integer advanceNumberOfMonths,
        LocalDate dueDate,
        int recipientCount,
        List<AwardReportTermRecipientResponse> recipients
) {
}
