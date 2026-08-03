package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * "Action Summary" - one row per (Time and Money document, affected
 * award) pair, backed by archive.award_amount_transaction (confirmed
 * 1:1 with that pairing directly from Kuali's own service-layer
 * comment - see the design doc). Family-wide (every version of
 * awardNumber), since award_amount_transaction carries no
 * sequence_number of its own. timeAndMoneyDocumentNumber is this
 * action's own Time and Money workflow document - never conflate with
 * an Award's own workflowDocumentNumber, a different KEW document
 * entirely.
 */
public record TimeAndMoneyActionResponse(
        long awardAmountTransactionId,
        String awardNumber,
        String timeAndMoneyDocumentNumber,
        String transactionTypeCode,
        String transactionTypeDescription,
        LocalDate noticeDate,
        String comments,
        String documentStatus,
        LocalDateTime creationDate,
        String sourceUpdateUser,
        LocalDateTime sourceUpdateTimestamp
) {
}
