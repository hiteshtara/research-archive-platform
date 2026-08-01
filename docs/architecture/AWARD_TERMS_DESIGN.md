# Award Terms — Design and Implementation Record

## Purpose

Design, then implement, incremental UPSERT support for the Award Terms
subsystem's real, currently-unarchived child tables
(`archive.award_sponsor_term`, `archive.award_report_term`,
`archive.award_report_term_recipient`) — and record why two of the six
tables named in the original request (`AWARD_BASIS_OF_PAYMENT`,
`AWARD_METHOD_OF_PAYMENT`) turned out not to be part of this subsystem
at all, and why the Award Template Terms tables are excluded, so future
sessions don't have to re-derive any of it.

## Scope

`AWARD_SPONSOR_TERM`, `AWARD_REPORT_TERMS`, `AWARD_REP_TERMS_RECNT` —
the three tables that are both (a) real, live Award-instance data (not
template/reference metadata) and (b) not already archived. Does not
touch `archive.award_version`, `archive.award_person`, or any other
existing table's schema or UPSERT behavior. Does not touch Award
Reporting (`AWARD_CLOSEOUT`, `AWARD_PAYMENT_SCHEDULE`) beyond the term
data itself, Award Budget, Time and Money, Award Contacts, or the
already-complete Award Custom Data / Award People subsystems.

## Source material used

- Upstream Kuali Coeus source (`/Users/mukadder/kuali-project/kuali-research`,
  read-only), `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`:
  the `Award` class-descriptor itself (lines 54–275, in particular the
  `awardSponsorTerms`/`awardReportTermItems` named collections at lines
  135–137 and 166, and the scalar `basisOfPaymentCode`/
  `methodOfPaymentCode` fields at lines 82/84), plus the full
  class-descriptors for `AwardSponsorTerm` (`AWARD_SPONSOR_TERM`, lines
  1125–1138), `AwardReportTerm` (`AWARD_REPORT_TERMS`, lines 692–728),
  `AwardReportTermRecipient` (`AWARD_REP_TERMS_RECNT`, lines 730–750),
  `AwardBasisOfPayment`/`AwardMethodOfPayment` (lines 1082–1107),
  `AwardTemplateTerm`/`AwardTemplateReportTerm`/
  `AwardTemplateReportTermRecipient` (lines 1163–1232), and the small
  code lookups they reference (`SponsorTerm`/`SponsorTermType`,
  `ReportClass`, `Report`, `Frequency`, `FrequencyBase`, `Distribution`,
  `ContactType`).
- BU 7.3 reference tree (`reference/kuali/award/`): `AwardReportTerm.xml`
  — a Struts/data-dictionary attribute-override bean
  (`ospDistributionCode`) — confirms `AwardReportTerm` is a real,
  BU-customized business object. No BU-specific override file exists
  for `AwardSponsorTerm`/`AwardReportTermRecipient`; per the same
  reasoning already established for `AwardPersonUnit` in
  `AWARD_PEOPLE_EXPANSION_DESIGN.md`, their absence doesn't indicate
  BU doesn't use them.
- `database/migrations/V011__create_award_archive_tables.sql` (current
  `archive.award_version` schema, confirming `basis_of_payment_code`/
  `method_of_payment_code` are not currently captured anywhere),
  `sql/extract/award/01_award_versions.sql` (established `LEFT JOIN`
  denormalization style for Award's own small code lookups —
  `AWARD_STATUS`, `SPONSOR`, `UNIT`, `AWARD_TRANSACTION_TYPE`),
  `AWARD_PEOPLE_EXPANSION_DESIGN.md`/`AWARD_CUSTOM_DATA_DESIGN.md` (the
  two most recent Tier 1 designs this one directly extends).

## Assumptions

- `SPONSOR_TERM_ID` (on `AWARD_SPONSOR_TERM`) and `REPORT_CLASS_CODE`/
  `REPORT_CODE`/`FREQUENCY_CODE`/`FREQUENCY_BASE_CODE`/
  `OSP_DISTRIBUTION_CODE` (on `AWARD_REPORT_TERMS`) are cross-cutting
  lookup codes whose own extraction/verification is out of scope here —
  kept as bare, unjoined values, the same convention already established
  for `custom_attribute_id`/`inv_credit_type_code`. Unlike
  `STATUS_CODE`/`SPONSOR_CODE`/`TRANSACTION_TYPE_CODE` (already joined
  in `01_award_versions.sql`), none of these lookups have been
  independently verified for Award, so the more conservative
  "unverified lookup" precedent applies, not the "verified Award-owned
  dimension" one.
- `CONTACT_TYPE_CODE`/`ROLODEX_ID` on `AWARD_REP_TERMS_RECNT` follow the
  same bare-code convention already used for `archive.award_person`'s
  own `rolodex_id`/`contact_role_code`.

## Findings

### Complete Award Terms object graph

```
Award (AWARD)
├── basisOfPaymentCode -> AwardBasisOfPayment (AWARD_BASIS_OF_PAYMENT)   [scalar code on Award itself, NOT a child row]
├── methodOfPaymentCode -> AwardMethodOfPayment (AWARD_METHOD_OF_PAYMENT) [scalar code on Award itself, NOT a child row]
├── AwardSponsorTerm (AWARD_SPONSOR_TERM)                                [MISSING - this work]
│   └── sponsorTermId -> SponsorTerm (SPONSOR_TERM)                     [unverified lookup, unjoined]
└── AwardReportTerm (AWARD_REPORT_TERMS)                                [MISSING - this work]
    └── AwardReportTermRecipient (AWARD_REP_TERMS_RECNT)                [MISSING - this work]

AwardTemplate (template/reference metadata, not a real Award record)
├── AwardTemplateTerm (AWARD_TEMPLATE_TERMS)                            [out of scope - template only]
└── AwardTemplateReportTerm (AWARD_TEMPLATE_REPORT_TERMS)               [out of scope - template only]
    └── AwardTemplateReportTermRecipient (AWARD_TEMPL_REP_TERMS_RECNT)  [out of scope - template only]
```

### `AWARD_BASIS_OF_PAYMENT`/`AWARD_METHOD_OF_PAYMENT` are not child tables at all

Both are pure code/description lookup tables (`BASIS_OF_PAYMENT_CODE`/
`METHOD_OF_PAYMENT_CODE` as their own primary key, no `AWARD_ID`
anywhere in either class-descriptor). The actual Award data is two
scalar fields directly on `AWARD` itself
(`Award.basisOfPaymentCode`/`Award.methodOfPaymentCode`, confirmed at
repository-award.xml lines 82/84) — architecturally identical to
`STATUS_CODE`/`SPONSOR_CODE`, which `01_award_versions.sql` already
denormalizes via `LEFT JOIN`. Capturing them the same way would require
adding two columns to `archive.award_version` and updating both the
Oracle-side join and the **TRUNCATE-based full load's** column list —
out of scope here (see Decisions: this work makes no TRUNCATE-path
changes at all). Confirmed via direct schema/extraction-SQL inspection
that neither column is captured by the archive today. Recorded as a
well-scoped, deliberately deferred follow-on, not silently dropped.

### `AWARD_TEMPLATE_TERMS`/`AWARD_TEMPLATE_REPORT_TERMS`/`AWARD_TEMPLATE_REPORT_TERM_RECIPIENT` are template-only

All three key off `AWARD_TEMPLATE_CODE` (a reference to `AwardTemplate`,
Kuali's reusable-defaults feature for new awards), never `AWARD_ID` or
`AWARD_NUMBER`. None of the three represent an actual, real Award
record — confirmed directly in their class-descriptors
(`templateCode`/`AWARD_TEMPLATE_CODE` is the only parent reference on
every one of them). Per the explicit instruction to include Template
Terms "only if they are truly part of active Award records," they are
excluded.

### Oracle tables, PK/FK mappings

| Table | PK column | Sequence | FK column(s) | Parent |
|---|---|---|---|---|
| `AWARD_SPONSOR_TERM` | `AWARD_SPONSOR_TERM_ID` | `SEQ_AWARD_SPONSOR_TERM` (**own**, not shared) | `AWARD_ID`, `AWARD_NUMBER`, `SEQUENCE_NUMBER` (all direct) | `AWARD` |
| `AWARD_REPORT_TERMS` | `AWARD_REPORT_TERMS_ID` | `SEQUENCE_AWARD_ID` (shared) | `AWARD_ID`, `AWARD_NUMBER`, `SEQUENCE_NUMBER` (all direct) | `AWARD` |
| `AWARD_REP_TERMS_RECNT` | `AWARD_REP_TERMS_RECNT_ID` | `SEQ_AWARD_REP_TERMS_RECNT_ID` (**own**, not shared) | `AWARD_REPORT_TERMS_ID` only — **no `AWARD_ID` column of its own** | `AWARD_REPORT_TERMS` |

**Refines, not contradicts, the prior "everything shares
`SEQUENCE_AWARD_ID`" finding** (`AWARD_DOMAIN_STUDY.md`): two of these
three tables draw from their own dedicated sequences
(`SEQ_AWARD_SPONSOR_TERM`, `SEQ_AWARD_REP_TERMS_RECNT_ID`), the same way
`award_custom_data_id` already did (`SEQ_AWARD_CUSTOM_DATA_ID`). This
doesn't weaken the UPSERT-conflict-key design at all — a table's own
surrogate PK is a safe conflict key regardless of which sequence backs
it, since UPSERT conflict resolution is always scoped to one table —
but it does mean "shares `SEQUENCE_AWARD_ID`" can no longer be assumed
by default for a not-yet-investigated Award child table; it must be
checked per table, exactly as done here.

`AWARD_REP_TERMS_RECNT` has the same structural shape already solved for
Award People's grandchild (`AWARD_PERS_UNIT_CRED_SPLITS`): no `AWARD_ID`
of its own, requiring `AWARD_ID`/`AWARD_NUMBER`/`SEQUENCE_NUMBER` to be
denormalized through an Oracle-side `JOIN` back up to `AWARD_REPORT_TERMS`
so the existing generic `read_award_children_matching_award_ids` reader
needs zero changes. `AWARD_SPONSOR_TERM` and `AWARD_REPORT_TERMS`, by
contrast, both carry `AWARD_ID`/`AWARD_NUMBER`/`SEQUENCE_NUMBER`
directly — no join needed for either, the simpler shape already used by
`award_amount_info`/`award_person`/`award_funding_proposal`/
`award_custom_data`.

### Current archive coverage

- `archive.award_sponsor_term` — missing, this work.
- `archive.award_report_term` — missing, this work.
- `archive.award_report_term_recipient` — missing, this work.
- `archive.award_version.basis_of_payment_code`/`method_of_payment_code`
  — missing, explicitly deferred (see Decisions).

### Missing target tables (new migration)

`database/migrations/V040__create_award_terms.sql` (additive only —
`CREATE TABLE IF NOT EXISTS` + indexes):

```sql
CREATE TABLE IF NOT EXISTS archive.award_sponsor_term (
    award_sponsor_term_id     BIGINT PRIMARY KEY,
    award_id                  BIGINT NOT NULL
                                  REFERENCES archive.award_version(award_id)
                                  ON DELETE CASCADE,
    award_number              VARCHAR(50),
    sequence_number           INTEGER,

    sponsor_term_id           BIGINT,

    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),
    source_version_number     BIGINT,

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);
CREATE INDEX ix_award_sponsor_term_award ON archive.award_sponsor_term (award_id, award_sponsor_term_id);
CREATE INDEX ix_award_sponsor_term_lookup ON archive.award_sponsor_term (sponsor_term_id);

CREATE TABLE IF NOT EXISTS archive.award_report_term (
    award_report_term_id      BIGINT PRIMARY KEY,
    award_id                  BIGINT NOT NULL
                                  REFERENCES archive.award_version(award_id)
                                  ON DELETE CASCADE,
    award_number              VARCHAR(50),
    sequence_number           INTEGER,

    report_class_code         VARCHAR(50),
    report_code                VARCHAR(50),
    frequency_code             VARCHAR(50),
    frequency_base_code        VARCHAR(50),
    osp_distribution_code      VARCHAR(50),
    due_date                   DATE,

    source_update_timestamp    TIMESTAMP,
    source_update_user         VARCHAR(100),
    source_version_number      BIGINT,

    loaded_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                    BIGINT REFERENCES archive.load_run(load_id)
);
CREATE INDEX ix_award_report_term_award ON archive.award_report_term (award_id, award_report_term_id);

CREATE TABLE IF NOT EXISTS archive.award_report_term_recipient (
    award_report_term_recipient_id  BIGINT PRIMARY KEY,
    award_report_term_id            BIGINT NOT NULL
                                        REFERENCES archive.award_report_term(award_report_term_id)
                                        ON DELETE CASCADE,
    award_id                        BIGINT NOT NULL
                                        REFERENCES archive.award_version(award_id)
                                        ON DELETE CASCADE,
    award_number                    VARCHAR(50),
    sequence_number                 INTEGER,

    contact_id                      BIGINT,
    contact_type_code               VARCHAR(50),
    rolodex_id                      BIGINT,
    number_of_copies                INTEGER,

    source_update_timestamp         TIMESTAMP,
    source_update_user              VARCHAR(100),
    source_version_number           BIGINT,

    loaded_at                       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                         BIGINT REFERENCES archive.load_run(load_id)
);
CREATE INDEX ix_award_report_term_recipient_award ON archive.award_report_term_recipient (award_id, award_report_term_recipient_id);
CREATE INDEX ix_award_report_term_recipient_term ON archive.award_report_term_recipient (award_report_term_id);
```

### UPSERT conflict keys

Each table's own surrogate PK: `award_sponsor_term_id`,
`award_report_term_id`, `award_report_term_recipient_id` — each unique
within its own table regardless of which Oracle sequence backs it (see
Findings above).

### Load order

Within `_run_load_award_id`'s existing family-widened transaction, after
the existing eight tables (version → amount_info → person →
funding_proposal → custom_data → person_unit →
person_unit_credit_split → person_credit_split):

9. `award_sponsor_term` (FK to `award_id` only, no dependency on
   anything added in this pass)
10. `award_report_term` (FK to `award_id` only)
11. `award_report_term_recipient` (FK to `award_report_term_id` — **must
    follow step 10**, since it depends on `award_report_term` rows
    existing first, same FK-ordering pattern already proven for
    `award_person_unit_credit_split`/`award_person_unit`)

No new Oracle family-resolution scan, no new top-level load function —
`award_sponsor_term`/`award_report_term` reuse
`read_award_children_matching_award_ids` exactly as `award_custom_data`
does (both already carry `award_id` directly); `award_report_term_recipient`
reuses it the same way `award_person_unit_credit_split` does, via a
join-denormalized `award_id`.

### Batch behavior

No new batch domain/entity_type. All three tables are children of the
`AWARD`/`AWARD` entity that already exists — they ride along for free on
`--create-batch`/`--load-batch`/`--show-batch`, exactly as every
previous Tier 1 addition has.

### Deletion/reconciliation strategy

Deferred, identically to every other Award child table so far — no
hard-delete, no soft-delete marking implemented. Same
recommended-but-unimplemented default already recorded in
`AWARD_IMPLEMENTATION_ROADMAP.md`. Not re-decided here.

### Family-widening behavior

Unchanged from Phase 4A: `_run_load_award_id` still resolves the
requested `award_id`'s `award_number`, re-reads that entire family fresh
from Oracle, and re-upserts every member together in one transaction.
All three new tables are scoped to the same already-resolved
`family_award_ids` set — no new widening logic needed, since none of
these three tables interact with `is_primary_current` at all (that
invariant is exclusively about `archive.award_version` rows).

### Test plan

Extend `etl/tests/test_award_incremental_upsert.py` in place — same
rationale as every prior Tier 1 addition: these are not independent
load paths, they're three more child tables on the same family-widened
load. New fixtures: `_sponsor_term_row`, `_report_term_row`,
`_report_term_recipient_row`; a `sponsor_terms`/`report_terms`/
`report_term_recipients` param added to `_patched_oracle` (dispatching
the three new Oracle SQL path constants, defaulting to empty
DataFrames). New/extended tests: insert-all-N-tables (extending the
existing "first load" test from eight to eleven tables), reload-
unchanged, a value-change-produces-an-update test for at least one
table, an FK-ordering test proving `award_report_term_recipient` loads
correctly when its parent `award_report_term` row is newly inserted in
the very same transaction (mirroring
`test_person_unit_credit_split_loads_correctly_when_its_parent_unit_is_new`),
a does-not-touch-unrelated-award isolation test, a dry-run test, and
batch-level assertions that the three new tables' counts propagate
through `_run_load_award_batch`'s report dict.

### Local real-data smoke-test plan

Same shape as `AWARD_PEOPLE_EXPANSION_DESIGN.md`'s, prepared but not
run here for the same reason (requires BU VPN + a real AWS SSM session,
outside this work's authorization): BU VPN → `buaws` if needed → start
the approved SSM tunnel (`docs/runbooks/LOCAL_SETUP.md`'s exact
target) → export `POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DB` and
`ORACLE_USER`/`ORACLE_PASSWORD`/`ORACLE_DSN` → pick one real `AWARD_ID`
with at least one row in `AWARD_SPONSOR_TERM` and `AWARD_REPORT_TERMS`
(ideally one whose report term also has a recipient row) → from `etl/`:
`uv run python load_awards_from_csv.py --load-award-id <award_id> --dry-run`,
inspect the report for all eleven tables and confirm nothing persisted
→ `uv run python load_awards_from_csv.py --load-award-id <award_id>`
(real load) → re-run the exact same command immediately → confirm the
second run reports `inserted=0 updated=0` and `unchanged` equal to each
new table's row count for that award (or `unchanged=0` if that award
genuinely has no rows in one of the three tables — legitimate, not a
bug) → `uv run python scripts/reconcile_load.py --domain AWARD --limit 5`
to confirm no discrepancy.

## Findings (real-data smoke test — real issue caught)

The first real `--load-award-id --dry-run` run against real Oracle
failed with `RuntimeError: award_report_terms.csv is missing columns:
award_report_term_id` before any Award Terms data was written (the
transaction never reached a commit/rollback for these tables — Phase
4A's existing tables were unaffected). Root cause:
`10_award_report_terms.sql` selected `art.AWARD_REPORT_TERMS_ID`
unaliased. The real Oracle column name is `AWARD_REPORT_TERMS_ID`
(plural "TERMS", matching the table name) — confirmed against
`repository-award.xml`'s `AwardReportTerm` class-descriptor, which maps
that same column to the singular Java field `awardReportTermId`. Every
other piece of this subsystem (the archive column, `_run_load_award_id`,
`upsert_award_report_term`, and `11_award_report_term_recipients.sql`'s
own FK alias) already used the singular `award_report_term_id` — only
`10_award_report_terms.sql`'s own `SELECT` had never been aliased to
match. Fixed by aliasing `art.AWARD_REPORT_TERMS_ID AS AWARD_REPORT_TERM_ID`.
`09_award_sponsor_terms.sql` and `11_award_report_term_recipients.sql`
were re-inspected against the same class of mismatch and found correct
(`AWARD_SPONSOR_TERM_ID` is singular in Oracle already; `11` had already
aliased both its own PK and its FK reference correctly).

This was a SQL/transform contract bug the existing mocked-Oracle test
suite could not have caught, because every hand-written test fixture
already used the *correct* column name — the bug only exists at the
boundary between the literal `.sql` file text and the loader's column
assumptions. Added `AwardTermsSqlColumnContractTest` to
`test_award_incremental_upsert.py`: it parses each of the three real
`.sql` files' actual `SELECT` list (independent of any assumption about
what it "should" contain), simulates the same column normalization a
real Oracle cursor + `normalize_column_name` would apply, and feeds a
DataFrame built from those exact column names into each `prepare_*`
function — reproducing this exact failure mode locally, without Oracle,
before the next real-data run.

## Open questions

- `archive.award_version.basis_of_payment_code`/`method_of_payment_code`
  remain uncaptured — a real, small, well-understood gap (two scalar
  columns + two tiny lookup tables, exactly analogous to
  `status_code`/`status_description`), deliberately deferred because
  fixing it requires a TRUNCATE-path change this work is scoped not to
  make. A natural, minimal follow-on: add the two columns via an
  additive migration, extend `01_award_versions.sql`'s existing
  `LEFT JOIN` block, and update both `prepare_versions` and the
  TRUNCATE-based full load's column list together, in one dedicated
  pass.
- `SPONSOR_TERM`/`SPONSOR_TERM_TYPE`, `REPORT_CLASS`, `REPORT`,
  `FREQUENCY`, `FREQUENCY_BASE`, `DISTRIBUTION` lookup descriptions are
  not joined in — same deferred-verification status as
  `custom_attribute_id`/`inv_credit_type_code`, not resolved here.
- Same deletion/reconciliation and ID-reuse open questions already
  recorded in `AWARD_IMPLEMENTATION_ROADMAP.md` apply equally here.

## Decisions

- `AWARD_BASIS_OF_PAYMENT`/`AWARD_METHOD_OF_PAYMENT` are excluded from
  this implementation: they are not child tables, they're lookup tables
  for two scalar `AWARD`-level codes, and capturing those codes would
  require a TRUNCATE-path change (`01_award_versions.sql`,
  `prepare_versions`, and the full load's column list) that this work
  is explicitly scoped not to make. Recorded as a deferred follow-on,
  not silently dropped.
- Template Terms (`AWARD_TEMPLATE_TERMS`/`AWARD_TEMPLATE_REPORT_TERMS`/
  `AWARD_TEMPLATE_REPORT_TERM_RECIPIENT`) are excluded: confirmed via
  direct class-descriptor inspection that all three key off
  `AWARD_TEMPLATE_CODE`, never a real Award's `AWARD_ID`/`AWARD_NUMBER`.
- `award_report_term_recipient` upserts strictly after
  `award_report_term` within the same family-load transaction, to
  satisfy its FK even when the parent term row is being inserted for
  the first time in this very same load — the same pattern already
  proven for `award_person_unit_credit_split`/`award_person_unit`.
- `sponsor_term_id`/`report_class_code`/`report_code`/`frequency_code`/
  `frequency_base_code`/`osp_distribution_code`/`contact_type_code`/
  `rolodex_id` all stay bare, unjoined values — consistent with, not a
  re-litigation of, the existing unverified-lookup convention.

## Recommended implementation order

1. ~~Design: object graph, Oracle PK/FK mappings, archive coverage,
   migration, UPSERT keys, load order, batch behavior, deletion
   strategy, family-widening behavior, test plan, smoke-test plan~~ —
   done.
2. ~~Migration (`V040`), verified against a throwaway database~~ — done.
3. ~~Oracle extraction SQL (two flat, one joined for the recipient
   grandchild)~~ — done.
4. ~~`prepare_sponsor_terms`/`prepare_report_terms`/
   `prepare_report_term_recipients`,
   `upsert_award_sponsor_term`/`upsert_award_report_term`/
   `upsert_award_report_term_recipient`~~ — done.
5. ~~Extend `_run_load_award_id`/`_run_load_award_batch`~~ — done.
6. ~~Tests + full validation (`pytest` 505 passed, `ruff` clean, `mypy`
   clean)~~ — done.
7. Local real-data smoke test (dry-run, real load, rerun, verify
   `unchanged` on every new table) — dry run found and required fixing
   the `10_award_report_terms.sql` column-alias bug (see Findings
   above); dry run not yet re-verified against real Oracle from this
   session (no Oracle/RDS connectivity available here — see the
   response to the bug report for what was actually run: the mocked
   local test suite, not the real dry run itself).
8. Next Tier 1 subsystem per `AWARD_DOMAIN_DECOMPOSITION.md` (Award
   Contacts, Award Attachments/Notepad, Award Reporting, or Award
   Subaward Summary), or the deferred `basis_of_payment_code`/
   `method_of_payment_code` follow-on.

## Date last updated

2026-07-31 (design and implementation complete; real-data dry run found
and fixed a `10_award_report_terms.sql` column-alias bug, see Findings;
re-verification against real Oracle not yet run from this session).
