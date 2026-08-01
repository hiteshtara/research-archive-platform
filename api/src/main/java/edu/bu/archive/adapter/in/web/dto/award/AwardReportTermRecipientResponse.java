package edu.bu.archive.adapter.in.web.dto.award;

public record AwardReportTermRecipientResponse(
        Long awardReportTermRecipientId,
        Long contactId,
        String contactTypeCode,
        Long rolodexId,
        Integer numberOfCopies
) {
}
