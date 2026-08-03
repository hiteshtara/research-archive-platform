package edu.bu.archive.adapter.in.web.dto.explorer;

/*
 * Internal repository->service row for the standalone Unit Explorer
 * lookup - never returned directly from a controller. See
 * AwardContactService/AwardArchiveRepository.findUnitByNumber and
 * ExplorerService, which combines this with
 * findUnitAdministratorsByUnitNumber into the final
 * ExplorerUnitResponse.
 */
public record ExplorerUnitRow(
        String unitNumber,
        String unitName,
        String parentUnitNumber,
        String parentUnitName,
        String organization
) {
}
