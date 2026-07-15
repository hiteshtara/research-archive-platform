package edu.bu.archive.domain.model;

import java.time.Instant;
import java.time.LocalDate;

public record IrbProtocol(
        Long recordId,
        String studyId,
        String protocolBase,
        String protocolNumber,
        String title,
        String protocolType,
        String protocolStatus,
        LocalDate approvalDate,
        String piBuid,
        String piFirstName,
        String piLastName,
        String piFullName,
        String piEmail,
        boolean piBuidMissing,
        boolean active,
        Instant loadedAt
) {
}
