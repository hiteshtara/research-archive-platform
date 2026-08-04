package edu.bu.archive.adapter.in.web.dto.proposal;

import java.math.BigDecimal;

/*
 * archive.proposal_person, keyed by this exact proposalId (not the
 * whole family) - PI/MPI/COI/KP via contactRoleCode, the same shared
 * vocabulary already used by Award's own AwardPersonDetailResponse.
 * Associated units live at the separate /units resource, never nested
 * here - see ProposalUnitsResponse.
 */
public record ProposalPersonResponse(
        Long proposalPersonId,
        String personId,
        String fullName,
        String contactRoleCode,
        String keyPersonProjectRole,
        boolean principalInvestigator,
        String facultyFlag,
        BigDecimal academicYearEffort,
        BigDecimal calendarYearEffort,
        BigDecimal summerEffort,
        BigDecimal totalEffort
) {
}
