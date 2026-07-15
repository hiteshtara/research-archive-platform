package edu.bu.archive.adapter.in.web.dto.workspace;

import java.time.LocalDate;

public record IrbProtocolVersionResponse(
        Long protocolId,
        String protocolBase,
        String protocolNumber,
        Integer sequenceNumber,
        String crcProtocolNumber,
        String documentNumber,
        String title,
        String protocolType,
        String protocolStatus,
        String ohrpCategories,
        String summaryKeywords,
        String piId,
        String piEmail,
        String piAffiliation,
        String fundCenterNumber,
        String schoolNumber,
        String irbAnalystId,
        String irbAdvisorId,
        LocalDate receivedDate,
        LocalDate claimedDate,
        LocalDate determinationDate,
        LocalDate approvalDate,
        LocalDate expirationDate,
        LocalDate closureDate,
        LocalDate authorizationDate,
        String recordStorageBox,
        String expirationStatus,
        Integer workingDays,
        Integer calendarDays,
        Integer irbDays,
        Integer piDays,
        Integer fundingSourceCount
) {
}
