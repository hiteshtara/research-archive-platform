package edu.bu.archive.adapter.in.web.dto.award;

/*
 * Real, Award-specific archived data (archive.award_unit_contact, from
 * Oracle's own AWARD_UNIT_CONTACTS - see V041's migration header) -
 * never derived the way Central Administration Contacts is.
 * projectRole resolves unit_administrator_type_code through the shared
 * archive.unit_administrator_type table (falling back to the bare code
 * if no match) so this shows the same human label Kuali does, rather
 * than a raw code. unitNumber is this contact's own associated unit
 * (award_unit_contact.unit_administrator_unit_number) - it can be null,
 * and when present is not always the Award's lead unit, unlike
 * AwardUnitDetailsResponse.leadUnit (always true there). leadUnit here
 * is true only when this contact's own unit matches the Award's lead
 * unit.
 */
public record AwardUnitContactResponse(
        String personId,
        String fullName,
        String projectRole,
        String unitNumber,
        boolean leadUnit,
        String email,
        String phone
) {
}
