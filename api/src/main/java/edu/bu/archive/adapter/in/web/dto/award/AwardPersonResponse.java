package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AwardPersonResponse(
        Long awardPersonId,
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        String personId,
        Long rolodexId,
        String fullName,
        String contactRoleCode,
        String keyPersonProjectRole,
        String facultyFlag,
        BigDecimal academicYearEffort,
        BigDecimal calendarYearEffort,
        BigDecimal summerEffort,
        BigDecimal totalEffort,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser
) {
}
