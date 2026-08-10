# Time and Money

## The rules

**1. Pending vs. permanent state are different tables, not different
row-states of the same table.** `PendingTransaction` is transient,
in-flight scratch state for a not-yet-approved Time & Money document.
Once approved, its content is *copied* into permanent `TransactionDetail`
rows (classified `PRIMARY` or `INTERMEDIATE` depending on the hierarchy
hop), and `processedFlag` flips — but the pending row is not deleted.
Never assume `PendingTransaction` disappearing means the data is gone;
never assume a `processedFlag` toggle means the row itself changed
identity.

**2. One approved transaction can fan out to many rows across many
Awards.** A single Time & Money document can produce multiple
`AwardAmountInfo` rows across *multiple Awards* (once per hierarchy hop
it touches) and can affect both a *pending* and an *active* Award version
simultaneously when `ALLOW_TM_WHEN_PENDING_AWARD_PARAM` is enabled. Do
not assume a 1:1 relationship between a Time & Money transaction and an
`AwardAmountInfo` row.

**3. "Current" is the highest surrogate ID, not the highest transaction
ID.** `AwardAmountInfo`'s current-state row is determined by
`MAX(award_amount_info_id)` (an OJB `<orderby>`), despite a same-package
service method confusingly named
`fetchAwardAmountInfoWithHighestTransactionId`. Trust the ID ordering
column, not the method name.

**4. `TRANSACTION_ID` means two different things depending on the
table.** On most Time & Money tables it's a numeric surrogate key. On
`AWARD_AMOUNT_TRANSACTION` specifically, it's a `VARCHAR2(10)` **document
number** — this archive stores it separately as `document_number` to
avoid the naming collision misleading a future reader.

**5. Financial totals and "last activity" have different natural
scopes, and conflating them is a real, previously-shipped bug.** A
plain, non-Time-and-Money amendment can mint a new "current" Award
version whose only `award_amount_info` row is a copy-forward snapshot —
not itself created by a Time & Money transaction. Scoping "last Time and
Money action" to the exact `award_id` therefore silently returns nothing
for most ordinary Awards (confirmed: 3 of 4 sampled ordinary Awards had
zero Time-and-Money-created rows on their current version despite real
family history), while the version-specific obligated/anticipated
*totals* are correctly exact-version-scoped (`archive.award_amount_info`
has no `sequence_number` column of its own — it belongs to one specific
`award_id`). The fix: keep obligated/anticipated totals scoped to the
exact `award_id`, but resolve `familyTransactionCount`/
`lastFamilyTimeAndMoneyDocumentNumber`/etc. across the whole
`award_number` family via `archive.award_amount_transaction` (which has
no `sequence_number` column at all — it was never version-scoped to
begin with).

**6. `source_version_number` (Oracle's `VER_NBR`) is not part of the
current-row rule and must never outrank a later-appended row.** Four of
`AwardArchiveRepository.java`'s `archive.award_amount_info` "current row"
selectors (`findCurrentAmounts`, `searchAwards`, `findSummaryCards`,
`findSummaryByAwardId`) sorted `source_version_number DESC NULLS LAST`
*ahead of* `award_amount_info_id DESC`, diverging from Rule 3 and from
this file's other two current-row selectors
(`findTimeAndMoneySummary`, Proposal Explorer's `current_amount`), which
always used the correct pure `award_amount_info_id DESC` form. Live data
(2026-08-10, dev archive) confirmed 767 of 29,379 multi-row Award
families hit a real divergence between the two forms - i.e. real Awards
were showing a stale, superseded amount on the Hierarchy card, Summary
tab, Search results, and Current Amounts endpoint. Fixed by removing
`source_version_number DESC NULLS LAST` from all four sites, so every
current-row selector in the file now uses the same pure Rule-3 form. See
regression tests `AwardArchiveRepositoryTest`
(`*OrdersCurrentAmountByAwardAmountInfoIdOnly`) and
`etl/tests/test_award_amount_info_current_row_selection.py`.

## Why this matters

Rules 1-4 are from reading `TimeAndMoneyHistoryServiceImpl.java` and
`ActivePendingTransactionsServiceImpl.java` directly. Rule 5 was
discovered the hard way: a live bug report ("Time and Money is showing
data only for the test Award; other Award pages appear empty")
traced back to conflating two rules that only *feel* like the same
scoping question but aren't — "what totals does this Award version
have" (exact-version) vs. "when did this Award family last do
anything Time-and-Money-related" (whole-family). See [Award
Versions](Award%20Versions.md) for why `sequence_number` presence/absence
per table is the actual signal to check, not intuition about what
"should" be version-scoped.

## Evidence

- `TimeAndMoneyHistoryServiceImpl.java` (`buildTimeAndMoneyHistoryObjects`),
  `ActivePendingTransactionsServiceImpl.java` —
  `coeus-impl/src/main/java/org/kuali/kra/award/...`.
- Live-verified before/after on real Awards: `100906-00001` (award_id
  3452675) went from 0 to 4 `familyTransactionCount`; `100565-00001`
  (1120712) 0 → 1; `101737-00001` (1124102) 0 → 4; sibling Awards
  `100004-00002` (1207597) and `100004-00003` (award_id 3) confirmed
  fully financially independent after the fix (different totals,
  different transaction counts).
- `204713-00133` (award_id 3187665): investigated 2026-08-10 as a
  suspected $0.00 obligated-amount selection bug (Award Hierarchy card
  showed $0.00 against a real, earlier $280,607.11 amount). Confirmed
  **not a bug**. Four competing `award_amount_info` rows, all
  `source_version_number = 0` (a real Oracle tie): id 3187674
  ($280,607.11, superseded), id 3187908 ($0.00, tied to Oracle document
  923179/transaction 76644 - the *last* row in the 126-row
  `AWARD_AMOUNT_TRANSACTION` ledger for this award, `PROCESSED_FLAG='Y'`
  in `PENDING_TRANSACTIONS`, fully cross-referenced via
  `TRANSACTION_DETAILS`' hierarchy fan-out to sibling award
  `204713-00001`), and two orphaned duplicate rows (ids 3195981/3195982,
  `tnm_document_number='925932'`, `transaction_id` NULL, absent from
  `PENDING_TRANSACTIONS`/`TRANSACTION_DETAILS`/`AWARD_AMOUNT_TRANSACTION`
  entirely). Rule 3's `MAX(award_amount_info_id)` selects id 3195982 -
  and independently, the last *real, fully-processed* transaction
  (id 3187908) already agrees: $0.00. The earlier $280,607.11 was real
  but superseded, not current. See regression test
  `AwardAmountInfoCurrentRowSelectionTest.test_case_a_award_204713_00133_true_zero_is_current`.
- `award_id=8`: real example of the Rule 6 defect this investigation
  surfaced - see Rule 6 above and
  `test_case_b_award_id_8_higher_id_beats_higher_ver_nbr`.

## Data quality observations (not rules - do not encode these in selection logic)

- **Orphaned "phantom" `award_amount_info` rows.** 13,652 rows
  archive-wide (as of 2026-08-10) have `tnm_document_number` set but
  `transaction_id` NULL - a third pattern the person who wrote
  V048's migration comment didn't name: neither "Time-and-Money-created"
  (both set) nor "original row created when the Award was first
  entered" (neither set). The `204713-00133` case above (ids
  3195981/3195982) is one instance: a document number with zero
  corresponding rows anywhere in `PENDING_TRANSACTIONS`,
  `TRANSACTION_DETAILS`, or `AWARD_AMOUNT_TRANSACTION`. This is a
  genuine Oracle/Kuali-side data-quality artifact (most likely a
  document that reserved a number but never completed the pending →
  approved pipeline), not an archive or ETL defect. **It must not
  influence current-row selection** - Rule 3's `MAX(award_amount_info_id)`
  already handles it correctly by construction (an orphan row can still
  legitimately be the true latest state, as it happens to be here, but
  it is never treated specially). Detectable via
  `findTimeAndMoneyHistory`'s existing
  `(transaction_id IS NOT NULL AND tnm_document_number IS NOT NULL) AS time_and_money_created`
  flag, already surfaced to the Time and Money History tab.

## See also

[`docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md`](../architecture/AWARD_TIME_AND_MONEY_DESIGN.md)
for the full schema/ETL mapping, and
[`docs/architecture/AWARD_HIERARCHY_FINANCIAL_SEMANTICS.md`](../architecture/AWARD_HIERARCHY_FINANCIAL_SEMANTICS.md)
for why hierarchy nodes never aggregate totals even though Time & Money
transactions move value between them (see [Award
Hierarchy](Award%20Hierarchy.md)).
