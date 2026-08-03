package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * "History Timeline" - the archive.award_amount_info append-only
 * ledger, family-wide (every version of awardNumber, newest first),
 * extended with the two Time and Money-only columns V048 added
 * (pendingTransactionId/originatingAwardVersion) plus a
 * repository-computed timeAndMoneyCreated flag.
 *
 * sequenceNumber is this row's OWN Award version (via the
 * award_version join on award_id) - the version the ledger row
 * literally belongs to. originatingAwardVersion is a SEPARATE,
 * independently-set value: the Award version a Time and
 * Money-created snapshot was produced against at approval time. The
 * two are usually equal but can differ (see
 * docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md's "multi-version
 * fan-out" finding) - never assume one implies the other.
 *
 * timeAndMoneyCreated is computed in SQL, not derived in Java/React,
 * so the (pendingTransactionId IS NOT NULL AND
 * timeAndMoneyDocumentNumber IS NOT NULL) rule lives in exactly one
 * place.
 */
public record TimeAndMoneyHistoryEntryResponse(
        long awardAmountInfoId,
        long awardId,
        String awardNumber,
        int sequenceNumber,
        Long pendingTransactionId,
        String timeAndMoneyDocumentNumber,
        Integer originatingAwardVersion,
        BigDecimal obligatedTotalDirect,
        BigDecimal obligatedTotalIndirect,
        BigDecimal obligatedTotalAmount,
        BigDecimal anticipatedChangeDirect,
        BigDecimal anticipatedChangeIndirect,
        BigDecimal anticipatedTotalDirect,
        BigDecimal anticipatedTotalIndirect,
        BigDecimal anticipatedTotalAmount,
        LocalDate awardEffectiveDate,
        boolean timeAndMoneyCreated
) {
}
