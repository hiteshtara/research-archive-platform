package edu.bu.archive.adapter.in.web.dto.award;

/*
 * Resolves one award_id to its family (award_number) and its own
 * position within that family (sequence_number) - the two facts every
 * Budget endpoint needs to compute its bounded-family scope (sequences
 * <= this sequenceNumber). See docs/kuali-business-rules/Budget.md.
 */
public record AwardFamilyPositionRow(
        String awardNumber,
        int sequenceNumber
) {
}
