package edu.bu.archive.adapter.in.web.dto.proposal;

/*
 * archive.proposal_unit_contact - a genuinely separate sibling table
 * from archive.proposal_person, never merged (see
 * docs/kuali-business-rules/InstitutionalProposal.md and V061's
 * migration comment: live-verified as a different real person than the
 * PI in the reference fixture). unitAdministratorTypeDescription
 * resolves the shared archive.unit_administrator_type reference table
 * (the same table Award's own Central Administration/Unit Contacts
 * reuse) rather than duplicating its descriptions.
 */
public record ProposalUnitContactResponse(
        Long proposalUnitContactId,
        String personId,
        String fullName,
        String unitAdministratorTypeCode,
        String unitAdministratorTypeDescription,
        String unitContactType
) {
}
