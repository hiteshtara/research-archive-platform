# Kuali Document Explorer — Design

Investigation and design only, per an explicit user request. No source
code, migrations, or indexes were created or changed. All queries below
are read-only, run against dev Postgres (2026-08-11 snapshot). This
document extends, and does not contradict,
[`KUALI_DOCUMENT_METRIC_INVESTIGATION.md`](KUALI_DOCUMENT_METRIC_INVESTIGATION.md)
(the already-implemented Dashboard/Document Search feature) — that
feature's five-module union count and simple filter set stay as-is;
this document designs a **richer, faceted** explorer on top of the same
four core modules (Award/Proposal/Negotiation/Subaward), with IRB
evaluated separately per the request and, based on the evidence below,
**excluded from the initial model**.

This is entirely deterministic SQL. No AI model, Bedrock, Titan
embedding, pgvector, or generated text appears anywhere in this design.

## 1. Verified module data sources

| Module | Document table | Document number | Business number | Native status | Unit source | Person source | Sponsor source |
| --- | --- | --- | --- | --- | --- | --- | --- |
| AWARD | `archive.award_version` | `workflow_document_number` | `award_number` + `sequence_number` | `status_description` | `lead_unit_number`/`lead_unit_name` (1) + `archive.award_person_unit` (many) | `archive.award_person` (many, real roles) | `sponsor_code`/`sponsor_name` (on row) |
| PROPOSAL | `archive.proposal_version` | `document_number` | `proposal_number` + `version_number` | `status_description` | `lead_unit_number`/`lead_unit_name` (1, no multi-unit table) | `principal_investigator_id`/`principal_investigator_name` (1 slot only — no CO-PI/key-person table; see §4) | `sponsor_code`/`sponsor_name` (on row) |
| NEGOTIATION | `archive.negotiation` | `document_number` | `negotiation_id` | `negotiation_status_description` | None on the main row — see §3's bifurcation finding | `negotiator_full_name`/`negotiator_person_id` (1, on row — a **negotiator**, never a PI) | None on the main row — see §3 |
| SUBAWARD | `archive.subaward` | `document_number` | `subaward_code` + `sequence_number` | `status_description` | `requisitioner_unit` (1, on row) | `site_investigator` (1, on row, 97.3% populated) + `archive.subaward_contact` (many, real roles) | `award_sponsor_name` (on row, only 5.3% populated — see §3) |

Existing UI routes (all already live, from the just-shipped Document
Search feature): `/awards/{award_id}`, `/proposals/{proposal_number}`,
`/negotiations/{negotiation_id}`, `/subawards/{subaward_id}`.

## 2. Status normalization

Real native-status inventories, full counts, dev snapshot:

**AWARD** (`archive.award_version.status_description`, 49,827 rows, 10 distinct):

| Native status | Count |
| --- | ---: |
| Approved Award | 30,920 |
| PAFO/OSP (Closing) | 8,362 |
| Closed | 7,944 |
| Pre-Close | 1,578 |
| Pre-Award Not Billable | 694 |
| Compliance Hold | 145 |
| Cancelled | 108 |
| Pre-Award Billable | 34 |
| Spending Hold | 23 |
| Do Not Use This Status | 19 |

**PROPOSAL** (`archive.proposal_version.status_description`, 17,739 rows, 7 distinct):

| Native status | Count |
| --- | ---: |
| Pending | 12,319 |
| Not Funded | 3,420 |
| Funded | 1,898 |
| Withdrawn | 49 |
| Deactivated Record | 46 |
| Pending-Revised | 7 |

**NEGOTIATION** (`archive.negotiation.negotiation_status_description`, 10,775 rows, 9 distinct):

| Native status | Count |
| --- | ---: |
| Fully Executed | 9,858 |
| Abandoned | 827 |
| In Negotiation | 51 |
| Signature In Process | 16 |
| Under Review | 16 |
| On Hold | 5 |
| Request Acknowledged/Docs Req. | 1 |
| Limited Issues | 1 |

**SUBAWARD** (`archive.subaward.status_description`, 513 rows, 8 distinct):

| Native status | Count |
| --- | ---: |
| 07. Executed | 255 |
| 05. Sent to subrecipient | 70 |
| 03. Pending new or updated FRN | 55 |
| 02. Subaward RA initial review | 53 |
| 01. RA Review | 46 |
| 04. PI/DA | 18 |
| 10. Cancelled | 14 |
| 08. Closed | 2 |

### Proposed mapping (confident cases only)

| Module | Native status | Normalized status | Confidence |
| --- | --- | --- | --- |
| Award | Approved Award | ACTIVE | High |
| Award | Closed | CLOSED | High |
| Award | Cancelled | CANCELLED | High |
| Proposal | Pending, Pending-Revised | PENDING | High |
| Proposal | Funded | ACTIVE | High — a Funded proposal has become an Award; still worth surfacing as its own record |
| Proposal | Not Funded, Withdrawn, Deactivated Record | ARCHIVED | High — terminal, non-active proposal states |
| Negotiation | Fully Executed | ACTIVE | High |
| Negotiation | Abandoned | CANCELLED | High |
| Negotiation | In Negotiation, Signature In Process, Under Review, Request Acknowledged/Docs Req. | PENDING | High |
| Subaward | 07. Executed | ACTIVE | High |
| Subaward | 10. Cancelled | CANCELLED | High |
| Subaward | 08. Closed | CLOSED | High |
| Subaward | 01–05 (RA Review through Sent to subrecipient) | PENDING | High |

### Unresolved — mapped to UNKNOWN pending business review

**Correction (verified against the live implementation and its dev
smoke test, superseding two earlier, less careful counts of "seven"
and then "eight"): there are nine distinct ambiguous native status
values, not seven or eight.** The original count of eight in this
section's first draft omitted Negotiation's **"Limited Issues"** (1
row) entirely — it always correctly fell through to `UNKNOWN` in the
implemented SQL `CASE` expression (verified live: `SELECT module,
native_status_description, COUNT(*) FROM documents WHERE
normalized_status='UNKNOWN' GROUP BY 1,2` against dev returned exactly
these nine rows), it was just never listed in this document's own
enumeration. The `UNKNOWN` mapping itself is unchanged by this
correction — only the documentation of which values produce it.

Per the explicit instruction not to guess: these are genuinely ambiguous
without a Kuali business-rules source, so they map to `UNKNOWN`, not a
guessed category, until reviewed. The complete, verified list (dev row
counts as of 2026-08-11):

- Award **"PAFO/OSP (Closing)"** (8,362 — the single largest ambiguous
  bucket) and **"Pre-Close"** (1,578): plausibly ACTIVE-still (not yet
  closed) or a fourth "CLOSING" state the requested taxonomy doesn't
  have a slot for.
- Award **"Compliance Hold"** (145) and **"Spending Hold"** (23): a
  hold is neither ordinary ACTIVE nor PENDING in the requested
  taxonomy's sense.
- Award **"Pre-Award Billable"** (34) / **"Pre-Award Not Billable"**
  (694): could be ACTIVE (an approved, current Award) or PENDING (award
  hasn't started spending). Both are real Kuali statuses, not typos.
- Award **"Do Not Use This Status"** (19): the status's own label warns
  against relying on its meaning.
- Negotiation **"On Hold"** (5): same ambiguity as Award's holds.
- Negotiation **"Limited Issues"** (1): no business-rules source found;
  never previously listed in this document, corrected here.

All normalized-status output preserves `normalized_status`,
`native_status_code`, and `native_status_description` together, per the
requirement — a client can always see the real underlying value even
when the normalized bucket is `UNKNOWN`.

## 3. Unit normalization

**Award**: `lead_unit_number`/`lead_unit_name` 100% populated
(49,827/49,827) directly on `award_version` — one lead unit per Award
version. Additionally, `archive.award_person_unit` (57,661 rows) gives
a real one-to-many **person-to-unit** assignment with its own
`lead_unit_flag`, richer than a single Award-level lead unit (a person
can be credited to a non-lead unit).

**Proposal**: `lead_unit_number`/`lead_unit_name` 100% populated
(17,739/17,739) directly on `proposal_version` — single value only, no
multi-unit table exists for Proposal.

**Subaward**: `requisitioner_unit` 100% populated (513/513) directly on
`subaward`.

**Negotiation — bifurcated, a real structural finding, not a data gap**:
the main `archive.negotiation` table has **no unit column at all**.
`negotiation_association_type_code` splits the 10,775 rows:

| Association type | Count | Unit/sponsor/PI source |
| --- | ---: | --- |
| NO (unassociated) | 8,533 | `archive.negotiation_unassociated_detail` (`lead_unit`, `sponsor_code`, `pi_name`, `pi_person_id`) — 8,554 negotiations have a matching detail row (small ~21-row discrepancy, unexplained, worth a follow-up query if this ships) |
| AWD (Award) | 2,223 | Resolve via `associated_document_id` joined to the associated Award's `lead_unit_number`/`sponsor_code` |
| SWD (Subaward) | 16 | Resolve via the associated Subaward |
| IP (Institutional Proposal) | 3 | Resolve via the associated Proposal |

**Recommended default**: **lead unit only** for the primary
`primary_unit_number`/`primary_unit_name` fields (matches what Award
and Proposal already store as their own single "lead unit" concept, so
the canonical model stays consistent across modules) — with **any
associated unit** (via `award_person_unit` for Award, or the
resolved-Award/Subaward/Proposal chain for Negotiation) available as a
**separate, explicit filter mode**, not the default. Recommending
lead-unit-only as the default keeps result counts predictable and
matches how a unit administrator would naturally ask "documents *for*
my unit" (lead-owned), while "any associated unit" stays available for
the rarer, broader question.

`archive.unit` (5,115 rows) is the shared reference table
(`unit_number` PK, `unit_name`, `parent_unit_number`) for resolving a
unit **name** when only a `unit_number` is on hand and the module's own
row doesn't already denormalize a name — Negotiation's
`negotiation_unassociated_detail.lead_unit` in particular has no paired
name column, so it must join `archive.unit` to display a name at all.

## 4. Person normalization

**Award** (`archive.award_person`, 57,023 rows): real, multi-role
relationship table.

| `contact_role_code` | Count |
| --- | ---: |
| PI | 49,854 |
| COI | 5,494 |
| KP | 1,640 |
| MPI | 35 |

`is_principal_investigator` = `contact_role_code IN ('PI', 'MPI')`.

**Proposal**: **no relationship table** — `archive.proposal_person` was
dropped in full (`V033__drop_award_unit_contact_and_proposal_person.sql`,
per `docs/DECISIONS.md`: "Proposal's people had no verified Oracle
extraction query... removed entirely"). What remains is exactly **one**
denormalized slot on `proposal_version` itself:
`principal_investigator_id`/`principal_investigator_name`, populated
99.4%/99.2% (17,632/17,739 and 17,599/17,739). This means Proposal
**cannot** support CO-PI/key-person search or a person-role facet the
way Award can — only a single-PI lookup. This is a real, current schema
limitation to state honestly in the API/UI, not paper over with an
invented multi-person model.

**Negotiation**: one denormalized slot on the main row,
`negotiator_full_name`/`negotiator_person_id`, 100% populated
(10,775/10,775). This is a **negotiator**, not a PI — the unassociated
negotiations' own `pi_name`/`pi_person_id` (in
`negotiation_unassociated_detail`) is a second, genuinely different
person relationship that must never be collapsed into "negotiator."

**Subaward**: `site_investigator` (a person ID) directly on the
`subaward` row, 97.3% populated (499/513) — the Subaward's PI-equivalent
role. Separately, `archive.subaward_contact` (real role table, resolved
descriptions via `V068`):

| `contact_type_code` | Description | Count |
| --- | --- | ---: |
| 34 | Administrative Contact | 506 |
| 35 | Financial Contact | 499 |
| 36 | Authorized Official | 328 |

### Canonical person relationship

```
person_id
full_name
role_code
role_description
is_principal_investigator
source_table
source_primary_key
```

`first_name`/`last_name`/`email` are **not recommended** for the
canonical model — none of the four modules' relevant tables carry them
split out (Award/Proposal/Negotiation only ever store a combined
`full_name`), and email is explicitly gated ("only if approved for
display") with no approval given in this request; omit rather than
fabricate a split or expose an unapproved field.

### Rolodex / non-employee people

`archive.rolodex` (~12.5K rows, external non-BU contacts) is
independently referenced by `subaward_contact.rolodex_id` and by
`negotiation_unassociated_detail.pi_rolodex_id` — a person relationship
row can point at **either** a BU `person_id` **or** a `rolodex_id`,
never both. The canonical model's `source_table`/`source_primary_key`
already distinguish these cleanly (e.g. `source_table = 'rolodex'` vs.
`source_table = 'award_person'`); no separate boolean flag is needed,
but the API contract (§6) must document that `person_id` may be a
Rolodex ID, not always a BU principal ID, so a client never assumes a
BU-affiliation lookup will succeed for every result.

## 5. Proposed canonical document model

Given the real per-module differences above (Negotiation's bifurcated
unit/sponsor source, Proposal's single-PI-only limitation, Subaward's
sparse own-sponsor column), the canonical **document** row itself
should carry only what is reliably available per-row without an
expensive join, with related units/people kept as genuinely separate
one-to-many result sets (never collapsed):

```
module
document_number
business_record_number
title
normalized_status
native_status_code            -- Award/Subaward have no native status
native_status_description        code column today (only description) -
version_number                   preserve NULL honestly, don't invent one
sequence_number
sponsor_code
sponsor_name
primary_unit_number
primary_unit_name
primary_person_id
primary_person_name
primary_person_role
created_date                  -- from source_update_timestamp where present
updated_date
effective_date                -- module-specific: begin_date (Award),
                                  initial_start_date (Proposal),
                                  negotiation_start_date (Negotiation),
                                  start_date (Subaward)
target_route
```

**Subaward's `sponsor_name`**: only 27/513 (5.3%) of `subaward` rows
have a populated `award_sponsor_name`. Recommend resolving Subaward's
sponsor via a join to its parent Award (Subaward's `document_number`'s
owning Award, following the same `subaward_funding`-style linkage
already used by `RELATED_SUBAWARD` evidence indexing) rather than
trusting the sparse denormalized column — flagged as a decision
requiring verification before implementation, not assumed safe to ship
as-is.

**Related units/people remain separate, one-to-many result sets** keyed
by `(module, document_number)` — never flattened into repeated document
rows (§7's "does not duplicate the same document" requirement).

## 6. Recommended database approach

| Approach | Coverage | Refresh | Migration risk | Maintainability |
| --- | --- | --- | --- | --- |
| Fixed repository `UNION ALL` (mirrors the just-shipped Document Search repository exactly) | Full, real-time | N/A — always current | None — no new schema object | Low — same pattern already proven in this codebase, same file to extend |
| Plain PostgreSQL view | Full, real-time | N/A | Low (`CREATE VIEW`) | Similar to above; adds one DB object to reason about, no code benefit over a repository constant |
| Materialized view | Full, but stale between refreshes | Needs a refresh job/schedule | Medium — new migration, new refresh mechanism, new staleness-window failure mode | Higher — must own refresh timing, and stale data directly contradicts this archive's read-only "always reflects the source" posture |
| Separate module queries combined in the Java service | Full, real-time | N/A | None | Higher per-query overhead (4-5 round trips instead of 1), loses the single stable `ORDER BY`/pagination the union gives for free |

**Recommendation: fixed repository `UNION ALL`**, the same design
already implemented and proven for Document Search
(`DocumentSearchRepository.java`) — extend that exact pattern (or a
sibling repository reusing its CTE) rather than introducing a new
database object. This directly satisfies "do not introduce a new
search service or external database." A materialized view is
explicitly not recommended: a stale count between refreshes would
contradict every existing grain rule in this codebase (`CLAUDE.md`'s
own "never treat a raw archive row count as..." guidance is about
exactly this kind of silent staleness risk).

## 7. Search API design

```
GET /api/v1/documents
```

| Filter | Behavior |
| --- | --- |
| `query` | Free-text substring across document number, title, business record number (mirrors `AwardSearchPattern`'s `*wildcard*`/substring convention) |
| `module` | Exact match against a fixed allowlist (`AWARD,PROPOSAL,NEGOTIATION,SUBAWARD`); **repeatable** for multi-select (`module=AWARD&module=PROPOSAL`) — bound as an `IN` list, never string-built |
| `normalizedStatus` | Exact match against the fixed 6-value enum, repeatable for multi-select |
| `nativeStatus` | Exact substring match against `native_status_description` (native codes differ in shape per module — text match is safer than an allowlist here) |
| `unitNumber` | Exact match; combined with `unitRole` |
| `unitRole` | `LEAD` (default) or `ANY` — see §3's recommendation |
| `personId` / `personName` | `personId` exact, `personName` substring |
| `personRole` | Free text against `role_description`, since role vocab differs per module (PI/COI/KP/MPI for Award vs. Administrative/Financial/Authorized Official for Subaward vs. Negotiator for Negotiation) |
| `sponsorCode` | Exact match |
| `dateFrom`/`dateTo` | Range against `effective_date` |
| `page`/`size` | 0-based page, 1-100 size, mirrors `PaginationSupport` |
| `sort` | Fixed allowlist only (`documentNumber`, `title`, `effectiveDate`, `module`) — **never** a raw column name from the client, exactly like the module filter |

**Exact document-number behavior**: identical to the shipped Document
Search endpoint — substring ILIKE naturally returns exactly the one
matching row for a full, real document number (proven empirically:
searching the exact string `430102` returns exactly one business
record, no collisions, since document numbers are confirmed globally
unique).

**Multiple modules/statuses**: bound as parameterized `= ANY(:list)`
(or `IN`), never string-concatenated — same safety posture as the
single-value module filter already shipped.

**PI-only vs. any-person**: a boolean `piOnly` filter maps to
`is_principal_investigator = TRUE` for Award (where the distinction is
real); for Proposal/Negotiation/Subaward, where there is only ever one
person slot, `piOnly=true` simply has no effect (that slot already *is*
the closest thing to a PI/negotiator/site-investigator) — document this
explicitly rather than silently ignoring the parameter.

**Invalid filters**: an unrecognized `module`/`normalizedStatus` value
is bound as an ordinary parameter and naturally matches zero rows
(same design decision as the shipped Document Search's module filter)
— never a 400, never silently widened.

**Authentication**: same global `/api/**` Cognito rule every other
route uses — no new wiring.

**SQL-injection protection**: every filter value bound via `.param(...)`;
`module`/`sort` are the only two fields that must additionally be
validated against a fixed allowlist **before** being trusted for
anything beyond an equality bind (`sort` in particular must never
become a raw `ORDER BY` column name built from client input — map it to
one of four fixed `ORDER BY` clauses in Java, never interpolate).

## 8. UI design

New `Kuali Documents` page (extending, not replacing, the already-shipped
`/documents` page) with:

- **Main search**: "Search by document number, record number, title,
  person or sponsor" — one text input, same substring semantics as
  today's shipped search.
- **Filters**: Module (multi-select chips), Common status (multi-select),
  Native status (free text, secondary/collapsed by default — most users
  will use Common status), Unit number + name (autocomplete against
  `archive.unit`), Person + Person role, PI-only toggle, Sponsor,
  date range.
- **Result card**: Module badge, document number, business-record
  number, title, common-status pill + native-status caption, unit,
  "Person — role" line, sponsor, version/sequence, date, Open Record
  action (reuses the shipped `targetRoute` pattern exactly).
- **Multiple people/units**: show the primary relationship plus a
  count, e.g. `Jane Smith — PI` / `+3 other people` — never duplicate a
  document row per associated person/unit. This requires the API to
  return `personCount`/`unitCount` alongside the primary fields (a
  small, additive extension to §5's canonical row).

## 9. Saved searches and presets

| Preset | Filter shape | Ready today? |
| --- | --- | --- |
| Active Awards | `module=AWARD&normalizedStatus=ACTIVE` | Yes |
| Pending Proposals | `module=PROPOSAL&normalizedStatus=PENDING` | Yes |
| Archived Subawards | `module=SUBAWARD&normalizedStatus=ARCHIVED` | Subaward has no row currently mapped to ARCHIVED in the confident table above — this preset would return 0 results until the ambiguous-status review in §2 resolves at least one Subaward status into ARCHIVED, or is redefined |
| Documents by PI | `piOnly=true&personName=...` | Yes, with the Proposal/Negotiation/Subaward caveat from §7 |
| Recently Updated | `sort=updatedDate` (needs `updated_date` populated — verify `source_update_timestamp` coverage before shipping this preset; not verified in this pass) | Needs verification |
| Negotiations in Progress | `module=NEGOTIATION&normalizedStatus=PENDING` | Yes |
| My Unit | Requires an authenticated-user → unit relationship | **Not implemented** — no such mapping exists in this archive today (Cognito auth carries identity, not a BU unit affiliation); explicitly not recommended per the instruction not to build this without a verified relationship |

## 10. Facet strategy

Facet counts (`Award: 49,827`, `Proposal: 17,739`, `Negotiation: 10,775`,
`Subaward: 513`) update per applied filter set. Recommend computing
facets as a **second, filtered `GROUP BY module` query against the same
CTE**, run alongside the paginated result query (two round trips, same
pattern as the shipped Document Search's `search()`/`count()` split) —
not a third denormalized count table, which would reintroduce exactly
the staleness risk §6 already rejected for materialized views.
Status/unit/person-role facets follow the same shape
(`GROUP BY normalized_status`, etc.) but should be **evaluated for cost
before shipping as live** — see §11's `Seq Scan` finding for the ILIKE
case, which a naive status facet could hit if not written as an exact
match.

## 11. Performance findings

Real `EXPLAIN` output, dev database:

| Query | Plan | Notes |
| --- | --- | --- |
| Exact document number (Award) | `Index Scan using ix_award_version_workflow_document_number` — cost 0.29..8.31 | Fast, already indexed |
| Active Awards (`status_description = 'Active'`) | `Index Scan using ix_award_version_status` — cost 0.29..7.54 | Fast, already indexed (index exists even though the literal value 'Active' doesn't match any real status — the plan still proves the index is used for exact equality) |
| Unit number (`lead_unit_number = '1202020000'`) | `Bitmap Index Scan using ix_award_version_lead_unit_number` — cost 16.63..1999.04 | Fast, already indexed |
| Person name substring (`full_name ILIKE '%Smith%'`) | `Bitmap Index Scan using ix_award_person_full_name_trgm` — cost 514.71..526.25 | Fast — a trigram (`gin_trgm_ops`) index already exists for this exact case |
| **Status substring (`status_description ILIKE '%Active%'`)** | **`Seq Scan on award_version` — cost 0.00..3135.99** | **No index used** — a substring/ILIKE search against status falls back to a full scan; exact-match status filtering (§7's `normalizedStatus`/`nativeStatus` design, which are exact-match or allowlist-bound, not free substring) avoids this, but a naive "status contains text" facet or filter would not |

## 12. Required indexes (not created — design only)

- `archive.proposal_version(lead_unit_number)` — no index currently confirmed; Award's equivalent exists (`ix_award_version_lead_unit_number`), Proposal's does not appear in the migrations searched. Verify before shipping unit filtering for Proposal.
- `archive.negotiation(negotiation_association_type_code)` — needed for the AWD/SWD/IP/NO split query in §3 to stay fast at scale (10,775 rows is small enough today that this may not be strictly necessary yet, but should be verified with `EXPLAIN` before shipping if Negotiation's unit/sponsor resolution is implemented).
- `archive.subaward_contact(subaward_id, contact_type_code)` — for person-role faceting on Subaward.
- A trigram index on `archive.proposal_version(principal_investigator_name)` and `archive.negotiation(negotiator_full_name)`, mirroring Award's existing `ix_award_person_full_name_trgm`, if person-name substring search is required for those modules (currently only Award has this).

## 13. Security and privacy rules

- Never expose S3 bucket/key, DB credentials, or stack traces (same
  standing rule as every other endpoint in this codebase).
- `email` is explicitly excluded from the canonical person model (§4) —
  no approval was given in this request to display it; omit rather than
  gate behind an unreviewed flag.
- Rolodex-sourced people (external, non-BU) must be labeled as such
  wherever `source_table = 'rolodex'`, not presented identically to a
  BU `person_id` result.
- `module`/`sort` allowlist validation (§7) is a security requirement,
  not just a correctness one — an unvalidated `sort` value is the one
  place in this design an attacker-controlled string could otherwise
  reach raw SQL structure.

## 14. Exact implementation files (not created this phase)

- `api/.../adapter/out/persistence/DocumentExplorerRepository.java` (new — richer sibling of `DocumentSearchRepository`, or an extension of it, TBD at implementation time)
- `api/.../application/document/DocumentExplorerService.java`, `StatusNormalizer.java` (new, houses the §2 mapping table as code, not a migration)
- `api/.../adapter/in/web/DocumentExplorerController.java`
- `api/.../adapter/in/web/dto/document/DocumentExplorerResultResponse.java`, `FacetCountResponse.java`
- `ui/src/pages/DocumentsPage.tsx` (extend, not replace)
- `ui/src/features/documents/documentsPresentation.mjs` (extend with status-normalization/facet helpers)

## 15. Test plan (for implementation time, not run this phase)

Mirrors the shipped Document Search test structure: SQL-lock repository
tests (union shape, fixed allowlists never string-built), service tests
(status normalization per real value in §2's table, unit lead-vs-any
behavior, PI-only no-op for single-slot modules, facet counts), and
controller tests (auth, invalid module/sort handling, pagination).
Every test uses the real values discovered in this investigation (e.g.
`"PAFO/OSP (Closing)"` → `UNKNOWN`), never invented statuses.

## 16. Unresolved business decisions (require your approval)

1. **Award "PAFO/OSP (Closing)" (8,362 rows) and "Pre-Close" (1,578
   rows)** — ACTIVE, CLOSED, or a status the requested 6-value taxonomy
   doesn't have room for?
2. **Award "Compliance Hold"/"Spending Hold" (168 rows combined)** — a
   real ON_HOLD-style state, or fold into PENDING/ACTIVE?
3. **Award "Pre-Award Billable"/"Pre-Award Not Billable" (728 rows
   combined)** — ACTIVE or PENDING?
4. **Unit filtering default**: this document recommends **lead unit
   only** as the default with "any unit" as an explicit opt-in — confirm
   before implementation.
5. **Subaward sponsor**: resolve via parent-Award join (recommended) or
   ship the sparse 5.3%-populated `award_sponsor_name` column as-is?
6. **Negotiation's small unassociated-detail discrepancy** (8,533
   "NO"-type negotiations vs. 8,554 rows with a detail row) — worth a
   dedicated follow-up query before implementation, not resolved here.
7. **IRB**: excluded from the initial model per the evidence in this
   document (no unit column at all on `irb_protocol_version`, no
   denormalized person *name* — only `pi_id`, no sponsor column, and
   currently 0 rows in this dev database). Confirm this exclusion, or
   direct a separate IRB-specific investigation before any
   implementation.

## Boundaries honored

No production code, migrations, or indexes were created. No data was
changed. Nothing was committed, pushed, or deployed as part of this
design phase. No AI/Bedrock/Titan/pgvector/RAG involvement anywhere in
this design.
