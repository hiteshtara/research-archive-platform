package edu.bu.archive.adapter.in.web.dto.proposal;

import java.util.List;

/*
 * The /units resource: Associated Units (a person's own
 * proposal_person_unit rows) and Unit Contacts (the separate
 * proposal_unit_contact table) as two distinct lists - never merged,
 * per explicit instruction. People themselves (PI/Key Personnel) live
 * at the separate /people resource.
 */
public record ProposalUnitsResponse(
        List<ProposalAssociatedUnitResponse> associatedUnits,
        List<ProposalUnitContactResponse> unitContacts
) {
}
