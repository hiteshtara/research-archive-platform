package edu.bu.archive.adapter.in.web.dto.proposal;

/*
 * archive.proposal_person_unit joined back to archive.proposal_person
 * (for personName) and the shared archive.unit reference table (for
 * unitName) - a person's associated unit(s), each with its own
 * leadUnitFlag. A different concept from the Proposal's own lead unit
 * (see ProposalSummaryResponse.leadUnitNumber), though they agree in
 * the reference fixture (family 205).
 */
public record ProposalAssociatedUnitResponse(
        Long proposalPersonUnitId,
        Long proposalPersonId,
        String personName,
        String unitNumber,
        String unitName,
        boolean leadUnit
) {
}
