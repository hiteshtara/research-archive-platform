package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.util.List;

/*
 * "Transaction Details" - looked up by pendingTransactionId (the
 * numeric surrogate PENDING_TRANSACTIONS/TRANSACTION_DETAILS/
 * award_amount_info.transaction_id all share - never called bare
 * "transactionId" here, since this bundle has multiple differently-
 * typed columns historically named TRANSACTION_ID; see
 * docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md's Traps section).
 *
 * The pending_transaction/pending_transaction_extension-sourced fields
 * (everything except pendingTransactionId/timeAndMoneyDocumentNumber/
 * details) are nullable: whether Oracle retains PENDING_TRANSACTIONS
 * rows indefinitely after processing is an open question this
 * project's own research left unresolved, so a transaction old enough
 * that its working-state row is gone still resolves successfully here
 * from transaction_detail (the durable ledger) alone - it just carries
 * less header detail.
 *
 * fandaDistributionPeriod is pending_transaction_extension's own
 * BUDGET_PERIOD column, deliberately NOT named budgetPeriod here: it
 * is an F&A cost-distribution period identifier, never a Budget
 * Version - see the design doc's "Every relationship to Budget"
 * finding.
 *
 * A single transaction can move money across more than one Award
 * hierarchy node, so details is a list spanning potentially several
 * source/destination Award pairs - never assume one "owning" Award.
 */
public record TimeAndMoneyTransactionResponse(
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
        String fandaDistributionPeriod,
        List<TimeAndMoneyTransactionDetailResponse> details
) {
}
