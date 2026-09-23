# Should Subaward search return one current record per family?

**Status: OPEN QUESTION. Measured, not decided.** Nothing in this note
has been implemented, and the FRN and exact-code-ranking work
deliberately did not change the result grain.

## The question

`GET /api/subawards` returns one row per **archive.subaward version**.
It is not, and has never been, one row per Subaward family, despite the
page being called SubawardFamiliesPage. Award solved the same problem
long ago with a materialized `is_primary_current` flag and searches only
current rows; Subaward has no equivalent.

## What the data says (dev RDS, 2026-09-23)

| Figure | Value |
|---|---|
| `archive.subaward` rows (versions) | **88,818** |
| distinct `subaward_code` families | **3,265** |
| rows with `subaward_sequence_status = 'ACTIVE'` | **3,263** |
| families with **no** ACTIVE row | **3** |
| families with **more than one** ACTIVE row | **1** |
| average versions per family | **27.2** |
| most versions in one family | **484** |

About 96% of every row the search can return is a superseded version.

## What that costs a user today

Searching `1920` on dev returns **87 rows across 26 families**. Of those,
**58 rows are family 1920 itself** - its whole version history - and the
remaining 29 rows are 25 unrelated families that matched only incidental
digit collisions inside a document number or a primary key.

Exact-code ranking (shipped separately) fixes *which* row appears first.
It does not reduce the 58.

## Why this was not changed as part of FRN or ranking

Switching the search to current-only is not a drop-in:

- **3 families have no ACTIVE row at all** and would disappear from
  search entirely.
- **1 family has more than one ACTIVE row** and would still duplicate.
- Award's equivalent is a materialized column maintained by the ETL, not
  a runtime filter. Doing the same for Subaward means a migration and a
  loader change, not a `WHERE` clause.
- Every existing count, page total and deep link would change meaning at
  once.

That is a deliberate product decision about what a Subaward search *is*,
and it deserves to be taken on its own rather than as a side effect of
making FRNs searchable.

## What a decision needs

1. Whether an archived-version lookup stays reachable (Award keeps one:
   Historical Award Records is a separate search).
2. What to do about the 3 families with no ACTIVE row - source defect,
   or a real state the UI must represent?
3. Whether the current-row resolution is materialized (Award's model) or
   computed per query.
