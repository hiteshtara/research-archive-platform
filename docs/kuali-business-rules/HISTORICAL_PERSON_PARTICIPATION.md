# Historical Person Participation Search — Source Audit, Gap Analysis, and Design

**Status: research and design only. Nothing in this document has been implemented.**
Requested by: legal/audit requirement (Dean's office) — the ability to find every
IRB protocol, Award, and Institutional Proposal where a specific person served
in a given role (e.g. PI) during a historical date range, even when that
record's *current* version has a different person in that role.

## 0. Headline finding

**Award and Institutional Proposal are structurally ready for this requirement
today. IRB is not**, and the gap is not cosmetic:

- Award's `archive.award_person` and Proposal's `archive.proposal_person`
  both key to the exact historical version, carry their own denormalized
  `full_name` snapshot (never resolved from a shared "current person" table),
  carry a real Oracle `source_update_timestamp`, and their parent version
  tables carry a real KEW workflow document number. A correct
  point-in-time query is possible today with a straightforward join.
- IRB's live API path (`archive.irb_protocol_version`) has **no role column
  at all** (a single hardcoded PI slot, no Co-PI/Co-I/Key Person concept),
  **no historical name** (`pi_full_name` exists only on the *current-row*
  table, never on the historical version table — every historical version
  displays the *current* PI's name, not the name that was actually recorded
  at that version), **no real business timestamp** (only ETL `loaded_at`),
  and **no attachment linkage**.
- A second, correctly-shaped IRB personnel table (`archive.protocol_person`)
  already exists in the schema — exact version FK, name snapshot, real role
  vocabulary, real timestamps — but it is **completely orphaned**: zero
  references anywhere in `api/src/main/java`, populated only by a separate
  loader (`etl/load_protocols.py`) that has never been reconciled against the
  live IRB data lineage's own `protocol_id`/`protocol_base` key space (see
  §3.3). Using it today would require a verification phase first, not a
  simple query.
- A related, already-shipped feature (`GET /api/investigators?email=`,
  `InvestigatorRepository.findByEmail`) has a **real correctness bug** for
  this exact use case: it correctly finds every IRB protocol family where a
  person was PI on *any* historical version, but then returns that family's
  *most recent* version's data — not the actual historical version where the
  match occurred (§4). A legal-discovery answer built on this endpoint today
  would show the wrong protocol status/title/dates for the period being
  investigated.

Everything below documents exactly why, with file:line citations, then
proposes a cross-domain design that does not repeat these mistakes.

## 1. Methodology

Audited directly against `database/migrations/*.sql` (schema, verbatim),
`sql/extract/*/*.sql` (Oracle extraction queries), and the live Java
repository/controller code that actually serves the current API — not
just the schema in isolation, since a column can exist and still never be
read (see IRB Lineage B). Cross-checked against `docs/kuali-business-rules/`
and `docs/architecture/` for any already-proven business rule. Every claim
below is either a direct schema quote, a direct code quote, or an
independently re-run grep against the current working tree.

## 2. Domain-by-domain audit

### 2.1 Award

**Person table**: `archive.award_person`
(`database/migrations/V011__create_award_archive_tables.sql:109-155`).

```sql
CREATE TABLE IF NOT EXISTS archive.award_person (
    award_person_id             BIGINT PRIMARY KEY,
    award_id                    BIGINT NOT NULL
                                    REFERENCES archive.award_version(award_id)
                                    ON DELETE CASCADE,
    award_number                VARCHAR(50) NOT NULL,
    sequence_number              INTEGER NOT NULL,
    person_id                   VARCHAR(50),
    rolodex_id                  BIGINT,
    full_name                   VARCHAR(500),
    contact_role_code           VARCHAR(50),
    key_person_project_role     VARCHAR(300),
    faculty_flag                VARCHAR(10),
    academic_year_effort        NUMERIC(10,4),
    calendar_year_effort        NUMERIC(10,4),
    summer_effort                NUMERIC(10,4),
    total_effort                 NUMERIC(10,4),
    source_update_timestamp     TIMESTAMP,
    source_update_user          VARCHAR(100),
    loaded_at                   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                     BIGINT REFERENCES archive.load_run(load_id)
);
```

- **Exact version key**: `award_id`, a real `REFERENCES archive.award_version(award_id)`
  FK. Confirmed live in `AwardArchiveRepository.findCurrentPeople`
  (`api/.../AwardArchiveRepository.java:293-337`), joined on `award_person.award_id = award_version.award_id`.
- **Family/sequence preserved on the row**: yes — `award_number`,
  `sequence_number` both denormalized directly onto the row, sourced 1:1
  from Oracle `AWARD_PERSONS` (`sql/extract/award/03_award_people.sql:6-9`).
- **Role**: `contact_role_code` (known real values, live-confirmed:
  **PI / MPI / COI / KP**, documented in
  `database/migrations/V061__create_proposal_person_and_unit_contact.sql:10-13`'s
  header comment as "the same shared vocabulary already proven for
  `archive.award_person.contact_role_code`"). `AwardArchiveRepository.java:322-324`:
  `WHEN UPPER(TRIM(person.contact_role_code)) = 'PI'`.
- **Historical name preserved**: yes. `full_name` is the row's **own**
  snapshot column, populated from Oracle `AWARD_PERSONS.FULL_NAME`
  (`sql/extract/award/03_award_people.sql:13`) — selected directly in
  `AwardArchiveRepository.java:305,328`, never joined to `archive.person`
  for display.
- **Timestamp**: `source_update_timestamp`/`source_update_user`, from Oracle
  `AWARD_PERSONS.UPDATE_TIMESTAMP`/`UPDATE_USER`
  (`sql/extract/award/03_award_people.sql:25-26`) — a real business
  timestamp, not ETL ingestion time.
- **Workflow document**: `archive.award_version.workflow_document_number`
  (`V055__add_award_workflow_document_number.sql:38-39`), proven to be the
  real KEW `DOC_HDR_ID` (`docs/kuali-business-rules/Workflow Documents.md`).
  Status: `status_code`/`status_description`/`award_sequence_status` on
  `award_version` (V011:5,7-8). Current-version flag: `is_primary_current`
  (`V013__add_award_primary_current_flag.sql`).
- **Project period fields** (on `award_version`, V011): `begin_date`,
  `closeout_date` — usable as the "project period" date basis (see §6).
- **Attachment linkage**: `archive.award_attachment.award_id` — exact
  version key, used as such in `AwardArchiveRepository.findAttachments`
  (`AwardArchiveRepository.java:1543-1552`). No declared `REFERENCES`
  constraint (unlike `award_person`), but consistently used exact-scoped
  in every query that touches it.

**Conclusion: Award is ready.** Every field the Dean's query needs already
exists, is version-exact, and is already proven correct by this codebase's
own existing queries.

### 2.2 Institutional Proposal

Two schema generations exist; only the newer one matters for this design.
The original `archive.proposal_person` (`V015`, altered by `V016`) was
**dropped** (`V033__drop_award_unit_contact_and_proposal_person.sql:11`) and
**re-created with a different, correct, exact-version shape** by
`V061__create_proposal_person_and_unit_contact.sql:39-63`:

```sql
CREATE TABLE IF NOT EXISTS archive.proposal_person (
    proposal_person_id       BIGINT PRIMARY KEY,
    proposal_id               BIGINT NOT NULL,
    proposal_number           VARCHAR(50) NOT NULL,
    sequence_number           INTEGER NOT NULL,
    person_id                 VARCHAR(40),
    rolodex_id                BIGINT,
    full_name                 VARCHAR(500),
    contact_role_code         VARCHAR(20),
    key_person_project_role   VARCHAR(200),
    faculty_flag              VARCHAR(10),
    academic_year_effort      NUMERIC(10,4),
    calendar_year_effort      NUMERIC(10,4),
    summer_effort              NUMERIC(10,4),
    total_effort                NUMERIC(10,4),
    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),
    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);
```

> **Stale-doc flag**: `docs/kuali-business-rules/InstitutionalProposal.md`
> (as of this writing) still says `archive.proposal_person: table does not
> exist (dropped by V033, as expected)`. That was true before V061
> re-created it and is now **incorrect** — flag for a doc update, not part
> of this deliverable.

- **Exact version key**: `proposal_id`, populated from Oracle
  `PROPOSAL_PERSONS.PROPOSAL_ID` (`sql/extract/proposal/03_proposal_persons.sql:4`).
  **No declared `REFERENCES` FK** to `proposal_version(proposal_id)` (a bare
  column, unlike Award's `award_person.award_id`) — correct by ETL
  discipline, not by constraint. Confirmed exact-scoped in
  `ProposalV1Repository.findPersonRows(long proposalId)`:
  `WHERE proposal_id = :proposalId` (`ProposalV1Repository.java:131-153`).
- **Family/sequence preserved on the row**: yes, denormalized 1:1 from
  Oracle (`sql/extract/proposal/03_proposal_persons.sql:5`).
- **Role**: `contact_role_code` — same **PI / MPI / COI / KP** vocabulary,
  independently corroborated from real Kuali Java source in
  `docs/kuali-business-rules/InstitutionalProposal.md:268-269` ("PI
  selection: `roleCode = ContactRole.PI_CODE` (`"PI"`) ... `"MPI"` for
  multi-PI"). That doc also warns `key_person_project_role` is an
  independent field — do not conflate it with `contact_role_code`.
- **Historical name preserved**: yes — `full_name` is the row's own
  snapshot from `PROPOSAL_PERSONS.FULL_NAME`
  (`sql/extract/proposal/03_proposal_persons.sql:9`), selected directly in
  `ProposalV1Repository.java:136,169`, no join to `archive.person`. The
  older family-scoped path (`proposal_version.principal_investigator_id`/
  `principal_investigator_name`, V015:21-22) is likewise a per-version
  denormalized snapshot, also correct.
- **Timestamp**: `source_update_timestamp`/`source_update_user`, from real
  Oracle `PROPOSAL_PERSONS.UPDATE_TIMESTAMP`/`UPDATE_USER`
  (`sql/extract/proposal/03_proposal_persons.sql:20-21`).
- **Workflow document**: `proposal_version.document_number`, the real KEW
  document number, added by `V058__add_proposal_version_and_award_link_columns.sql:27`
  (live-verified pairs documented in `InstitutionalProposal.md:33,38-40`).
  Status: `status_code`/`status_description` (V058:28-29),
  `proposal_sequence_status` (V015:7, values ACTIVE/ARCHIVED/CANCELED/PENDING).
- **Project period fields** (on `proposal_version`, V015):
  `initial_start_date`/`initial_end_date`, `total_start_date`/`total_end_date`.
- **Attachment linkage**: `archive.proposal_attachment.proposal_id` — exact
  version key, used as such in `ProposalV1Repository.findAttachmentRows`
  (`ProposalV1Repository.java:215-247`). Same "no declared FK, but
  consistently used exact-scoped" pattern as Award.

**Conclusion: Proposal is ready**, on the same terms as Award (via the
V061 personnel table, not the older dropped one).

### 2.3 IRB — two unrelated data lineages

This is the one domain that needed real scrutiny, and the finding is
serious enough to restate: **there are two, structurally unrelated IRB
person data sets in this schema, and only the worse one is wired to the
API.**

#### Lineage A — legacy composite (what the live API actually serves)

Source: a legacy administrative Excel export
(`etl/archive_etl/transform/irb_composite.py`), **not** an Oracle-direct
extraction (`docs/architecture/PROTOCOL_ARCHIVE_COVERAGE.md:104-110,125`).
Tables: `archive.irb_protocol` (current-row snapshot,
`V004__create_irb_tables.sql`) + `archive.irb_protocol_version` (history,
`V007__create_irb_composite_history.sql:1-50`):

```sql
CREATE TABLE IF NOT EXISTS archive.irb_protocol_version (
    protocol_id            BIGINT PRIMARY KEY,
    protocol_base          VARCHAR(30) NOT NULL,
    protocol_number        VARCHAR(60) NOT NULL,
    sequence_number        INTEGER,
    active_ind             VARCHAR(10),
    crc_protocol_num       VARCHAR(20),
    document_number        VARCHAR(100),
    title                  TEXT,
    protocol_type_code     VARCHAR(30),
    protocol_type          VARCHAR(200),
    protocol_status_code   VARCHAR(30),
    protocol_status        VARCHAR(200),
    pi_id                  VARCHAR(50),
    pi_email               VARCHAR(320),
    pi_affiliation_code    VARCHAR(30),
    pi_affiliation         VARCHAR(200),
    fund_center_number     VARCHAR(100),
    school_number          VARCHAR(100),
    received_date          DATE,
    claimed_date           DATE,
    determination_date     DATE,
    approval_date          DATE,
    expiration_date        DATE,
    closure_date            DATE,
    authorization_date     DATE,
    loaded_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                BIGINT REFERENCES archive.load_run(load_id)
    -- (record_storage_box/maximum_expiration_ind/expiration_status/
    -- working_days/calendar_days/irb_days/pi_days/funding_source_count/
    -- ohrp_categories/summary_keywords/irb_analyst_id/irb_advisor_id
    -- omitted above for brevity - none are role/name/timestamp-relevant)
);
```

- **No role column at all.** A single hardcoded PI slot
  (`pi_id`/`pi_email`/`pi_affiliation*`); no `contact_role_code` or
  equivalent. **There is no Co-PI/Co-Investigator/Key Person concept
  anywhere in the live IRB data path.**
- **No historical name — this is the critical gap.**
  `irb_protocol_version` has `pi_id` and `pi_email` but **no
  `pi_full_name` column** (independently re-verified: zero occurrences of
  `pi_full_name` in `V007__create_irb_composite_history.sql`).
  `pi_full_name` exists **only** on `archive.irb_protocol`, the
  single current-row table (V004:22). Confirmed in the actual global
  search view: `archive.v_global_search.pi_full_name` is sourced
  exclusively from `current_records.pi_full_name`
  (`V010__expand_global_search_to_history.sql:107,126,162`) — independently
  re-verified, all three occurrences point at the *current* row, never a
  historical version. Historical PI **email** is aggregated across
  versions (V010:47-49); historical PI **name** is not.
  Also confirmed in `IrbWorkspaceRepository.findHistoricalProtocol`
  (`IrbWorkspaceRepository.java:88-104`): the per-version SELECT list
  includes `pi_id, pi_email, pi_affiliation` but no `pi_full_name`, while
  the separate `findCurrentProtocol` query (against `archive.irb_protocol`)
  does select it (`IrbWorkspaceRepository.java:45-58,73`).
  **Practical effect**: for any historical IRB version, this system can
  give you the PI's `pi_id` (BUID) and `pi_email` as recorded at that
  version, but the *name* shown anywhere in the UI/API for that version is
  actually the *current* PI's name — not necessarily the name of the
  person who was actually PI at that point in history.
- **No real business timestamp.** No `source_update_timestamp`/
  `source_update_user` on `irb_protocol_version` at all — only
  `loaded_at` (ETL ingestion time, V007:48). The closest genuine business
  dates are protocol-lifecycle milestones (`received_date`,
  `determination_date`, `approval_date`, `expiration_date`,
  `closure_date`) — none of which specifically means "when this PI
  assignment was recorded."
- **No proven workflow-document linkage.** `document_number`/
  `crc_protocol_num` exist on the row, but unlike Award/Proposal, no
  `docs/kuali-business-rules/` doc proves this is a real KEW join key;
  `docs/architecture/PROTOCOL_ARCHIVE_COVERAGE.md:125` explicitly flags
  several sibling columns on this table as "no corresponding column on
  the OJB-mapped `PROTOCOL` table at all — provenance unconfirmed."
- **No attachment linkage.** No IRB-specific attachment table. The
  generic `archive.archived_attachment` table's `module_code` CHECK
  constraint lists `'IRB_PROTOCOL'`/`'IRB_PERSONNEL'`
  (`V020__create_archived_attachment.sql:31-32`), but grepping the whole
  API and ETL codebases shows it is **never actually populated or read
  for IRB** — the only hit is a unit test string-checking the constraint
  itself. IRB has no attachments linkable to a specific historical
  version today.

#### Lineage B — real Oracle-direct Protocol Archive (schema exists, orphaned)

Created in `V021`-`V023`, dropped entirely in
`V032__drop_protocol_archive.sql` ("Protocol Archive is a distinct module
from legacy IRB... being removed"), then re-created with a smaller shape in
`V034__create_protocol_archive.sql`. `archive.protocol_person`
(`V034__create_protocol_archive.sql:74-120`):

```sql
CREATE TABLE IF NOT EXISTS archive.protocol_person (
    protocol_person_id               BIGINT PRIMARY KEY,
    protocol_id                      BIGINT NOT NULL
                                         REFERENCES archive.protocol_version(protocol_id),
    source_protocol_id               BIGINT NOT NULL,
    protocol_number                  VARCHAR(60) NOT NULL,
    sequence_number                  INTEGER NOT NULL,
    person_id                        VARCHAR(100),
    full_name                        VARCHAR(500),
    protocol_person_role_id          VARCHAR(100),
    protocol_person_role_description VARCHAR(200),
    is_pi                            BOOLEAN NOT NULL DEFAULT FALSE,
    email_address                    VARCHAR(500),
    email_source                     VARCHAR(20),
    rolodex_id                       BIGINT,
    affiliation_type_code            VARCHAR(100),
    comments                         TEXT,
    source_update_timestamp          TIMESTAMP,
    source_update_user               VARCHAR(100),
    source_version_number            BIGINT,
    source_object_id                 VARCHAR(100),
    archived_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                          BIGINT REFERENCES archive.load_run(load_id)
);
```

Structurally, this is exactly what the Dean's requirement needs: real FK
to `protocol_version(protocol_id)`, `full_name` snapshot on the row,
explicit `protocol_person_role_id`/description + `is_pi` boolean, and real
`source_update_timestamp`. **But**: independently re-verified via
`grep -rn "archive\.protocol_version\|archive\.protocol_person"
api/src/main/java/` → **zero matches**. (A naive grep for the bare
substrings `protocol_person`/`protocol_version` returns 11 hits, but every
one of them is `irb_protocol_version` — Lineage A — matched as a
substring; there is genuinely no reference to the standalone
`protocol_person`/`protocol_version` tables anywhere in the API.) It is
populated only by `etl/load_protocols.py` and never read back by any
controller, repository, or query.

Worse: Lineage B's own `protocol_id`/`protocol_base` numbering has **never
been proven equivalent** to Lineage A's `protocol_id`/`protocol_base`
numbering — they are two independent loads from two different source
processes, and `V023__correct_protocol_person_parent_resolution.sql` and
`docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md` document a past real bug in
exactly this kind of parent-resolution logic for this table family (a
child row's own `PROTOCOL_ID` disagreeing with its true parent version at
~15% mismatch rate, resolved only after being caught against live data —
see project convention already captured in `docs/DECISIONS.md`). Treating
Lineage B as a drop-in replacement without a dedicated reconciliation
phase would repeat that exact mistake.

### 2.4 Existing adjacent precedent — and its bug

`GET /api/investigators?email=` (`InvestigatorController.java`,
`InvestigatorRepository.findByEmail`, `InvestigatorRepository.java:95-169`)
already does a version of "find every place this person was PI,
historically" — but only for IRB, only by email (no `personId`/role
filter), no date range, and with a real correctness bug for this exact
use case:

```sql
-- historicalStudies: correctly finds every protocol_base family where
-- pi_email matched on ANY historical version...
WITH matching_families AS (
    SELECT DISTINCT protocol_base
    FROM archive.irb_protocol_version
    WHERE UPPER(TRIM(pi_email)) = UPPER(TRIM(:email))
),
-- ...but then returns each family's MOST RECENT version's data, not the
-- specific version where the match actually occurred:
ranked_versions AS (
    SELECT protocol.*, ROW_NUMBER() OVER (
        PARTITION BY protocol.protocol_base
        ORDER BY COALESCE(protocol.sequence_number, -1) DESC, protocol.protocol_id DESC
    ) AS version_rank
    FROM archive.irb_protocol_version protocol
    INNER JOIN matching_families ON matching_families.protocol_base = protocol.protocol_base
)
SELECT ... FROM ranked_versions WHERE version_rank = 1
```

If Person X was PI on version 1 of protocol ABC (2011) and version 3
(current, 2023) has a different PI, this endpoint correctly identifies
ABC as a match, but reports version 3's title/status/dates as the
"historical" record — not version 1's, which is the version that
actually had X as PI. **A legal-discovery answer built on this endpoint
would misstate the record's status and dates for the period under
investigation.** The new design in §5-§7 must not repeat this: it must
return the *matching* version's own data, not the family's newest
version.

## 3. Comparison matrix

| Domain | Exact version key | Family key | Person source | PI rule | Historical name preserved | Date source | Workflow document |
|---|---|---|---|---|---|---|---|
| **Award** | `award_person.award_id` (declared FK to `award_version`) | `award_number` (denormalized on row) | `archive.award_person` (own table, Oracle-direct) | `contact_role_code = 'PI'` (PI/MPI/COI/KP vocabulary) | **Yes** — `full_name` on the row itself | `source_update_timestamp` (real Oracle business time); project period via `award_version.begin_date`/`closeout_date` | `award_version.workflow_document_number` — proven real KEW `DOC_HDR_ID` |
| **Institutional Proposal** | `proposal_person.proposal_id` (bare column, no declared FK) | `proposal_number` (denormalized on row) | `archive.proposal_person` (V061, own table, Oracle-direct) | `contact_role_code = 'PI'` (PI/MPI/COI/KP vocabulary) | **Yes** — `full_name` on the row itself | `source_update_timestamp` (real Oracle business time); project period via `proposal_version.initial_start_date`/`total_end_date` | `proposal_version.document_number` — proven real KEW doc number |
| **IRB (Lineage A, live API)** | `irb_protocol_version.protocol_id` (PK, but no role table joins to it) | `protocol_base` (denormalized on row) | `irb_protocol_version` itself — single hardcoded PI slot, **no role table** | Structural only — one PI field, **no Co-PI/Co-I/KP concept** | **No** — only `pi_id`/`pi_email` on the historical row; `pi_full_name` exists only on the *current*-row table and is what every UI/API surface actually displays | **No real business timestamp** — only ETL `loaded_at`; closest fields are lifecycle milestones (`approval_date` etc.), not "when recorded" | `document_number` present but **unverified** as a real KEW join key (unlike Award/Proposal) |
| **IRB (Lineage B, orphaned)** | `protocol_person.protocol_id` (declared FK to `protocol_version`) | `protocol_number`/`protocol_base` | `archive.protocol_person` (Oracle-direct, structurally correct) | `protocol_person_role_id`/description + explicit `is_pi` boolean (derivation "not yet verified against live Oracle data" per its own migration comment) | **Yes, structurally** — `full_name` on the row | `source_update_timestamp` (real) | Present on `protocol_version` but **entire lineage never wired to any API** and **key-space equivalence to Lineage A unproven** |

## 4. Gap analysis summary

1. **IRB has no usable historical-name, historical-role, or historical
   attachment path today.** This blocks the Dean's requirement for IRB
   specifically, not just makes it harder.
2. **IRB Lineage B could close this gap, but requires a verification
   phase first**: prove `protocol_id`/`protocol_base` equivalence (or
   build an explicit mapping) between Lineage A and B before either (a)
   wiring Lineage B into the API for the first time, or (b) treating its
   rows as authoritative for a legal-discovery answer. This is the same
   class of bug already documented once in this project
   (`docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md`) — do not repeat it by
   skipping verification under time pressure.
3. **`personId` is not proven to be one universal identity space across
   domains.** Award/Proposal use Oracle's `PERSON_ID`; IRB uses a BUID
   (`pi_id`). These may coincide for BU-affiliated individuals but that
   equivalence is not proven anywhere in this codebase. A cross-domain
   search keyed on a bare `personId` string match risks false negatives
   (same person, different ID format) or, worse, false positives if BUIDs
   and Oracle PERSON_IDs ever collide by coincidence. Recommend also
   supporting a name+email-based search path (mirroring
   `InvestigatorRepository`'s existing email-based identity resolution)
   rather than assuming `personId` alone is sufficient, and documenting
   this ambiguity explicitly in the API's own response (never silently
   assume equivalence).
4. **Neither `award_person` nor `proposal_person` has a declared FK to
   its parent version table** (unlike Award's own `award_attachment`
   pattern used elsewhere, and unlike Lineage B's `protocol_person`).
   This is a correctness risk worth closing (an additive migration,
   out of scope for this document) but is not currently observed to
   cause bad data — every existing query already scopes correctly by
   convention.
5. **The existing `/api/investigators` endpoint's version-selection bug**
   (§4) should be fixed as part of this work, or explicitly superseded by
   the new endpoint with a note in its own doc that `/api/investigators`
   remains for a different, narrower purpose (current + most-recent-match
   summary, not full historical audit).
6. **`docs/kuali-business-rules/InstitutionalProposal.md`'s claim that
   `archive.proposal_person` does not exist is stale** (V061 re-created
   it after that doc was written) — flag for correction, not part of this
   deliverable.

## 5. Proposed normalized read model

A single, domain-tagged shape every one of the three (eventually four,
once IRB is unblocked) domains' historical person-participation rows maps
into. Never materialized as its own table — always a read-time UNION
across domain-specific queries (see §7), so it can never drift from the
domains' own authoritative data.

| Field | Type | Meaning |
|---|---|---|
| `domain` | enum: `AWARD` \| `PROPOSAL` \| `IRB` | Which archive this row came from |
| `recordId` | long | The exact historical version's own surrogate key (`award_id`/`proposal_id`/`protocol_id`) — what a client navigates to |
| `familyNumber` | string | `award_number`/`proposal_number`/`protocol_base` |
| `sequenceNumber` | int, nullable | The version's ordinal within its family |
| `personId` | string, nullable | Oracle `PERSON_ID` (Award/Proposal) or BUID (IRB) — **not proven to be one identity space across domains, see §4.3** |
| `historicalFullName` | string, nullable | The name **as recorded on this exact historical row** — never resolved from a shared/current person table |
| `roleCode` | string, nullable | `contact_role_code` (Award/Proposal) or `protocol_person_role_id` (IRB Lineage B only) |
| `roleDescription` | string, nullable | Human-readable role, where available |
| `title` | string, nullable | Award/Proposal/Protocol title as of this version |
| `status` | string, nullable | This version's own status description |
| `workflowDocumentNumber` | string, nullable | Real KEW document number for this version, where proven (Award/Proposal only until IRB is unblocked) |
| `versionDate` | timestamp, nullable | `source_update_timestamp` — when this version/assignment was recorded in the source system |
| `projectStartDate` | date, nullable | Award `begin_date` / Proposal `initial_start_date` / IRB `approval_date` (semantics differ by domain — documented per-row, never conflated) |
| `projectEndDate` | date, nullable | Award `closeout_date` / Proposal `total_end_date` / IRB `expiration_date` or `closure_date` |

Two fields deliberately **not** in the user-specified list above, needed
for audit trust and correct navigation, proposed as additions:

- `exactVersionId` — same value as `recordId`, named explicitly for
  clarity that this is the audit-grade exact version, distinct from any
  "current version" resolution a client might separately perform (mirrors
  the `exactLinkedAwardId`/`navigableCurrentAwardId` convention already
  used elsewhere in this codebase for exactly this "audit ID vs.
  navigation ID" distinction).
- `sourceDomainConfidence` — for IRB rows only, once Lineage B is wired:
  `"VERIFIED"` or `"UNVERIFIED"` depending on whether that row's
  `protocol_id` has been reconciled against Lineage A (see §8, Phase 0).
  Never silently presented as equally trustworthy to Award/Proposal rows
  until that reconciliation is proven.

## 6. `dateBasis` — why it must be an explicit filter, not assumed

The Dean's stated query ("PI at any point from 2010-01-01 through
2019-12-31") is ambiguous between two real, different questions:

- **`recorded`** (default): does this version's own `versionDate`
  (`source_update_timestamp` — when Kuali last saved this specific
  person/role assignment) fall in the range? This answers "was this the
  system-of-record state at some point in the range" — an audit-trail
  question.
- **`project`**: does the project's own `[projectStartDate,
  projectEndDate]` interval overlap the requested range? This answers
  "was this person PI at some point *during the project's actual
  run*" — a business question, and almost certainly the one legal/audit
  actually wants for "who was responsible for this work during this
  period."

These can disagree materially: a record last touched in Kuali in 2023 for
a project that actually ran 2010-2012 matches `recorded` against a
2023 range and `project` against a 2010-2012 range — not the same rows.
`dateBasis` must be a required-with-default filter parameter so the
caller always knows which question they asked, and the response should
echo back which basis was used (see §8, export requirements).

## 7. Sample SQL (illustrative — not production code)

Award and Proposal are shown as real, runnable joins against the proven
schema above. IRB is shown as **blocked**, with the query that would work
once Lineage B is reconciled and wired (§8, Phase 0), clearly marked as
not-yet-valid.

```sql
-- AWARD — works today
SELECT
    'AWARD' AS domain,
    ap.award_id AS record_id,
    ap.award_number AS family_number,
    ap.sequence_number,
    ap.person_id,
    ap.full_name AS historical_full_name,
    ap.contact_role_code AS role_code,
    NULL AS role_description,
    av.title,
    av.status_description AS status,
    av.workflow_document_number,
    ap.source_update_timestamp AS version_date,
    av.begin_date AS project_start_date,
    av.closeout_date AS project_end_date
FROM archive.award_person ap
JOIN archive.award_version av ON av.award_id = ap.award_id
WHERE ap.contact_role_code = :roleCode          -- e.g. 'PI'
  AND ap.person_id = :personId
  AND (
        -- dateBasis = 'recorded'
        ap.source_update_timestamp BETWEEN :dateFrom AND :dateTo
        -- dateBasis = 'project' (mutually exclusive, chosen by the API layer)
        -- OR (av.begin_date, av.closeout_date) OVERLAPS (:dateFrom, :dateTo)
      );

-- INSTITUTIONAL PROPOSAL — works today
SELECT
    'PROPOSAL' AS domain,
    pp.proposal_id AS record_id,
    pp.proposal_number AS family_number,
    pp.sequence_number,
    pp.person_id,
    pp.full_name AS historical_full_name,
    pp.contact_role_code AS role_code,
    NULL AS role_description,
    pv.title,
    pv.status_description AS status,
    pv.document_number AS workflow_document_number,
    pp.source_update_timestamp AS version_date,
    pv.initial_start_date AS project_start_date,
    pv.total_end_date AS project_end_date
FROM archive.proposal_person pp
JOIN archive.proposal_version pv ON pv.proposal_id = pp.proposal_id
WHERE pp.contact_role_code = :roleCode
  AND pp.person_id = :personId
  AND (
        pp.source_update_timestamp BETWEEN :dateFrom AND :dateTo
        -- OR (pv.initial_start_date, pv.total_end_date) OVERLAPS (:dateFrom, :dateTo)
      );

-- IRB — BLOCKED. Requires Phase 0 (Lineage A/B reconciliation, see §8)
-- before this can be trusted. Shown for design completeness only.
SELECT
    'IRB' AS domain,
    prp.protocol_id AS record_id,
    prp.protocol_number AS family_number,
    prp.sequence_number,
    prp.person_id,
    prp.full_name AS historical_full_name,
    prp.protocol_person_role_id AS role_code,
    prp.protocol_person_role_description AS role_description,
    pv.title,
    pv.protocol_status_code AS status,
    pv.document_number AS workflow_document_number,   -- UNVERIFIED as real KEW key
    prp.source_update_timestamp AS version_date,
    pv.approval_date AS project_start_date,
    pv.expiration_date AS project_end_date
FROM archive.protocol_person prp                        -- Lineage B, currently orphaned
JOIN archive.protocol_version pv ON pv.protocol_id = prp.protocol_id
WHERE (prp.is_pi = TRUE OR prp.protocol_person_role_id = :roleCode)
  AND prp.person_id = :personId
  AND prp.source_update_timestamp BETWEEN :dateFrom AND :dateTo;
  -- Every row from this query must carry sourceDomainConfidence until
  -- Lineage A/B key-space reconciliation is complete and proven.
```

A production implementation would `UNION ALL` the domains actually
requested (`domains` filter) rather than always running all three, and
apply `personId`/`personName` as alternative, explicitly-labeled identity
filters (never silently OR'd together without the caller knowing which
one matched — see §4.3).

## 8. `GET /api/v1/historical-participation/search` — proposed design

**Filters** (all optional except at least one identity filter —
`personId` or `personName` — being required; the endpoint must refuse to
run an unfiltered full-archive person scan):

| Filter | Type | Notes |
|---|---|---|
| `personId` | string | Matched per-domain against that domain's own ID space (Oracle `PERSON_ID` for Award/Proposal, BUID for IRB) — response must indicate which ID space matched for each row |
| `personName` | string | Case-insensitive substring/exact match (configurable) against `historicalFullName` — the fallback identity path per §4.3 |
| `role` | string | e.g. `PI` — matched against each domain's own role vocabulary; IRB has no role vocabulary until Lineage B is wired (§4.1) |
| `domains` | string[] | Subset of `AWARD`, `PROPOSAL`, `IRB` — defaults to all three, but IRB returns an explicit `"blocked"` notice (not silently empty results) until Phase 0 is complete |
| `dateFrom`, `dateTo` | date | The requested range |
| `dateBasis` | enum: `recorded` \| `project` | Required-with-default (`recorded`) — see §6 |

**Response**: `{ criteria: {...as echoed back, including resolved dateBasis},
generatedAt, results: NormalizedParticipationRow[] }` — every row from §5's
model, plus a top-level `warnings` array (e.g. `"IRB search is currently
blocked pending Lineage A/B reconciliation — see
docs/kuali-business-rules/HISTORICAL_PERSON_PARTICIPATION.md §8 Phase 0"`)
so a legal/audit consumer never mistakes "IRB excluded because blocked"
for "IRB searched and found nothing."

Every result links to the **exact historical version** (`recordId`),
never a "current version" resolution — this is the entire point of the
requirement, and must not regress to the `/api/investigators`-style bug
in §4.

## 9. Export requirements

Both CSV and PDF exports must include, not just the result rows:

- **Query criteria**: every filter value actually applied (including the
  resolved `dateBasis` and which `domains` were included/blocked).
- **Generation timestamp**: when the export was produced (server time,
  ISO-8601, distinct from any row's own `versionDate`).
- **Record/version identifiers**: `recordId`/`familyNumber`/
  `sequenceNumber` for every row, verbatim — this is what makes the
  export independently re-verifiable against the archive later.
- **Source workflow documents**: `workflowDocumentNumber` per row, with an
  explicit "not available" (not blank) when a domain doesn't have one
  proven (IRB, until Phase 0).
- CSV: reuse this codebase's existing, already-proven client-side
  `toCsv()` pattern (`ui/src/features/explorer/explorerPresentation.mjs`)
  if the export is generated client-side from an already-fetched result
  set; add a dedicated header/footer block above the data rows carrying
  the criteria/generation-timestamp metadata, since plain tabular CSV has
  no other place to carry it.
- PDF: no existing precedent in this codebase (confirmed: no PDF
  generation library or endpoint exists anywhere in `api/` or `ui/`
  today) — this would be new infrastructure, not a reuse of an existing
  pattern, and should be scoped/estimated separately in Phase 2 (§10)
  rather than assumed to be a small addition.

## 10. Recommended implementation plan

**Phase 0 — IRB Lineage A/B reconciliation (blocking prerequisite,
IRB-only).** Prove (or disprove) that Lineage B's `protocol_id`/
`protocol_base` correspond to Lineage A's. If they don't cleanly
correspond, build and verify an explicit mapping, live-verified against
real Oracle data the same way this project's own past parent-resolution
bug was caught (`docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md`). Until this
phase completes, IRB results from the new endpoint must carry
`sourceDomainConfidence: "UNVERIFIED"` at best, or be excluded with an
explicit `warnings` entry at worst — never presented as equally reliable
to Award/Proposal.

**Phase 1 — Award + Proposal only, ship first.** Both domains are ready
today (§2.1, §2.2). Build `GET /api/v1/historical-participation/search`
scoped to `domains: [AWARD, PROPOSAL]` only, with the normalized read
model (§5), `dateBasis` filter (§6), and the sample SQL (§7) as the real
implementation basis. This alone answers the Dean's requirement for two
of the three domains immediately, without waiting on Phase 0.

**Phase 2 — CSV export.** Reuse the existing client-side `toCsv()`
pattern plus the metadata header block (§9). Low risk, no new
infrastructure.

**Phase 3 — IRB integration**, gated on Phase 0's completion. Add IRB to
`domains`, wire `archive.protocol_person`/`protocol_version` (Lineage B)
into the query, and fix or explicitly supersede `/api/investigators`'s
version-selection bug (§4) as part of the same effort, since both draw
from the same reconciled data.

**Phase 4 — PDF export.** New infrastructure; scope and estimate
separately once Phases 1-3 are live and the actual export content
requirements have been validated against a real legal/audit request.

**Phase 5 — cross-domain `personId` identity reconciliation** (§4.3): a
deliberate, documented decision about whether/how to treat Award/Proposal
`PERSON_ID` and IRB BUID as one identity space, rather than the implicit
assumption a naive `personId` filter would otherwise make.

---

**This document stops here, as instructed.** No migration, repository,
service, controller, or UI code has been written for this requirement.
Phase 1 (§10) is ready to begin on request.
