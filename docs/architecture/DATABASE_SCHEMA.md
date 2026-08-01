# Database Schema Reference

![Database loaders to schema diagram](DATABASE_LOADERS_DIAGRAM.svg)

A map of what's actually in `database/migrations/` (V001–V052), organized
by domain. This is the "what tables exist and how do they relate" doc; for
*why* a table is shaped the way it is, see the per-domain `*_DESIGN.md` /
`*_ARCHIVE_COVERAGE.md` docs in this directory, and
[`docs/DECISIONS.md`](../DECISIONS.md) for schema decisions that were later
reversed (Protocol Archive, unit-contact removal, CSV retirement).

## How migrations apply

All tables live in a single Postgres schema, `archive`, created in
`V001__create_archive_schema.sql` (which also enables `pg_trgm` for
trigram/fuzzy-search indexes). Files follow Flyway's `V###__description.sql`
naming convention, but **Spring Boot's Flyway integration is disabled**
(`spring.flyway.enabled: false`). Migrations are applied by the Python ETL
(`etl/archive_etl/upload/migrations.py`), which tracks applied versions in
`public.schema_migration` and runs as part of the `load_*` scripts — not by
`mvn spring-boot:run`. If you add a migration, it only takes effect the next
time an ETL loader runs.

Two migrations are forward-only removals, not additions:
`V032__drop_protocol_archive.sql` drops everything V021–V029/V031 created,
and `V033__drop_award_unit_contact_and_proposal_person.sql` drops
`award_unit_contact` and `proposal_person`. Both were later recreated with a
corrected shape (Protocol Archive in V034; `award_unit_contact` in V041) —
see [`DECISIONS.md`](../DECISIONS.md) for why.

## Cross-cutting infrastructure

These aren't tied to one domain — every loader writes through them.

- **`load_run`** (V002) — one row per ETL run: domain, source file/S3
  location, row counts (read/staged/loaded/rejected), status, and a JSONB
  validation report. Nearly every other table has a nullable `load_id`
  pointing back here for provenance.
- **`load_rejection`** (V002) — rows a loader refused to load, with the
  rejection reason and the raw rejected record as JSONB.
- **`etl_batch` / `etl_batch_item`** (V037) — a generic, domain-tagged
  "select N entities, then process them" manifest (used by the Award
  attachment physical-file loader). Batch membership is immutable once
  created; `etl_batch_item.status` tracks per-item load/process progress.

## Domain: IRB (`research_record`, `irb_*`)

IRB is the legacy, self-contained human-subjects domain — not part of the
Proposal→Award chain. `research_record` (V003) is a generic parent row
(record_id, record_type, title, active_flag) that both `irb_protocol` and
other record types hang off of. On top of that:

- **`irb_protocol`** (V004) — current-state protocol record, 1:1 with
  `research_record` via shared `record_id` PK.
- **`irb_protocol_version` / `irb_submission` / `irb_funding_source` /
  `irb_timeline_event`** (V007) — the *historical* composite: every
  archived version of a protocol, plus its submissions, funding sources,
  and timeline events, each keyed off `protocol_id`. V008 adds staging
  variants of the same four tables for the load pipeline.

Dashboard labels **Funding Relationships** / **Submissions** / **Timeline
Events** are row counts from `irb_funding_source` / `irb_submission` /
`irb_timeline_event` respectively — see CLAUDE.md's grain rules before
changing what these count.

## Domain: Award

The largest domain by table count (~45 tables across V011–V052) and the
reference implementation new domains should mirror. Everything hangs off
**`award_version`** (V011): PK `award_id`, unique on
`(award_number, sequence_number)`, with `is_current_version` (V013) marking
the current row per `award_number`. V012 explicitly allows multiple rows to
share `(award_number, sequence_number)` when `award_id` differs — don't
"fix" that as a data-quality issue; it's a legitimate historical case. Award
business grain is `COUNT(DISTINCT award_number)`; historical grain
(`Historical Award Records`) is `COUNT(*)` on `award_version`.

Child tables all reference `award_version(award_id)`, most `ON DELETE
CASCADE`. Grouped by what they cover:

| Group | Tables |
|---|---|
| Amounts / funding | `award_amount_info` (V011, extended V048), `award_funding_proposal` (V011, links to Proposal), `award_amount_transaction`, `award_direct_fanda_distribution` (V049) |
| People | `award_person` (V011), `award_person_unit`, `award_person_credit_split`, `award_person_unit_credit_split` (V039), `award_sponsor_contact`, `award_unit_contact` (V041, recreated after V033 dropped the earlier version) |
| Terms / reporting | `award_sponsor_term`, `award_report_term`, `award_report_term_recipient` (V040), `award_closeout`, `award_payment_schedule`, `award_approved_subaward` (V043), `award_comment` (V045), `award_notepad` (V042) |
| Compliance / special approvals | `award_cfda`, `award_cost_share`, `award_fanda_rate`, `award_science_keyword`, `award_special_review`, `award_special_review_exemption`, `award_approved_equipment`, `award_approved_foreign_travel`, `award_subcontracting_budgeted_goals` (V044) |
| Extension / CGB | `award_extension`, `award_cgb` (V046) — both keyed directly on `award_id` as PK (1:1 with the award, not a child-row collection) |
| Custom data / hierarchy | `award_custom_data` (V038), `award_hierarchy` (V049) |
| Time & money / transactions | `time_and_money_document`, `pending_transaction`, `pending_transaction_extension`, `transaction_detail` (V049) |
| Budget | `award_budget`, `award_budget_period`, `award_budget_line_item`, `award_budget_line_item_calculated_amount`, `award_budget_personnel_detail`, `award_budget_personnel_calculated_amount`, `award_budget_period_summary_calculated_amount`, `award_budget_limit` (V050), `award_budget_person`, `award_transferring_sponsor` (V051) |
| SAP transmission | `award_transmission`, `award_transmission_child` (V052) |

`award_cgb` and `award_extension` (V046) and `award_subcontracting_budgeted_goals`
are worth noting because Oracle's own PK for a couple of these compliance
rows *is* `award_number` itself — no surrogate ID — which V044/V046 preserve
rather than inventing a synthetic one.

## Domain: Proposal

**`proposal_version`** (V015) is composite-keyed on
`(proposal_id, version_number)` — there is no single-column
`archive.proposal` table/view; don't assume one exists without checking
`information_schema`. `proposal_person` (V015, expanded with 8 added
columns in V016) and `proposal_award` (V015, unique on
`(proposal_id, award_id, award_number)`) hang off it. Per CLAUDE.md: don't
use `award_funding_proposal` row count as the Proposal count, and don't
assign meaning to ad-hoc profiling numbers without knowing the exact SQL.

## Domain: Negotiation

**`negotiation`** (V017) is the parent; `negotiation_activity`,
`negotiation_custom_data`, `negotiation_notification`, and
`negotiation_unassociated_detail` all reference `negotiation(negotiation_id)`.
Negotiation connects back to Proposal/Award as part of the conceptual chain
(Proposal → Award → Funding → Negotiation → Investigator).

## Domain: Subaward

**`subaward`** (V018) is the parent, with ten child tables all referencing
`subaward(subaward_id)`: `subaward_amount`, `subaward_contact`,
`subaward_custom_data`, `subaward_funding`, `subaward_attachment`,
`subaward_closeout`, `subaward_report` (PK is a `VARCHAR(100)` source ID,
not a surrogate), `subaward_notepad`, `subaward_notification`,
`subaward_template_info`. `subaward_attachment_archive` (V019) is a
separate table layered on top of `subaward_attachment`, unique on
`(s3_bucket, s3_key)`, for the archived binary itself.

## Domain: Protocol Archive (removed, then recreated)

Not to be confused with IRB. V021–V029/V031 built a first version of this
domain; V032 dropped it entirely (API, UI, ETL, and schema — see
[`DECISIONS.md`](../DECISIONS.md)); V034 recreated it with a corrected
parent-resolution approach. The live schema today (post-V034) is just
**`protocol_version`** (PK `protocol_id`, unique on
`(protocol_number, sequence_number, protocol_id)`), **`protocol_person`**,
and **`protocol_unit`**. If you extend this domain, read
`docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md` first: a child row's
`PROTOCOL_ID` does not reliably identify its business version (~15%
mismatch was found for Personnel in the original build), so parent
resolution goes through `PROTOCOL_NUMBER` + `SEQUENCE_NUMBER`, keeping the
original `PROTOCOL_ID` only as audit metadata (`source_protocol_id`
columns carry this note as inline `COMMENT ON COLUMN`s throughout
V034/V022–V029).

## Attachments

- **`attachment_object`** (V035, upload-status enum extended in V036) —
  one row per physical file in S3 (bucket/key), independent of which
  business record references it; multiple attachment references can point
  at the same physical file.
- **`award_attachment`** (V035) — links an Award to an `attachment_object`.
- **`archived_attachment`** (V020) — generic cross-module attachment
  archive, unique on `(module_code, source_attachment_id)` and on
  `(s3_bucket, s3_key)`.
- **`subaward_attachment_archive`** (V019) — the Subaward-specific
  equivalent, described above.

## Views

- **`v_dashboard_counts`** (V006) — total/active/inactive counts from
  `research_record`, grouped by `record_type`.
- **`v_irb_search`** (V006) — `irb_protocol` joined to `research_record`,
  the read model behind IRB search.
- **`v_global_search`** (V009, redefined in V010 to include historical
  versions) — cross-record search view aggregating
  `irb_protocol_version` fields (document numbers, CRC protocol numbers,
  keywords, historical PI emails/affiliations/types/statuses) via
  `STRING_AGG`.
- **`v_protocol_latest`** / **`v_protocol_family`** — Protocol Archive read
  views; dropped by V032 and not yet redefined post-V034 recreation (check
  `information_schema.views` before assuming they exist again).

Ad hoc reporting SQL (not migrations, not applied automatically) lives in
`sql/dashboard/` (`award_dashboard.sql`, `award_explorer.sql`,
`award_summary.sql`) and `sql/extract/award/` (Oracle-side extraction
queries feeding the ETL, one file per source table/concept).

## Business grain vs. historical grain

The one rule that applies across every domain in this schema (from
CLAUDE.md, repeated here because it's the most common way to get a dashboard
number wrong): **never treat a raw archive row count as a business-object
count.** Identify the business grain (the real-world entity count, e.g.
`COUNT(DISTINCT award_number)`) and the historical grain (every archived
version/row, e.g. `COUNT(*)` on `award_version`) separately, and don't
silently deduplicate valid historical rows to make counts "look right."
When a table name or bare `COUNT(*)` seems to answer a grain question,
verify against the migration and source mapping before trusting it.
