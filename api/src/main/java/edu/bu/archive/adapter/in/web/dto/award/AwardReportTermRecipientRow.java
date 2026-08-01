package edu.bu.archive.adapter.in.web.dto.award;

public record AwardReportTermRecipientRow(
        Long awardReportTermRecipientId,
        Long awardReportTermId,
        Long contactId,
        String contactTypeCode,
        Long rolodexId,
        Integer numberOfCopies
) {
}
