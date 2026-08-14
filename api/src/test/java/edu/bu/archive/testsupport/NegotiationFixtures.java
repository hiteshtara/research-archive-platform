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

    /*
     * Real, live-verified Oracle staging fixture (2026-08-14):
     * negotiation_id=420, document_number="367821",
     * negotiation_status FE/"Fully Executed",
     * negotiation_agreement_type NDA/"Non-Disclosure Agreement",
     * negotiation_assc_type_id=1 (code "NO"/"None" - no association),
     * negotiator U93001494 "JESSICA L RIVIECCIO",
     * associated_document_id="419". negotiationId (420) and
     * associatedDocumentId (419) are two different Oracle columns with
     * two different values, close in magnitude by coincidence - never
     * the same field. See NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md.
     */
    public static NegotiationRowResponse negotiationRow420() {
        return new NegotiationRowResponse(
                420L,
                "367821",
                14L,
                "FE",
                "Fully Executed",
                2L,
                "NDA",
                "Non-Disclosure Agreement",
                1L,
                "NO",
                "None",
                "U93001494",
                "JESSICA L RIVIECCIO",
                null,
                null,
                null,
                null,
                "419",
                null,
                null,
                1L,
                "OBJECT-420",
                null,
                null,
                1L,
                "DOCUMENT-OBJECT-420"
        );
    }
}
