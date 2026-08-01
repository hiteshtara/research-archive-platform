# SAP Award Transmission — Session Summary

A clean recap of a research thread that closed out two open items on the
Award domain's completeness checklist and produced a verdict on whether
SAP transmission history can be reconstructed from the archive as it
stands. **§3's proposal has since been implemented** — see
`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md` for the full record.
Everything below is retained as originally written (including §3, still
describing what was *proposed*) with a status note added at the top of
each section that has since moved forward; it consolidates findings
written up in full elsewhere in this repo
(`SAP_AWARD_TRANSMISSION_ASSESSMENT.md`, `AWARD_COMPLETENESS_REPORT.md`,
`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`).

## 1. Closed: `BUDGET_PERSONS.PERSON_NAME`, not `FULL_NAME`

A validation query against real BU Oracle for `BUDGET_PERSONS` initially
used `FULL_NAME` — by analogy with `AWARD_PERSONS`/`AWARD_SPONSOR_CONTACTS`/
`AWARD_UNIT_CONTACTS`, which all use that column name. `BUDGET_PERSONS`
doesn't follow that convention; its real column, OJB field-descriptor, and
JPA entity all agree on `PERSON_NAME`. That query — `SELECT FULL_NAME FROM
BUDGET_PERSONS` — fails with `ORA-00904: "FULL_NAME": invalid identifier`.

The good news: the actual ETL code was never wrong. Extraction SQL
(`sql/extract/award/45_award_budget_person.sql`), the migration
(`V051__create_award_budget_person_and_transferring_sponsor.sql`), and
every test already used `PERSON_NAME`/`person_name` consistently — the
mistake existed only in a hand-written validation query, not in shipped
code. `AWARD_COMPLETENESS_REPORT.md` now carries this as a standing
regression note (with the corrected query) so it isn't rediscovered the
same way twice. No code change was needed.

## 2. SAP Award Transmission research: verdict

**Question**: can SAP transmission history be fully reconstructed from
data the Award archive already has, or does it need its own archive
tables?

**Verdict: partially reconstructable — not enough. It needs its own
archive subsystem.**

The research read BU's real integration code in full
(`SapIntegrationServiceImpl.java`, 2,769 lines, plus every supporting
class, OJB mapping, and the user-facing JSP tag that displays transmission
history) — no SAP call, no AWS/ECS/Terraform action, no BU dev RDS access,
nothing committed. Full detail lives in
[`SAP_AWARD_TRANSMISSION_ASSESSMENT.md`](SAP_AWARD_TRANSMISSION_ASSESSMENT.md);
this section is the condensed version.

**What's already covered.** Nearly everything SAP transmission reads as
*input* — Award, Award Hierarchy, Award Amount Info, Time and Money, Cost
Share, People/Units/Credit Splits, Sponsor/Unit Contacts, Sponsor Terms,
Report Terms, Payment Schedule, Approved Subawards, and every Award
Extension custom field — is already archived, confirmed field by field in
the assessment's mapping table.

**What isn't reconstructable, and would require `archive.award_transmission`
/ `archive.award_transmission_child`:**

- The exact raw sent and received XML payloads. These are stored verbatim
  on `AwardTransmission.sentData`/`.returnedData` and are genuinely shown
  to BU staff today (a side-by-side Sent/Received XML view on the Award
  "Actions" tab) — not internal debug data.
- Transmission status, timestamp, and initiating/transmitting user. Both
  successes and failures are preserved; every retransmission creates a new
  row, nothing is overwritten.
- The F&A rate basis actually used per hierarchy child
  (`overhead_key`/`base_code`/`off_campus`). This is the strongest single
  finding: in the common case, the live system pulls these forward from
  the *prior* transmission's own child row rather than recomputing from
  current Budget data. Once a budget moves past "to be posted," this value
  becomes permanently unrecoverable without the transmission-child history
  itself.
- The exact transformed output values (BU-specific code-conversion logic
  for billing rules, letter-of-credit codes, grant types, and similar).
  The inputs are archived; reproducing the exact historical output would
  require re-running that exact logic against the parameter values in
  effect at the time.

**Classification summary** (full detail in the assessment doc):

| Category | Examples |
|---|---|
| `ARCHIVE_REQUIRED` | `AwardTransmission`, `AwardTransmissionChild` |
| `RECONSTRUCTABLE_FROM_CORE_AWARD_DATA` | Every already-archived Award/Budget/People/Terms/Contacts/Extension table used as a transmission input — reconstructable as *inputs* only, not as exact transmitted output |
| `OPERATIONAL_ONLY` | The SAP service classes, JAXB wire-format types, SOAP structures — none persist independently |
| `CONFIGURATION_ONLY` | `sapService.*` Rice config properties, `SAP_TIMEOUT_PARM` |
| `LOOKUP_ONLY` | Code lookups referenced indirectly through conversion methods; shared Sponsor/Rolodex/Organization/Unit master data |

**One incidental finding worth tracking separately**: `BUDGET_RATE_AND_BASE`,
a real, not-yet-archived Budget table that feeds this same F&A rate
calculation. Flagged as an open question, not scoped into this pass — see
§4 below.

Both `KUALI_ARCHIVE_COVERAGE.md` and `AWARD_COMPLETENESS_REPORT.md` already
record SAP transmission as a separate integration-history subsystem that
does **not** block the core Award domain's existing completeness
declaration.

## 3. Proposed next step (now implemented): SAP Award Transmission History archive

**Status: done.** Everything proposed below was carried out as
described, with one deliberate change from the original plan: rather
than the transmission-child table being "FK'd to the parent
transmission," `transmission_id` was implemented as a bare, unenforced
column with no Postgres FK — the parent transmission's root Award and a
hierarchy child routinely belong to different `award_number` families,
and this project's incremental per-award loading can't guarantee the
parent family loads first. See
`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md` for the full implementation
record, including this and every other decision made along the way. The
plan as originally written follows, unchanged:

**Scope**: `AWARD_TRANSMISSION` and `AWARD_TRANSMISSION_CHILD` only. Does
not rebuild, call, or test the live SAP integration.

**Before writing a migration**: confirm real Oracle DDL for both tables
against real BU Oracle (currently unconfirmed — the assessment's column
list is OJB-mapping-only). Also confirm, from real data, how often
`AwardServiceImpl.updateTransmissionHistory`'s `AWARD_ID` reassignment
occurs, since a naive `WHERE AWARD_ID IN (...)` extraction filter may not
correctly capture a transmission's *originally* live Award version for
every historical row.

**Archive model**:
- `archive.award_transmission` — one row per transmission attempt, keyed
  by Oracle's real surrogate `TRANSMISSION_ID`, storing `sent_data`/
  `returned_data` as PostgreSQL `TEXT` verbatim (no parsing, normalizing,
  or redacting), plus `success_indicator`, `transmission_date`,
  `initiator_id`, `transmitter_id`, and a snapshot of the primary-Award
  fields captured at transmission time.
- `archive.award_transmission_child` — one row per hierarchy-child
  included in a transmission, FK'd to the parent transmission, capturing
  `parent_document_number`/`child_document_number`/`lead_unit_number`/
  `child_type`/`award_number`/`overhead_key`/`base_code`/`off_campus`.
- Every transmission attempt preserved as immutable history — no
  collapsing, deduplicating, or overwriting retransmissions.

**Loader behavior**: standard `--load-award-id`/`--load-batch` wiring,
following this project's established FK-safe two-level pattern (parent
inserted, then children, one Postgres transaction per batch) — one Oracle
read per new source per batch, bind-variable filtering, Oracle
1,000-value chunking, dry-run rollback, idempotent UPSERT.

**Tests**: the project's standard suite for a new bundle — SQL column
contracts, insert/update/unchanged, multiple retransmissions preserved as
separate rows, raw XML round-trip without alteration, child-row FK
behavior, F&A fields preserved exactly, dry-run rollback, unrelated-Award
isolation, batch propagation, one-read-per-table, idempotent rerun,
full-batch rollback — plus a dedicated test proving the parent/child
relationship survives the `AWARD_ID`-reassignment quirk without producing
duplicate or orphaned rows.

**Documentation**: a new `SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`, with
updates to `SAP_AWARD_TRANSMISSION_ASSESSMENT.md`,
`KUALI_ARCHIVE_COVERAGE.md`, `AWARD_COMPLETENESS_REPORT.md`, and
`AWARD_IMPLEMENTATION_ROADMAP.md` — keeping SAP transmission history
classified separately from the complete core Award domain throughout.

**Validation**: `cd etl && uv run pytest && uv run ruff check . && uv run
mypy .` — no SAP, AWS, ECS, Terraform, or BU dev RDS access at any point;
no commit or push until the person driving this explicitly asks for one.

## 4. Open item to keep on the list

`BUDGET_RATE_AND_BASE` should be evaluated as its own pass, ideally right
after SAP transmission history if that work goes ahead — it directly feeds
the same F&A rate calculation this assessment found couldn't always be
reconstructed from `archive.award_budget`, so it may turn out to be
`ARCHIVE_REQUIRED` rather than merely reconstructable. Don't let this drop
out of the backlog.

## Where the full detail lives

- [`SAP_AWARD_TRANSMISSION_ASSESSMENT.md`](SAP_AWARD_TRANSMISSION_ASSESSMENT.md) — the complete research pass: object graph, Oracle table inventory, Java class inventory, transmission lifecycle, full field-level mapping table, classification, open questions.
- [`AWARD_COMPLETENESS_REPORT.md`](AWARD_COMPLETENESS_REPORT.md) — the `BUDGET_PERSONS`/`AwardTransferringSponsor` closure and the `PERSON_NAME` regression note.
- [`KUALI_ARCHIVE_COVERAGE.md`](KUALI_ARCHIVE_COVERAGE.md) — where SAP transmission is recorded as a separate, not-yet-decided subsystem.

## Status

Research complete for both items above, and §3's SAP Award Transmission
History archive has since been fully implemented — migration, extraction
SQL, loader wiring, and a full test suite, including a dedicated test
proving the `AWARD_ID`-reassignment quirk updates a transmission row in
place rather than duplicating it (see
`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`). `BUDGET_RATE_AND_BASE`
evaluation (§4) has still not started — flagged in
`AWARD_COMPLETENESS_REPORT.md`'s "Open item: BUDGET_RATE_AND_BASE" as the
next piece of work, and must not drop out of tracking.
