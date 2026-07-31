# Kuali → Oracle → Archive Mapping

## Purpose

Map every currently-extracted Kuali repository object to its Oracle
source table(s), parent/child keys, corresponding `archive.*` table, and
whether an incremental UPSERT primitive exists for it — a cross-domain
reference produced before scoping Phase 4 (Award incremental UPSERT), to
prevent that work from quietly expanding into a rewrite of Proposal,
Negotiation, Subaward, Protocol, or IRB.

## Scope

Every domain with an Oracle-direct ETL loader in this repository: Award
(including Award Attachment), Proposal, Negotiation, Subaward, Protocol
(rebuilt), and legacy IRB (a structurally different, non-Oracle-direct
case, included for completeness).

## Source material used

- `oracle/award/`, `oracle/negotiation/`, `oracle/protocol/`,
  `oracle/subaward/` (Oracle extraction SQL)
- `sql/extract/award/`, `sql/extract/proposal/` (structured-loader
  extraction SQL — a separate location from `oracle/`, holding the
  Award/Proposal *structured* extraction queries)
- `database/migrations/V011`–`V020`, `V034`, `V035` (archive schema)
- Every `etl/load_*.py` loader, grepped for `def upsert_` and `TRUNCATE`
  to determine incremental-vs-full-reload status
- `docs/DECISIONS.md`, `CLAUDE.md` (business-grain and removed-feature
  history)

## Assumptions

- "Archived" means a Postgres table exists and a loader populates it —
  it does not imply the mapping captures every Oracle column on that
  table (see `AWARD_DOMAIN_STUDY.md` for a much deeper per-column gap
  analysis, Award-only).
- IRB's legacy S3 Excel/Parquet pipeline is treated as out of scope for
  "incremental UPSERT" comparisons — it is not Oracle-direct at all, so
  the question doesn't apply the same way.

## Findings

**Incremental UPSERT exists for exactly two tables in the entire
archive** (at the time this mapping was produced): `attachment_object`
and `award_attachment`, both built under Award Attachment. *(Phase 4A,
completed after this mapping, added four more: `award_version`,
`award_amount_info`, `award_person`, `award_funding_proposal` — see
`AWARD_IMPLEMENTATION_ROADMAP.md`. This document's table below reflects
the state at the time of the original mapping; the "Incremental UPSERT?"
column has been annotated where Phase 4A has since changed the answer.)*

Every other table — Proposal, Negotiation, Subaward, Protocol — is loaded
by a full `TRUNCATE` + bulk-reload on every run, with zero per-row UPSERT
capability at the time of this mapping.

### Award

| Kuali repository object | Oracle table(s) | Parent key | Child key | Archive PostgreSQL table | Implemented? | Incremental UPSERT? |
|---|---|---|---|---|---|---|
| Award (version) | `KCOEUS.AWARD` | `award_id` | — | `archive.award_version` | yes | **yes (Phase 4A)** |
| Award amount / time-and-money | `KCOEUS.AWARD_AMOUNT_INFO` | `award_id` | `award_amount_info_id` | `archive.award_amount_info` | yes | **yes (Phase 4A)** |
| Award person | `KCOEUS.AWARD_PERSONS` | `award_id` | `award_person_id` | `archive.award_person` | yes | **yes (Phase 4A)** |
| Award funding proposal (link) | `KCOEUS.AWARD_FUNDING_PROPOSALS` | `award_id` | `award_funding_proposal_id` | `archive.award_funding_proposal` | yes | **yes (Phase 4A)** |
| Award unit / contact | no verified Oracle query | — | — | removed, V033 | n/a | n/a |
| Award attachment (reference) | `KCOEUS.AWARD_ATTACHMENT` | `award_id` | `file_id` | `archive.award_attachment` | yes | yes (`upsert_award_attachment`) |
| Award attachment physical file | `KCOEUS.ATTACHMENT_FILE` / `FILE_DATA` | `file_id` | — (deduplicated 1:1) | `archive.attachment_object` | yes | yes (`upsert_attachment_object`) |

### Proposal

| Kuali repository object | Oracle table(s) | Parent key | Child key | Archive PostgreSQL table | Implemented? | Incremental UPSERT? |
|---|---|---|---|---|---|---|
| Proposal (version) | `PROPOSAL` | `proposal_id` | — | `archive.proposal_version` | yes | no |
| Proposal ↔ Award (link) | `AWARD_FUNDING_PROPOSALS` | `proposal_id` | `award_id` | `archive.proposal_award` | yes | no |
| Proposal person | orphaned extract SQL, unused | — | — | removed, V033 | n/a | n/a |

### Negotiation

| Kuali repository object | Oracle table(s) | Parent key | Child key | Archive PostgreSQL table | Implemented? | Incremental UPSERT? |
|---|---|---|---|---|---|---|
| Negotiation | `KCOEUS.NEGOTIATION` | `negotiation_id` | — | `archive.negotiation` | yes | no |
| Negotiation activity | `KCOEUS.NEGOTIATION_ACTIVITY` | `negotiation_id` | `negotiation_activity_id` | `archive.negotiation_activity` | yes | no |
| Negotiation custom data | `KCOEUS.NEGOTIATION_CUSTOM_DATA` | `negotiation_id` | composite | `archive.negotiation_custom_data` | yes | no |
| Negotiation notification | `KCOEUS.NEGOTIATION_NOTIFICATION` | `negotiation_id` | composite | `archive.negotiation_notification` | yes | no |
| Negotiation unassociated detail | `KCOEUS.NEGOTIATION_UNASSOC_DETAIL` | `negotiation_id` | composite | `archive.negotiation_unassociated_detail` | yes | no |

### Subaward

| Kuali repository object | Oracle table(s) | Parent key | Child key | Archive PostgreSQL table | Implemented? | Incremental UPSERT? |
|---|---|---|---|---|---|---|
| Subaward | `KCOEUS.SUBAWARD` | `subaward_id` | — | `archive.subaward` | yes | no |
| Subaward amount | `KCOEUS.SUBAWARD_AMOUNT_INFO` | `subaward_id` | composite | `archive.subaward_amount` | yes | no |
| Subaward contact | `KCOEUS.SUBAWARD_CONTACT` | `subaward_id` | composite | `archive.subaward_contact` | yes | no |
| Subaward custom data | `KCOEUS.SUBAWARD_CUSTOM_DATA` | `subaward_id` | composite | `archive.subaward_custom_data` | yes | no |
| Subaward funding source | `KCOEUS.SUBAWARD_FUNDING_SOURCE` | `subaward_id` | composite | `archive.subaward_funding` | yes | no |
| Subaward attachment (reference) | `KCOEUS.SUBAWARD_ATTACHMENTS` | `subaward_id` | composite | `archive.subaward_attachment` | yes | no |
| Subaward attachment physical file | old attachments framework (CSV metadata + Oracle BLOB) | `subaward_id` | — | `archive.subaward_attachment_archive` | partial (old framework) | no |
| Subaward closeout | `KCOEUS.SUBAWARD_CLOSEOUT` | `subaward_id` | composite | `archive.subaward_closeout` | yes | no |
| Subaward report | `KCOEUS.SUBAWARD_REPORTS` | `subaward_id` | composite | `archive.subaward_report` | yes | no |
| Subaward notepad | `KCOEUS.SUBAWARD_NOTEPAD` | `subaward_id` | composite | `archive.subaward_notepad` | yes | no |
| Subaward notification | `KCOEUS.SUBAWARD_NOTIFICATION` | `subaward_id` | composite | `archive.subaward_notification` | yes | no |
| Subaward template info | `KCOEUS.SUBAWARD_TEMPLATE_INFO` | `subaward_id` | composite | `archive.subaward_template_info` | yes | no |

### Protocol (rebuilt)

Business key: `protocol_number + sequence_number`; `protocol_id` kept as
audit-only `source_protocol_id`.

| Kuali repository object | Oracle table(s) | Parent key | Child key | Archive PostgreSQL table | Implemented? | Incremental UPSERT? |
|---|---|---|---|---|---|---|
| Protocol (version) | `KCOEUS.PROTOCOL` | `protocol_number + sequence_number` | — | `archive.protocol_version` | yes | no |
| Protocol person | `KCOEUS.PROTOCOL_PERSONS` | `protocol_number + sequence_number` | `protocol_person_id` | `archive.protocol_person` | yes | no |
| Protocol unit | `KCOEUS.PROTOCOL_UNITS` | `protocol_person_id` | composite | `archive.protocol_unit` | yes | no |
| Protocol funding / research area / location / submission / action / amend-renewal (6 tables) | extraction SQL existed pre-V032, recoverable from git history | `protocol_number + sequence_number` | composite | dropped, V032 — not rebuilt | no | n/a |

### IRB (legacy)

Not Oracle-direct — loaded from an S3 Excel/Parquet export, a structurally
different pipeline.

| Kuali repository object | Oracle table(s) | Parent key | Child key | Archive PostgreSQL table | Implemented? | Incremental UPSERT? |
|---|---|---|---|---|---|---|
| IRB protocol / submission / funding / timeline | n/a — S3 export, not Oracle | `record_id` | — | `archive.irb_protocol_version` + 3 more | yes | n/a (different pipeline) |

## Open questions

- **Proposal**: should person data remain removed (V033)? No new
  evidence surfaced by this mapping changes that calculus.
- **Subaward**: its attachment architecture (old CSV-metadata + Oracle-BLOB
  hybrid framework) is unreconciled with the newer, Oracle-direct pattern
  Award Attachment now uses. Not addressed by this mapping or by Phase 4A.
- **Protocol**: 6 of 9 original child tables remain unrebuilt since V032.
  Their extraction SQL is recoverable from git history but has not been
  restored.

## Decisions

- Phase 4 (Award incremental UPSERT) stays strictly scoped to the four
  Award tables already implemented at the time of this mapping — this
  mapping's purpose was explicitly to prevent it from expanding into the
  other domains' full-reload gap.
- Other domains (Proposal/Negotiation/Subaward/Protocol) are addressed
  **separately**, each requiring its own UPSERT-primitive design pass
  before any of them can safely use the generic batch framework — see
  `ETL_BATCH_FRAMEWORK.md`'s open questions.

## Recommended implementation order

1. ~~Award incremental UPSERT (Phase 4A)~~ — done.
2. Proposal incremental UPSERT — next natural candidate; shares Award's
   `business_number + sequence_number` versioning shape, so Award's
   design (see `AWARD_IMPLEMENTATION_ROADMAP.md`) is a close template.
3. Negotiation, Subaward, Protocol incremental UPSERT — each requires its
   own domain study first (this mapping is not deep enough to design
   from directly — see the depth `AWARD_DOMAIN_STUDY.md` required for
   Award as the bar for what each of these would need).
4. Subaward attachment architecture reconciliation — independent of the
   above, not blocked by any of it.

## Date last updated

2026-07-31.
