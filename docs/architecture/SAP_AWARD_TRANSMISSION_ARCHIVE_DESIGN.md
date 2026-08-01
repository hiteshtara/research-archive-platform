# SAP Award Transmission History — Design and Implementation Record

## Purpose

Implement the separate SAP integration-history archive subsystem that
`SAP_AWARD_TRANSMISSION_ASSESSMENT.md` identified: the two real, persisted
BU-specific business objects that record every SAP transmission attempt —
`AwardTransmission`/`AWARD_TRANSMISSION` and
`AwardTransmissionChild`/`AWARD_TRANSMISSION_CHILD`. That assessment's
verdict was **partially reconstructable — not enough; it needs its own
archive subsystem**, because the exact raw SAP payloads, transmission
status/history, and the F&A rate basis actually used per hierarchy child
are not fully recoverable from any other archived Award table. This
document is the implementation record for that subsystem.

## Scope

Strictly `AWARD_TRANSMISSION` and `AWARD_TRANSMISSION_CHILD`. Does not
rebuild, call, invoke, or test the operational SAP integration
(`edu.bu.kuali.kra.award.sapintegration.*`) in any way — this is archive-
only ETL over data the live system already persisted. Does not touch
`BUDGET_RATE_AND_BASE`, which is a separate, explicitly tracked open item
(see `AWARD_COMPLETENESS_REPORT.md` and §"Open questions" below) — not
scoped into this bundle.

## Source material used

Everything already gathered and cited in
[`SAP_AWARD_TRANSMISSION_ASSESSMENT.md`](SAP_AWARD_TRANSMISSION_ASSESSMENT.md):
`SapIntegrationServiceImpl.java`, the `AwardTransmission`/
`AwardTransmissionChild` Java business objects, their OJB class-descriptor
in `repository-award.xml`, and `AwardServiceImpl.updateTransmissionHistory`.
As already recorded there, **no Oracle bootstrap DDL for either table was
found anywhere in the available BU Kuali checkout** — the OJB mapping is
the only confirmed source for column names and Java-level types. This is
the same "unconfirmed real Oracle PK/FK/width" situation already carried
for `AWARD_EXTENSION` in `AWARD_EXTENSION_CGB_DESIGN.md`.

## Findings carried forward from the assessment

- **`AWARD_ID` reassignment quirk**: `AwardServiceImpl.updateTransmissionHistory`
  UPDATEs an existing `AwardTransmission` row's `AWARD_ID` in place to
  point at a new Award version, rather than inserting a new row. A
  transmission row's `AWARD_ID` can therefore drift from the Award version
  genuinely live at transmission time. Archived as observed at extraction
  time — the same "capture what Oracle shows today" discipline used
  throughout this project, not resolved to some earlier "true" value.
- **F&A rate-basis lineage problem**: `AwardTransmissionChild.overheadKey`/
  `baseCode`/`offCampus` are frequently copied forward from the *prior*
  transmission's own child row (not recomputed from current Budget) once a
  budget moves past "to be posted." This is the strongest concrete
  justification for archiving these three columns specifically — once a
  budget moves past that point, that historical F&A basis value becomes
  permanently unrecoverable from any other archived table.
- **Cross-`award_number`-family child relationship**: `AWARD_TRANSMISSION_CHILD.AWARD_ID`
  is the CHILD Award, routinely a *different* `award_number` family than
  the parent transmission's own root `AWARD_ID`. This is architecturally
  different from every other two-level Award child relationship in this
  project (which stay within one `award_number` family and are read
  together in the same bounded call) — see "FK strategy" below for what
  this means for the schema.
- Raw XML payloads (`SENT_DATA`/`RETURNED_DATA`) are the actual historical
  SOAP request/response this table exists to preserve — never parsed,
  normalized, redacted, or regenerated.

## Archive model

| Oracle table | Archive table | UPSERT key |
|---|---|---|
| `AWARD_TRANSMISSION` | `archive.award_transmission` | `transmission_id` |
| `AWARD_TRANSMISSION_CHILD` | `archive.award_transmission_child` | `transmission_child_id` |

Both use Oracle's own real surrogate PK as the UPSERT conflict key —
critically, this is what makes "preserve every transmission attempt as
immutable history, never collapse/deduplicate/overwrite" fall out for free
from the UPSERT itself: a genuinely new attempt always has a fresh PK
(Oracle assigns one per real attempt) and therefore always inserts a new
row. The UPSERT only ever makes re-extracting the *same* already-archived
row idempotent; it can never merge two distinct real attempts together.

`archive.award_transmission` columns: `transmission_id` (PK), `award_id`
(FK to `archive.award_version`, `NOT NULL`), `award_number`,
`sequence_number` (denormalized via `JOIN AWARD` — the table has neither
column of its own), `initiator_id`, `transmitter_id`, `success_indicator`,
`transmission_date`, `sent_data`/`returned_data` (`TEXT`, unbounded, stored
verbatim), `basis_of_payment_code`, `account_type_code`, `sponsor_code`,
`method_of_payment_code`, `document_number`, plus the standard
`source_update_timestamp`/`source_update_user`/`source_version_number`/
`loaded_at`/`load_id` provenance columns.

`archive.award_transmission_child` columns: `transmission_child_id` (PK),
`transmission_id` (bare, unenforced — see below), `award_id` (FK to
`archive.award_version`, `NOT NULL`), `award_number` (a real bare column
on this table, selected as-is, not overridden by the join),
`sequence_number` (denormalized via `JOIN AWARD`), `parent_document_number`,
`child_document_number`, `lead_unit_number`, `child_type`, `overhead_key`,
`base_code`, `off_campus`, plus the same provenance columns.

### FK strategy for `award_transmission_child.transmission_id`

Kept as a **bare, unenforced column — no Postgres FK to
`archive.award_transmission`.** This project's own per-award incremental
loading (`--load-award-id`/`--load-batch`) cannot guarantee the parent
transmission's own root Award family has already been loaded before a
given child Award's family is loaded, since the two routinely belong to
different `award_number` families and are read independently (see
"Extraction/read strategy" below). A hard FK here would make the loader's
existing incremental-load model unsafe for this one relationship
specifically. This is the same bare-reference treatment already
established elsewhere in this project for other cross-`award_number`-family
references (e.g. `archive.award_hierarchy.parent_award_number`).

## Extraction SQL

`sql/extract/award/47_award_transmission.sql` and
`48_award_transmission_child.sql`. Both use the established
join-to-denormalize pattern (`JOIN AWARD a ON a.AWARD_ID = ...`) already
used for `AWARD_EXTENSION`/`AWARD_CGB`, since neither table carries a bare
`SEQUENCE_NUMBER` of its own (`award_transmission_child` does carry its own
bare `AWARD_NUMBER`, selected as-is rather than overridden by the join,
since the two must always agree by definition).

`TRANSMISSION_ID` is deliberately **not** joined back to
`AWARD_TRANSMISSION` in the child extraction — it is carried through as a
bare value, matching the bare-column FK strategy above.

### Extraction/read strategy

Both tables are read independently via
`read_award_children_matching_award_ids(family_award_ids)`, each filtered
on its **own** `AWARD_ID` column — not via a two-step "resolve parent
transmission_ids first, then filter children by that set" join. This means
loading a root Award's family archives its own `award_transmission` rows
(full payload included), while loading a child Award's family
independently archives its own `award_transmission_child` rows for that
same transmission. The two halves reach eventual (not immediate) cross-
family consistency across separate incremental loads — accepted as
consistent with how every other cross-`award_number` relationship in this
project already behaves, and the only option that preserves "one Oracle
read per new source per batch" without requiring both halves to load
together.

## Load order

No FK relationship to any other table in this or any prior bundle beyond
`award_version` itself (and, deliberately, none between the two tables in
this bundle — see FK strategy above). Upserted after
`award_transferring_sponsor` (the prior bundle's last table) and before
`mark_load_complete`, in both `_run_load_award_id` and
`_run_load_award_batch` — transmission then transmission-child, an
arbitrary but stable choice, matching each table's own dependency-free
insert order.

## Reconciliation strategy

Deferred, identically to every other Award child table archived so far —
whatever Oracle returns on the next load overwrites/adds via UPSERT; no
independent deletion/reconciliation logic.

## Open questions

- **No Oracle bootstrap DDL confirmed for either table** — real
  Oracle-level PK/FK constraints, exact column widths (particularly
  `SENT_DATA`/`RETURNED_DATA`; OJB declares `VARCHAR`, implausible for a
  full SOAP XML payload), and `NOT NULL` constraints are unconfirmed. Same
  situation as `AWARD_EXTENSION`. Whoever next has BU Oracle/VPN access
  should confirm this against real DDL before trusting it further.
- **How often does the `AWARD_ID` reassignment
  (`AwardServiceImpl.updateTransmissionHistory`) actually occur** in real
  data — not answerable without real BU Oracle access.
- **`BUDGET_RATE_AND_BASE` remains unevaluated and unarchived.** Flagged by
  the user explicitly as the next piece of work after this bundle: it
  feeds the same historical F&A rate calculation this bundle's
  `overhead_key`/`base_code`/`off_campus` findings describe, and may turn
  out to be `ARCHIVE_REQUIRED` rather than merely reconstructable. This
  finding must not be dropped from tracking — see
  `AWARD_COMPLETENESS_REPORT.md` and `SAP_AWARD_TRANSMISSION_ASSESSMENT.md`,
  both of which also carry it.

## Decisions

- Both tables are archived using Oracle's own real surrogate PK
  (`transmission_id`/`transmission_child_id`) as the Postgres UPSERT key —
  this is what makes immutable-history preservation fall out of the
  UPSERT semantics themselves, with no special-casing required.
- `award_transmission_child.transmission_id` is a bare, unenforced column
  (no Postgres FK) — the cross-`award_number`-family relationship makes a
  hard FK unsafe under this project's incremental-load model.
- Both tables are read independently, each filtered on its own `AWARD_ID`
  column, rather than resolving the parent-child relationship at
  extraction time — preserves "one Oracle read per new source per batch"
  at the cost of eventual (not immediate) cross-family consistency.
- `sent_data`/`returned_data` are stored as unbounded Postgres `TEXT`,
  compared and written byte-for-byte, never parsed/normalized/redacted/
  regenerated — the raw historical payload this table exists to preserve.
- `overhead_key`/`base_code`/`off_campus` are stored and compared exactly
  as extracted, with no special handling — the whole point of archiving
  this table is that these three values are not otherwise reconstructable.

## Recommended implementation order

1. ~~`V052__create_award_sap_transmission_history.sql`~~ — done, verified
   against a throwaway database.
2. ~~`sql/extract/award/47_award_transmission.sql`,
   `48_award_transmission_child.sql`~~ — done.
3. ~~`prepare_award_transmission`/`prepare_award_transmission_child`,
   `upsert_award_transmission`/`upsert_award_transmission_child`~~ — done.
4. ~~Wire into `_run_load_award_id` and `_run_load_award_batch`~~ (report
   dict counters, reads, upsert loops, docstrings, log lines, CLI help
   text, table-count comments) — done.
5. ~~Tests~~ — done: SQL column contract, insert/update/unchanged,
   multiple retransmissions preserved as separate rows, raw XML round-trip
   (including special characters/unicode), F&A field fidelity across
   insert/update, bare `transmission_id` persisted without FK enforcement,
   dry-run rollback, unrelated-Award isolation, batch propagation,
   one-Oracle-read-per-table, idempotent rerun, full-batch rollback.
6. ~~Validation (`pytest`/`ruff`/`mypy`)~~ — done, all passing.
7. Documentation cross-references (`SAP_AWARD_TRANSMISSION_ASSESSMENT.md`,
   `KUALI_ARCHIVE_COVERAGE.md`, `AWARD_COMPLETENESS_REPORT.md`,
   `AWARD_IMPLEMENTATION_ROADMAP.md`, `SAP_TRANSMISSION_SESSION_SUMMARY.md`)
   — done, this same pass.

## Real-data smoke-test plan

Not run — this environment has no BU Oracle/VPN access, consistent with
every prior bundle in this project. When run from a BU-VPN-connected
environment with real Oracle and local Postgres access:

1. Confirm real Oracle DDL for both tables (PK/FK/column widths/NOT NULL)
   against `information_schema`/`DBA_TAB_COLUMNS`, resolving the Open
   Questions above.
2. Pick one real Award known to have at least one SAP transmission
   attempt (e.g. via `SELECT AWARD_ID FROM AWARD_TRANSMISSION WHERE
   ROWNUM <= 1`) and load it via `--load-award-id`, confirming
   `sent_data`/`returned_data` round-trip byte-for-byte against the real
   Oracle value.
3. If any Award in the sample has more than one transmission attempt,
   confirm every attempt (success and failure) is preserved as a separate
   row, not collapsed.
4. Reload the same Award twice via `--load-award-id`, confirming the
   second run reports `unchanged` for both tables.
5. Load a hierarchy Award family with real `AWARD_TRANSMISSION_CHILD` rows
   whose `AWARD_ID` differs from the parent transmission's root
   `AWARD_ID`, confirming both halves load correctly as independent
   incremental loads.
6. Batch-scale validation at the same 10/100/1000 sizes already used for
   the rest of the Award domain, confirming these two tables' one-read-
   per-batch behavior holds at scale.

## Date last updated

2026-08-01 (initial implementation).
