package edu.bu.archive.adapter.out.persistence;

import java.time.LocalDate;

/*
 * Raw repository-level row from DocumentSearchRepository's fixed
 * module-union query. targetId is the module's own routable identifier
 * (award_id, negotiation_id, subaward_id, proposal_number, protocol_id)
 * - never exposed to callers as-is; DocumentSearchService turns
 * (module, targetId) into the final targetRoute string so routing rules
 * live in one place (the service), not duplicated across SQL and Java.
 */
public record DocumentSearchRow(
        String module,
        String documentNumber,
        String businessRecordNumber,
        String title,
        String status,
        String versionOrSequence,
        LocalDate relevantDate,
        String targetId
) {
}
