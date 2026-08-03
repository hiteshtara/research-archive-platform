package edu.bu.archive.adapter.in.web.dto.award;

/*
 * Resolves through archive.award_sponsor_contact (Oracle's own
 * AWARD_SPONSOR_CONTACTS, real Award-specific data - see V041's
 * migration header) and, when rolodex_id is set, the shared
 * archive.rolodex reference table for organization/phone/email -
 * AWARD_SPONSOR_CONTACTS has no person_id column at all (confirmed
 * live against BU's real Oracle schema), so a Sponsor Contact is
 * always either Rolodex-linked or a bare cached name with no further
 * contact info to resolve, never an internal archive.person lookup.
 */
public record AwardSponsorContactResponse(
        String fullName,
        String organization,
        String contactRoleCode,
        String email,
        String phone
) {
}
