package edu.bu.archive.adapter.in.web.dto.award;

/*
 * LEFT JOINed to archive.contact_type (V074) for a readable
 * contactTypeDescription alongside the raw contactTypeCode - no
 * foreign key exists from award_report_term_recipient, so an
 * unresolved code still comes back with contactTypeDescription null
 * rather than the row being dropped. AWARD_REP_TERMS_RECNT is
 * genuinely empty archive-wide as of the live 2026-08 Oracle staging
 * verification behind this change - there is no real fixture with a
 * populated recipient row anywhere in the source system, not a load
 * gap on this archive's part.
 */
public record AwardReportTermRecipientResponse(
        Long awardReportTermRecipientId,
        Long contactId,
        String contactTypeCode,
        String contactTypeDescription,
        Long rolodexId,
        Integer numberOfCopies
) {
}
