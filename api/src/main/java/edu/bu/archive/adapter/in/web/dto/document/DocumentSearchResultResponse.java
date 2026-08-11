package edu.bu.archive.adapter.in.web.dto.document;

import java.time.LocalDate;

/*
 * One row of the module-union Kuali document search (see
 * docs/architecture/KUALI_DOCUMENT_METRIC_INVESTIGATION.md). module is
 * one of AWARD/PROPOSAL/NEGOTIATION/SUBAWARD/IRB - never a raw table
 * name. businessRecordNumber is the module's own stable business
 * identifier (award_number, proposal_number, negotiation_id as text,
 * subaward_code, protocol_number) - never the surrogate row id.
 * targetRoute is computed server-side from module + the module's own
 * routable identifier (see DocumentSearchService) so the UI never has
 * to encode per-module routing rules itself.
 */
public record DocumentSearchResultResponse(
        String module,
        String documentNumber,
        String businessRecordNumber,
        String title,
        String status,
        String versionOrSequence,
        LocalDate relevantDate,
        String targetRoute
) {
}
