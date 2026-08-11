package edu.bu.archive.adapter.in.web.dto.ai;

/*
 * Every field here is either a real archive.search_embedding (V071)
 * column value, a value computed from the same distance expression
 * SemanticSearchRepository already uses, or a small, fixed, reviewed
 * per-documentType lookup (title/targetSection) - never free text, never
 * model-generated, never raw SQL. See
 * docs/architecture/AWARD_EVIDENCE_RETRIEVAL_PHASE3_DESIGN.md section 6.
 *
 * sourcePrimaryKey is a String (not Long) - the approved Phase 3A
 * design's own response example shows it quoted ("12345"), and this
 * avoids any JS numeric-precision ambiguity for a value that is only
 * ever displayed/copied, never arithmetically used, by a client.
 *
 * excerpt is always redacted (SensitiveFieldRedactor) and length-capped
 * before this record is constructed - never the raw, unbounded
 * source_text column value.
 */
public record AwardEvidenceResultResponse(
        String documentType,
        String awardNumber,
        String title,
        String excerpt,
        String sourceTable,
        String sourcePrimaryKey,
        double score,
        String targetSection
) {
}
