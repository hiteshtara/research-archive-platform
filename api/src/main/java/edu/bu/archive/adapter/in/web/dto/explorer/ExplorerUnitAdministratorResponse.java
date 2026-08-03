package edu.bu.archive.adapter.in.web.dto.explorer;

/*
 * One archive.unit_administrator row for the standalone Unit Explorer
 * (looked up directly by unit_number, no Award involved - unlike
 * AwardCentralAdministrationContactResponse, which is Award-scoped and
 * already filtered to default_group_flag='C'). Exposes
 * administratorTypeCode/defaultGroupFlag directly (not filtered) so the
 * Explorer can show every administrator on a unit, Central- and
 * non-Central-group alike.
 */
public record ExplorerUnitAdministratorResponse(
        String personId,
        String fullName,
        String administratorTypeCode,
        String administratorTypeDescription,
        String defaultGroupFlag,
        String email,
        String phone
) {
}
