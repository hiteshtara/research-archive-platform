package edu.bu.archive.testsupport;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalRowResponse;

public final class ProposalFixtures {

    private ProposalFixtures() {
    }

    public static ProposalRowResponse proposalRow() {
        return new ProposalRowResponse(
                10L,
                "P-100",
                3,
                "Proposal title",
                "ACTIVE",
                "New",
                "Research",
                "SP-1",
                "Sponsor",
                "UNIT-1",
                "Unit",
                "PERSON-1",
                "Principal Investigator",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
