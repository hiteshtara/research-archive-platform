package edu.bu.archive.adapter.out.persistence;

import java.util.regex.Pattern;

/*
 * Decides whether a Subaward search query could be an FRN at all.
 *
 * "FRN" is BU's label for Kuali's PURCHASE_ORDER_NUM - an SAP purchase
 * order number. Searching it means reaching across a Subaward family's
 * whole version and amount history, which costs a sequential scan of
 * archive.subaward AND archive.subaward_amount (an ILIKE '%x%' cannot
 * use a btree index). Paying that on every search took an ordinary
 * query from 205ms to 1085ms on dev, so the two family-history
 * predicates are only emitted when the query could actually be an FRN.
 *
 * The rule is NUMERIC, 9 OR 10 DIGITS, measured against dev rather than
 * assumed (2026-09-23, all three FRN-bearing columns):
 *
 *   archive.subaward.purchase_order_num
 *       45,432 populated, every one exactly 10 characters, every one
 *       numeric - no exceptions at all.
 *   archive.subaward.fsrs_subaward_number
 *       80,794 populated, lengths 9 and 10 except 13 values, and those
 *       13 are not FRNs: "TBD", "334,338", "78,077.28", "2 Yr5 FRNs".
 *   archive.subaward_amount.purchase_order_num
 *       108,116 populated, lengths 9 and 10 except 30 values, the same
 *       kind of free-text junk.
 *
 * So every genuine FRN in the archive is a 9- or 10-digit number. Most
 * carry SAP's 45xxxxxxxx shape (214,919 of them) but not all - 0000000000,
 * 4000000245, 9500309346, 4450000444, 0123456789 and a 9-digit
 * 450000311 family all occur - so the detector deliberately does NOT
 * pin the prefix. A prefix rule would over-fit today's data and
 * silently stop matching a legitimate FRN that starts differently;
 * length plus numeric is the stable property.
 *
 * It is also safe against collisions with ordinary lookups, measured on
 * the same data: ZERO subaward_code values and ZERO document_number
 * values are 9- or 10-digit numeric, so searching a Subaward code or a
 * document number never triggers the expensive path. 403 account_number
 * values are - an account-number search will also look for that value
 * as an FRN, which is correct rather than wasteful, and is rare.
 */
final class SubawardFrnQueryDetector {

    private static final Pattern FRN_SHAPE = Pattern.compile("^[0-9]{9,10}$");

    private SubawardFrnQueryDetector() {
    }

    static boolean looksLikeFrn(String query) {
        return query != null && FRN_SHAPE.matcher(query.trim()).matches();
    }
}
