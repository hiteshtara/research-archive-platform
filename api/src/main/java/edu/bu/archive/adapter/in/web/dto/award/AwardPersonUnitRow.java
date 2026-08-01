package edu.bu.archive.adapter.in.web.dto.award;

public record AwardPersonUnitRow(
        Long awardPersonUnitId,
        Long awardPersonId,
        String unitNumber,
        String leadUnitFlag
) {
}
