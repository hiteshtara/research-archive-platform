package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * Compact Award summary only - no comments, Budget, Time and Money, SAP
 * transmission history, or attachments (those are separate, later
 * section endpoints - see AWARD_SEARCH_API_DESIGN.md's Phase 2 scope).
 *
 * Two fields deliberately do NOT appear here, and are not silently
 * mapped to a same-shaped-but-wrong column - see the design doc's "FAIN
 * and account type" section:
 *   - FAIN: no such column exists anywhere in the current archive
 *     schema (confirmed exhaustively - see AWARD_EXTENSION_CGB_DESIGN.md
 *     and SAP_AWARD_TRANSMISSION_ASSESSMENT.md's own open questions).
 *   - "account type": no general Award-level field exists for this -
 *     the only "account_type_code" in this schema is
 *     archive.award_transmission's own SAP-specific field, not a
 *     general Award attribute, and using it here would misrepresent it.
 *
 * closeoutDate is the real column exposed in place of the UI mockup's
 * invented "final expiration date"/"current fund effective date"
 * fields, which have no backing column.
 */
public record AwardSummaryResponse(
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        String title,
        String status,
        String sponsor,
        String primeSponsor,
        String principalInvestigator,
        String leadUnit,
        LocalDate awardEffectiveDate,
        LocalDate awardExecutionDate,
        LocalDate beginDate,
        LocalDate closeoutDate,
        BigDecimal obligatedTotalAmount,
        BigDecimal anticipatedTotalAmount,
        String basisOfPaymentCode,
        String basisOfPaymentDescription,
        String methodOfPaymentCode,
        String methodOfPaymentDescription,
        String rootAwardNumber,
        String parentAwardNumber
) {
}
