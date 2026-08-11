package edu.bu.archive.adapter.in.web.dto.ai;

import java.util.List;

/*
 * awardNumber (not "awardFamily") - this repo's own grain rule (business
 * grain is COUNT(DISTINCT award_number)) and build_evidence_embedding.py's
 * own scoping (always exactly one award_number per run) both establish
 * that the real unit of scope here is a single Award, not a wider
 * multi-award-number program grouping. See
 * docs/architecture/AWARD_EVIDENCE_RETRIEVAL_PHASE3_DESIGN.md section 3.3.
 *
 * insufficientEvidence is true whenever results is empty after the full
 * retrieval pipeline (no evidence indexed yet for this Award, or nothing
 * cleared the similarity threshold) - an explicit, named signal, never
 * left for the client to infer from array length alone.
 */
public record AwardEvidenceSearchResponse(
        String query,
        String awardNumber,
        List<AwardEvidenceResultResponse> results,
        boolean insufficientEvidence,
        String correlationId
) {
    public AwardEvidenceSearchResponse {
        results = List.copyOf(results);
    }
}
