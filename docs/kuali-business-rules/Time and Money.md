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

## See also

[`docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md`](../architecture/AWARD_TIME_AND_MONEY_DESIGN.md)
for the full schema/ETL mapping, and
[`docs/architecture/AWARD_HIERARCHY_FINANCIAL_SEMANTICS.md`](../architecture/AWARD_HIERARCHY_FINANCIAL_SEMANTICS.md)
for why hierarchy nodes never aggregate totals even though Time & Money
transactions move value between them (see [Award
Hierarchy](Award%20Hierarchy.md)).
