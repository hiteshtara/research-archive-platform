package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDateTime;

public record AwardUnitContactResponse(
        Long awardUnitContactId,
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        String personId,
        String fullName,
        String unitNumber,
        String unitName,
        String parentUnitNumber,
        String parentUnitName,
        String unitAdministratorTypeCode,
        String projectRole,
        String unitContactType,
        String defaultUnitContact,
        String primaryTitle,
        String directoryTitle,
        String officeLocation,
        String emailAddress,
        String officePhone,
        String phoneExtension,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser
) {
}
