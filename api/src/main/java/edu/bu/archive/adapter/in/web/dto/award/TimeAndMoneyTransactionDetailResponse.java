package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;

/*
 * One archive.transaction_detail row - the durable, permanent history
 * ledger a Time and Money transaction writes (as opposed to
 * pending_transaction's working state). A single transaction can
 * produce several of these (classified PRIMARY - the actual requested
 * move - or INTERMEDIATE - a hierarchy hop the money conceptually
 * passes through), each against its own award/version and its own
 * source/destination pair, which is why
 * TimeAndMoneyTransactionResponse.details is a list, not a single
 * value - a transfer between hierarchy nodes never assumes one
 * "owning" Award.
 */
public record TimeAndMoneyTransactionDetailResponse(
        long transactionDetailId,
        String awardNumber,
        int sequenceNumber,
        String timeAndMoneyDocumentNumber,
        String sourceAwardNumber,
        String destinationAwardNumber,
        BigDecimal obligatedAmount,
        BigDecimal obligatedDirectAmount,
        BigDecimal obligatedIndirectAmount,
        BigDecimal anticipatedAmount,
        BigDecimal anticipatedDirectAmount,
        BigDecimal anticipatedIndirectAmount,
        String comments,
        String transactionDetailType
) {
}
