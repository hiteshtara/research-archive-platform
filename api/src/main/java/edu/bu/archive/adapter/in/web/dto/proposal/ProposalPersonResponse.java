package edu.bu.archive.adapter.in.web.dto.proposal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProposalPersonResponse(
        Long proposalId,
        Integer versionNumber,
        String personId,
        String fullName,
        String role,
        String projectRole,
        Boolean principalInvestigator,
        String facultyFlag,
        BigDecimal academicYearEffort,
        BigDecimal calendarYearEffort,
        BigDecimal summerEffort,
        BigDecimal totalEffort,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Integer verNbr
) {
}
