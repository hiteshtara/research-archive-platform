package edu.bu.archive.adapter.in.web.dto.award;

/*
 * DERIVED, never persisted as its own table - reproduces
 * Award.initCentralAdminContacts() exactly: the Award's lead unit's
 * administrators (archive.unit_administrator, joined to
 * archive.unit_administrator_type), filtered to
 * default_group_flag = 'C'. See docs/architecture/AWARD_CONTACTS_DESIGN.md
 * for the full Java trace this mirrors - never a guessed or
 * approximated rule. projectRole is
 * unit_administrator_type.description (e.g. "OSP Administrator",
 * "PAFO Administrator") - the same label Kuali itself renders.
 */
public record AwardCentralAdministrationContactResponse(
        String personId,
        String fullName,
        String projectRole,
        String email,
        String phone
) {
}
