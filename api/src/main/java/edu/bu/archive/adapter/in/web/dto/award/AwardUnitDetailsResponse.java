package edu.bu.archive.adapter.in.web.dto.award;

/*
 * The Award's lead unit (Award.lead_unit_number, already archived on
 * archive.award_version) joined against the shared archive.unit
 * reference table - never a second, Award-owned copy of Unit data. An
 * Award has exactly one lead unit, so leadUnit is always true here; it
 * is still returned explicitly (rather than only implied by the
 * endpoint's name) to match AwardUnitContactResponse's own leadUnit
 * field, which is NOT always true - see that DTO's own comment.
 */
public record AwardUnitDetailsResponse(
        String unitNumber,
        String unitName,
        String parentUnitNumber,
        String parentUnitName,
        String organization,
        boolean leadUnit
) {
}
