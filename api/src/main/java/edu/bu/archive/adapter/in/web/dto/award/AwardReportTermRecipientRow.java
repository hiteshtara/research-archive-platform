package edu.bu.archive.adapter.in.web.dto.award;

/*
 * Repository-internal row, LEFT JOINed to archive.contact_type (V074) -
 * see AwardArchiveRepository.findReportTermRecipientRows's header
 * comment. Mapped into AwardReportTermRecipientResponse (dropping
 * awardReportTermId, used only for grouping) by
 * AwardArchiveService.findTerms.
 */
public record AwardReportTermRecipientRow(
        Long awardReportTermRecipientId,
        Long awardReportTermId,
        Long contactId,
        String contactTypeCode,
        String contactTypeDescription,
        Long rolodexId,
        Integer numberOfCopies
) {
}
