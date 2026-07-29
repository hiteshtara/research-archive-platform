package edu.bu.archive.testsupport;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;

public final class NegotiationFixtures {

    private NegotiationFixtures() {
    }

    public static NegotiationRowResponse negotiationRow() {
        return new NegotiationRowResponse(
                101L,
                "DOC-101",
                1L,
                "ACTIVE",
                "Active",
                2L,
                "AGREEMENT",
                "Agreement",
                3L,
                "AWARD",
                "Award",
                "PERSON-1",
                "Negotiator",
                null,
                null,
                null,
                null,
                "00001234",
                null,
                null,
                1L,
                "OBJECT-1",
                null,
                null,
                1L,
                "DOCUMENT-OBJECT-1"
        );
    }
}
