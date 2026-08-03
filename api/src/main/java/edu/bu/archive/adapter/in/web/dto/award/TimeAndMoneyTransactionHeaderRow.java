package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;

/*
 * Internal row shape for the archive.pending_transaction +
 * pending_transaction_extension join - assembled into
 * TimeAndMoneyTransactionResponse by AwardArchiveService's Time and
 * Money methods. Not returned to clients directly. May not exist for a
 * pendingTransactionId whose originating pending_transaction row
 * Oracle no longer retains - see TimeAndMoneyTransactionResponse's own
 * Javadoc.
 */
public record TimeAndMoneyTransactionHeaderRow(
        long pendingTransactionId,
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
        String processedFlag,
        String fandaDistributionPeriod
) {
}
