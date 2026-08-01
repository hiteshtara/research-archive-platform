# Award Basis of Payment / Method of Payment — Design and Implementation Record

## Purpose

Capture the two scalar `Award`-level fields (`basisOfPaymentCode`,
`methodOfPaymentCode`) that `AWARD_TERMS_DESIGN.md` identified but
deliberately deferred, because doing so requires a TRUNCATE-path
change (`01_award_versions.sql`, `prepare_versions`, and the full
load's column list) that no Tier 1 work up to that point was scoped to
make. This is the deferred follow-on.

## Scope

`archive.award_version` only — the existing Award core row and its
lookup enrichment. Does not touch any child table, any other Award
subsystem, Time and Money, Budget, or SAP.

## Source material used

- `coeus-impl/src/main/resources/org/kuali/kra/datadictionary/AwardBasisOfPayment.xml`
  and `AwardMethodOfPayment.xml`: both are small business-object
  lookups (`basisOfPaymentCode`/`methodOfPaymentCode` + `description` +
  `versionNumber`, `maxLength="3"`, `NumericValidationPattern` on the
  code — i.e. digit-string codes, not necessarily physically numeric).
- `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`:
  - The `Award`/`AWARD` class-descriptor (lines 54–100) declares
    `basisOfPaymentCode`/`methodOfPaymentCode` as plain scalar
    `jdbc-type="VARCHAR"` fields (lines 82/84) — not `INTEGER` like
    `statusCode`/`awardTransactionTypeCode`, despite all four looking
    like digit codes in the DD. This distinction matters for the ETL:
    `status_code`/`transaction_type_code` are numeric-converted,
    `basis_of_payment_code`/`method_of_payment_code` must NOT be, or a
    leading zero (e.g. `"01"`) would be silently lost.
  - Two `auto-retrieve="false"` reference-descriptors (lines 217–222)
    tie `Award.methodOfPaymentCode`/`Award.basisOfPaymentCode` to
    `AwardMethodOfPayment`/`AwardBasisOfPayment` via `foreignkey`
    — Kuali itself does not eagerly join these at read time.
  - The `AwardBasisOfPayment`/`AwardMethodOfPayment` class-descriptors
    (lines 1082–1107): table names `AWARD_BASIS_OF_PAYMENT`/
    `AWARD_METHOD_OF_PAYMENT`, PK `BASIS_OF_PAYMENT_CODE`/
    `METHOD_OF_PAYMENT_CODE`, plus a `DESCRIPTION` column — pure
    code/description lookups, no `AWARD_ID` anywhere on either.
- Generic Kuali Coeus bootstrap DDL
  (`coeus-db/coeus-db-sql/.../oracle/kc/bootstrap/V300_107__schema.sql`):
  confirms `AWARD.BASIS_OF_PAYMENT_CODE`/`AWARD.METHOD_OF_PAYMENT_CODE`
  are both nullable `VARCHAR2(3)` (lines 276/292 — no `NOT NULL`), and
  `AWARD_BASIS_OF_PAYMENT`/`AWARD_METHOD_OF_PAYMENT` (lines 669–683,
  1205–1219) both have `DESCRIPTION VARCHAR2(200) NOT NULL` and a real
  Oracle-enforced `PRIMARY KEY` on their own code column.
- `bu-db/` (every file): no BU-specific override of either lookup table
  or of `AWARD`'s own `BASIS_OF_PAYMENT_CODE`/`METHOD_OF_PAYMENT_CODE`
  columns was found. The only other place these code names appear in
  `bu-db/` is `BUKR-0009: SAP_interface_implementation.sql`'s
  `AWARD_TRANSMISSION` table (`VARCHAR2(3 BYTE)`, matching the same
  width) — a SAP interface table, explicitly out of scope, not a
  BU override of `AWARD` itself.
- `database/migrations/V011__create_award_archive_tables.sql` (current
  `archive.award_version` schema — confirmed neither field is
  captured), `V013__add_award_primary_current_flag.sql` (the precedent
  for adding a column to `archive.award_version` via a later corrective
  migration rather than rewriting the already-shipped `V011`),
  `sql/extract/award/01_award_versions.sql` (the established
  `LEFT JOIN`-and-denormalize convention for Award's own small code
  lookups — `AWARD_STATUS`, `SPONSOR`, `UNIT`, `AWARD_TRANSACTION_TYPE`
  — extended here for a fifth and sixth lookup), and
  `docs/architecture/AWARD_TERMS_DESIGN.md` (where this follow-on was
  first identified and deferred).

## Findings

- Both `basisOfPaymentCode` and `methodOfPaymentCode` are stored
  **directly on `AWARD`** as scalar columns — not child rows, not a
  join table. `AWARD_BASIS_OF_PAYMENT`/`AWARD_METHOD_OF_PAYMENT` are
  the lookup tables the codes reference, nothing more.
- Oracle column names: `AWARD.BASIS_OF_PAYMENT_CODE`,
  `AWARD.METHOD_OF_PAYMENT_CODE`. Both `VARCHAR2(3)`, nullable, no
  default.
- Lookup tables: `AWARD_BASIS_OF_PAYMENT` (PK `BASIS_OF_PAYMENT_CODE`,
  `DESCRIPTION VARCHAR2(200) NOT NULL`) and `AWARD_METHOD_OF_PAYMENT`
  (PK `METHOD_OF_PAYMENT_CODE`, `DESCRIPTION VARCHAR2(200) NOT NULL`).
  Both are otherwise-unremarkable single-purpose code lookups (a
  `VER_NBR`/`OBJ_ID`/`UPDATE_TIMESTAMP`/`UPDATE_USER` OJB-locking
  scaffold, nothing else).
- The archive stores **both the code and the denormalized
  description**, matching the existing `status_code`/
  `status_description` and `transaction_type_code`/`transaction_type`
  precedent already in `archive.award_version` — a `LEFT JOIN`
  snapshot taken at extraction time, not a value resolved dynamically
  by the API/UI at read time. This project is read-only and has no
  live Kuali lookup service to resolve against, so denormalizing is
  the only option that doesn't leave the description perpetually
  unavailable; it is explicitly the same architectural choice already
  made for every other Award-level code/description pair.
- No BU-specific overrides exist for either field or either lookup
  table.
- No child records exist under either lookup or under the two Award
  scalar fields themselves — this is a pure 2-column addition to the
  existing `archive.award_version` row, not a new table.

## Schema change

A corrective migration
(`database/migrations/V047__add_award_basis_and_method_of_payment.sql`),
not a rewrite of the already-shipped `V011`, following the same
precedent `V013` set for `is_primary_current`:

```sql
ALTER TABLE archive.award_version
    ADD COLUMN IF NOT EXISTS basis_of_payment_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS basis_of_payment_description VARCHAR(300),
    ADD COLUMN IF NOT EXISTS method_of_payment_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS method_of_payment_description VARCHAR(300);
```

`VARCHAR(10)` for the codes (matching the width already used for other
short codes such as `comment_type_code`, generous relative to Oracle's
actual `VARCHAR2(3)`) and `VARCHAR(300)` for the descriptions (matching
`status_description`/`transaction_type`'s existing width, generous
relative to Oracle's `VARCHAR2(200)`). No backfill is included — these
are brand-new nullable columns with no prior data to migrate; existing
rows read `NULL` until the next load (full or incremental) repopulates
them from Oracle.

## Archive mapping

| Archive column | Oracle source | Notes |
|---|---|---|
| `basis_of_payment_code` | `AWARD.BASIS_OF_PAYMENT_CODE` | Kept as `VARCHAR`, never numeric-converted (leading zeros matter) |
| `basis_of_payment_description` | `AWARD_BASIS_OF_PAYMENT.DESCRIPTION` | Denormalized snapshot via `LEFT JOIN ... ON BASIS_OF_PAYMENT_CODE`, taken at extraction time — not resolved dynamically |
| `method_of_payment_code` | `AWARD.METHOD_OF_PAYMENT_CODE` | Kept as `VARCHAR`, never numeric-converted |
| `method_of_payment_description` | `AWARD_METHOD_OF_PAYMENT.DESCRIPTION` | Denormalized snapshot via `LEFT JOIN ... ON METHOD_OF_PAYMENT_CODE`, taken at extraction time |

## Load order

No ordering concern — these are scalar columns on the existing
`archive.award_version` row, populated by the same `upsert_award_version`
call that already handles every other Award scalar field. No new load
function, no new batch domain/entity_type.

## Reconciliation strategy

Same as every other `archive.award_version` scalar field: whatever
Oracle returns on the next load (full, `--load-award-id`, or
`--load-batch`) overwrites the archive row via UPSERT. If a code is
ever cleared in Oracle, the next load correctly nulls it out here too
(no independent deletion/reconciliation question — this isn't a child
table).

## Open questions

- None specific to this change. The general "is Kuali's lookup ever
  reused/renamed across time" question that would apply to any code
  lookup is not investigated here, consistent with how
  `status_code`/`transaction_type_code` were treated — the archive
  stores what Oracle returned at extraction time, not a
  point-in-time-corrected value.

## Decisions

- Denormalize the description via `LEFT JOIN` snapshot, not resolve it
  dynamically — the only workable option for a read-only archive with
  no live Kuali service to query, and the same choice already made for
  every other Award-level code/description pair
  (`status_code`/`status_description`, `sponsor_code`/`sponsor_name`,
  `prime_sponsor_code`/`prime_sponsor_name`,
  `lead_unit_number`/`lead_unit_name`,
  `transaction_type_code`/`transaction_type`).
- Keep both codes as plain strings, never numeric-converted — unlike
  `status_code`/`transaction_type_code`, Oracle's own OJB mapping types
  them `VARCHAR`, not `INTEGER`, and a leading zero would be
  meaningful data loss if coerced to a number.
- Added via a new corrective migration (`V047`), not a rewrite of the
  already-applied `V011` — the same discipline the Cost Share
  `FISCAL_YEAR` fix and `V013` both established: never silently rewrite
  deployed schema history.

## Recommended implementation order

1. ~~Design~~ — done, this document.
2. ~~Migration `V047`~~ — done, verified against a throwaway database.
3. ~~Extraction SQL (`01_award_versions.sql`)~~ — done.
4. ~~`prepare_versions`/`_AWARD_VERSION_COLUMNS`/`upsert_award_version`,
   full-load column list~~ — done.
5. ~~Tests~~ — done.
6. ~~Validation (`pytest`/`ruff`/`mypy`)~~ — done.
7. ~~Coverage/roadmap docs updated~~ — done.

## Date last updated

2026-07-31 (initial implementation).
