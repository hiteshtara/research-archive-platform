package edu.bu.archive.adapter.in.web.dto.explorer;

import edu.bu.archive.adapter.in.web.dto.award.AwardCentralAdministrationContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitDetailsResponse;

import java.util.List;

/*
 * Aggregates the same four data sources the public Award Contacts
 * feature already exposes (AwardContactService/AwardV1Controller's
 * /people, /unit-details, /unit-contacts, /sponsor-contacts,
 * /central-administration-contacts) into one Explorer-shaped response,
 * for a single navigable "Award Contacts" Explorer result - no new SQL,
 * every field reuses the already-proven DTOs and queries directly.
 */
public record ExplorerAwardContactsResponse(
        ExplorerAwardResponse award,
        List<AwardPersonDetailResponse> keyPersonnel,
        AwardUnitDetailsResponse unitDetails,
        List<AwardUnitContactResponse> unitContacts,
        List<AwardSponsorContactResponse> sponsorContacts,
        List<AwardCentralAdministrationContactResponse> centralAdministrationContacts
) {
}
