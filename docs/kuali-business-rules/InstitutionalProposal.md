# Institutional Proposal

Behavioral companion to
[`docs/architecture/PROPOSAL_ARCHIVE_COVERAGE.md`](../architecture/PROPOSAL_ARCHIVE_COVERAGE.md)
(PROPOSAL_ARCHIVE_COVERAGE.md is the DataDictionary-driven *feature
completeness* checklist — table by table, COMPLETE/NOT YET ARCHIVED;
this document is the *business-behavior* companion, in the same
`docs/kuali-business-rules/` family as `Budget.md`/`Time and Money.md`:
how identity, versioning, and cross-domain relationships actually
work, sourced to real Kuali `.java`/OJB XML and live Oracle data, not
inferred from the screen). Do not duplicate the coverage matrix here —
cross-reference it.

**Source of truth used**: `InstitutionalProposal.java` (1,751 lines,
`org.kuali.kra.institutionalproposal.home`), its OJB mapping
(`repository-institutionalproposal.xml`), `ProposalLogService`/
`ProposalLogServiceImpl`/`ProposalLogUtils`, `AwardFundingProposal`'s
mapping in `repository-award.xml`, and live Oracle queries against a
real fixture (Proposal family `205`, `PROPOSAL_ID` 212/2986). No code
changes made — this is the investigation-only deliverable the user
explicitly required before any Repository/Service/Controller/DTO/React
work begins.

## Object and workflow identity model

Proven directly from the OJB mapping (`PROPOSAL` table):

| Concept | Field | Column | Meaning |
|---|---|---|---|
| Exact version row | `proposalId` | `PROPOSAL_ID` (PK, surrogate) | One row per submitted/archived/pending Proposal version — same grain as `AWARD.AWARD_ID` |
| Proposal family | `proposalNumber` | `PROPOSAL_NUMBER` | Shared across every version of the same Proposal — same grain as `AWARD.AWARD_NUMBER` |
| Version sequence | `sequenceNumber` | `SEQUENCE_NUMBER` | Position within the family (1, 2, 3…) |
| Kuali workflow document number | `documentNumber` | `DOCUMENT_NUMBER` | Per-**version** (not per-family) — real KEW document number, `access="anonymous"` field with a `reference-descriptor` to `InstitutionalProposalDocument`/`INSTITUTE_PROPOSAL_DOCUMENT` (workflow envelope only, no business content) — exactly the same shape as Award's own `workflow_document_number` (see [Workflow Documents](Workflow%20Documents.md)) |

Live-verified, fixture family `PROPOSAL_NUMBER = '205'`:

```
PROPOSAL_ID 212,  SEQUENCE_NUMBER 1, PROPOSAL_SEQUENCE_STATUS ARCHIVED, DOCUMENT_NUMBER 115569
PROPOSAL_ID 2986, SEQUENCE_NUMBER 2, PROPOSAL_SEQUENCE_STATUS ACTIVE,   DOCUMENT_NUMBER 125761
```

Confirms: `PROPOSAL_ID` = exact version, `PROPOSAL_NUMBER` = family,
`SEQUENCE_NUMBER` = position, `DOCUMENT_NUMBER` = per-version workflow
document — all four proven simultaneously against real data.

## Version-selection rules (current / pending / archived / cancelled)

`InstitutionalProposal.proposalSequenceStatus` (column
`PROPOSAL_SEQUENCE_STATUS`) holds a string value from
`org.kuali.coeus.common.framework.version.VersionStatus`:

```java
public enum VersionStatus {
    ACTIVE, ARCHIVED, CANCELED, PENDING
}
```

The exact same enum Award's own `award_sequence_status` uses — this is
Kuali's one shared version-status vocabulary, not a Proposal-specific
scheme. `InstitutionalProposal` exposes two convenience predicates:

```java
public boolean isActiveVersion() {
    return this.getProposalSequenceStatus().equals(VersionStatus.ACTIVE.toString());
}
public boolean isCancelled() {
    return this.getProposalSequenceStatus().equals(VersionStatus.CANCELED.toString());
}
```

**"Current"** = the version with `PROPOSAL_SEQUENCE_STATUS = 'ACTIVE'`
within a `PROPOSAL_NUMBER` family — proven live: fixture family `205`
has exactly one `ACTIVE` row (`PROPOSAL_ID` 2986, the highest
`SEQUENCE_NUMBER`) and one `ARCHIVED` row (212, the superseded prior
version). This mirrors Award's own single-current-version-per-family
invariant exactly — archive-facing code should name this
`selectedProposal`/`currentProposal`, not silently assume "highest
`sequenceNumber`" is always correct without checking status (the same
caution already documented for Budget's `selectArchiveBudget`).

`PENDING` and `ARCHIVED` are both real, encountered statuses (see the
`allFundingProposals` query below, which explicitly includes both
alongside `ACTIVE`) — `CANCELED` is the one status a "which versions
are real" query must exclude.

## Proposal Log conversion flow

**`ProposalLog` is a separate Kuali feature from `InstitutionalProposal`**
(confirmed already in `PROPOSAL_ARCHIVE_COVERAGE.md` — different table,
`PROPOSAL_LOG`, PK is `PROPOSAL_NUMBER` itself with no `PROPOSAL_ID`/
`SEQUENCE_NUMBER` version axis of its own). This section traces
**exactly how one becomes/links to the other**, which that document
explicitly did not attempt.

### The two link fields, proven from `ProposalLogServiceImpl`

**`PROPOSAL_LOG.MERGED_WITH`** — set when a *temporary* log entry is
merged into a *permanent* one (duplicate-PI-deadline-entry cleanup, not
proposal creation):

```java
public void mergeProposalLog(ProposalLog permanentProposalLog, String temporaryProposalNumber) {
    ProposalLog tempProposalLog = getBusinessObjectService()
        .findBySinglePrimaryKey(ProposalLog.class, temporaryProposalNumber);
    tempProposalLog.setMergedWith(permanentProposalLog.getProposalNumber());
    tempProposalLog.setLogStatus(ProposalLogUtils.getProposalLogMergedStatusCode());
    getBusinessObjectService().save(tempProposalLog);
}
```

`MERGED_WITH` holds a **`PROPOSAL_NUMBER`** (log-to-log family
pointer) — its name is accurate.

**`PROPOSAL_LOG.INST_PROPOSAL_NUMBER` — a real naming trap, the kind
this project has hit before (Budget's "Budget Total Cost Limit").
Despite the "`_NUMBER`" suffix, it does NOT hold a `PROPOSAL_NUMBER`.**
It holds the **`PROPOSAL_ID`** (surrogate PK) of a specific Proposal
version, stored as text:

```java
// InstitutionalProposal.java — runs after EVERY save
@Override
protected void postPersist() {
    super.postPersist();
    updateProposalIpReviewJoin();
    if (proposalId != null && proposalNumber != null)
        updateMergedInstitutionalProposal();
}
private void updateMergedInstitutionalProposal() {
    getProposalLogService().updateMergedInstProposal(proposalId, proposalNumber);
}

// ProposalLogServiceImpl.java
public void updateMergedInstProposal(Long proposalId, String proposalNumber) {
    ProposalLog proposalLog = ...findByPrimaryKey(ProposalLog.class, {"proposalNumber": proposalNumber});
    if (proposalLog != null) {
        proposalLog.setInstProposalNumber(proposalId.toString());   // <- PROPOSAL_ID, not PROPOSAL_NUMBER
        getBusinessObjectService().save(proposalLog);
    }
}
```

The lookup key for the `ProposalLog` row is `proposalNumber` (both
`PROPOSAL.PROPOSAL_NUMBER` and `PROPOSAL_LOG.PROPOSAL_NUMBER` share the
same value/sequence for a converted family), but the value **written
into** `INST_PROPOSAL_NUMBER` is `proposalId.toString()` — the exact
version's surrogate key. Because this runs on every save, in practice
it tracks whichever version was most recently saved, not necessarily
"the current one" at query time.

**Live-verified against the real fixture — and this caveat matters in
practice, not just in theory**: `PROPOSAL_LOG` for `PROPOSAL_NUMBER =
'205'` has `INST_PROPOSAL_NUMBER = NULL`, despite a real, ACTIVE
`PROPOSAL_ID` 2986 existing for that exact family. Do not assume this
field is reliably populated — a real, live counterexample exists in
production data. Never treat `INST_PROPOSAL_NUMBER` as an authoritative
join key from the archive side; if a Proposal-to-ProposalLog link is
ever needed, join on the shared `PROPOSAL_NUMBER` value instead — it is
consistently populated on both sides (confirmed: `PROPOSAL_LOG.PROPOSAL_NUMBER
= '205'` and `PROPOSAL.PROPOSAL_NUMBER = '205'` agree for this fixture).

### The promotion action itself

`promoteProposalLog(proposalNumber)` does **not** create the `PROPOSAL`
row — it only flips `PROPOSAL_LOG.LOG_STATUS` to the "Submitted" code:

```java
public void promoteProposalLog(String proposalNumber) {
    updateProposalLogStatus(proposalNumber, ProposalLogUtils.getProposalLogSubmittedStatusCode());
}
```

Real PROPOSAL_LOG_STATUS codes (live Oracle lookup):

| Code | Description |
|---|---|
| 1 | Pending |
| 2 | Merged |
| 3 | Submitted |
| 4 | Void |
| 5 | Temporary |

Real PROPOSAL_LOG_TYPE codes: `1` = Permanent, `2` = Temporary.

Fixture confirms the whole lifecycle in one live example:
`PROPOSAL_LOG.LOG_STATUS = '3'` ("Submitted") for `PROPOSAL_NUMBER
'205'`, `PROPOSAL_LOG_TYPE_CODE = '1'` ("Permanent") — exactly what a
successfully-promoted, real (non-duplicate) log entry should show,
consistent with a real `PROPOSAL` family existing for that same number.

**Conclusion for archival scope**: `PROPOSAL_LOG` remains correctly
out of scope as its own archived business entity (per the existing
coverage doc's decision — it's a pre-submission deadline-tracking
concept, not the Institutional Proposal record). This investigation
does not reopen that decision; it documents the conversion mechanics
in case a future feature (e.g. "how long did this proposal sit as a
pending log entry before submission") ever needs it.

## Proposal-to-Award relationship (`AWARD_FUNDING_PROPOSALS`)

Table (`repository-award.xml`, class `AwardFundingProposal`):

```
AWARD_FUNDING_PROPOSAL_ID  PK
AWARD_ID                   FK -> AWARD.AWARD_ID       (exact Award version)
PROPOSAL_ID                FK -> PROPOSAL.PROPOSAL_ID (exact Proposal version)
ACTIVE                     CHAR bool
```

This is a genuine **many-to-many join table between exact Award
versions and exact Proposal versions** — `awardId`/`proposalId` are
both real surrogate-key FKs, nullable=false on both sides. But Kuali's
own business logic almost never queries it by exact version on both
ends; it resolves **family-wide, non-Cancelled, active-flagged** on
whichever side is the "owner" of the query, via two structurally
identical, independently-implemented query customizers:

```java
// org.kuali.kra.institutionalproposal.dao.ojb.AllFundingProposalQueryCustomizer
crit.addEqualTo("proposal.proposalNumber", ip.getProposalNumber());
crit.addIn("proposal.proposalSequenceStatus", [ACTIVE, PENDING, ARCHIVED]);  // excludes CANCELED
crit.addEqualTo("active", true);

// org.kuali.kra.award.dao.ojb.AllFundingProposalQueryCustomizer
crit.addEqualTo("award.awardNumber", award.getAwardNumber());
crit.addIn("award.awardSequenceStatus", [ACTIVE, PENDING, ARCHIVED]);        // excludes CANCELED
crit.addEqualTo("active", true);
```

This is the **exact same bounded-family pattern** already proven for
Budget, Comments, and Time & Money in this project — now proven a
fourth time, symmetric on both sides of a many-to-many relationship.
`InstitutionalProposal` also exposes a simpler, exact-version-only
collection (`awardFundingProposals`, plain `inverse-foreignkey` on
`proposalId`) alongside the family-wide `allFundingProposals` — the
same "exact vs. family" duality Budget's `awardFundingProposals`-style
collections already have. **Archive-facing code resolving "which
Awards fund this Proposal" (or vice versa) must use the family-wide,
non-Cancelled, active-only rule — not a bare `PROPOSAL_ID`/`AWARD_ID`
equality join** — exactly the mistake the Budget investigation warned
against repeating.

Live-verified, fixture `AWARD_FUNDING_PROPOSAL_ID` 148183: `AWARD_ID`
148155 ↔ `PROPOSAL_ID` 2986, `ACTIVE = 'Y'`. Both the whole-Proposal-family
query and the whole-Award-family query return exactly this same one row
for this fixture (a clean 1:1 case) — this fixture does not exercise a
true many-to-many example, but the query mechanics themselves are
proven directly from Java source, not inferred from this one row.

`archive.proposal_award` (this project's existing Proposal-side mirror
of the same Oracle table) and `archive.award_funding_proposal`
(Award's own mirror) are each independently populated from
`AWARD_FUNDING_PROPOSALS` — flagged as an open question in the existing
coverage doc, not re-litigated here; this document only adds the proof
of *how the relationship is actually queried in Kuali*, which that
question depends on.

## People, units, and contacts

Proven from `repository-institutionalproposal.xml`:

| Table | Class | Key relationship |
|---|---|---|
| `PROPOSAL_PERSONS` | `InstitutionalProposalPerson` | `PROPOSAL_ID` FK (exact version) — project personnel: PI, co-PI, key persons |
| `PROPOSAL_PERSON_UNITS` | `InstitutionalProposalPersonUnit` | `PROPOSAL_PERSON_ID` FK — **child of a specific person**, not of the Proposal directly |
| `PROPOSAL_UNIT_CONTACTS` | `InstitutionalProposalUnitContact` | `PROPOSAL_ID` FK (exact version) — a **separate, sibling** table to `PROPOSAL_PERSONS`, not a child of it |

**PI selection**: `roleCode = ContactRole.PI_CODE` (`"PI"`, the same
shared `ContactRole` class Award uses — `"MPI"` for multi-PI):

```java
public InstitutionalProposalPerson getPrincipalInvestigator() {
    return this.getProjectPersons().stream()
        .filter(InstitutionalProposalPerson::isPrincipalInvestigator).findFirst().orElse(null);
}
```

**Key-person roles**: a separate field, `keyPersonRole` (column
`KEY_PERSON_PROJECT_ROLE`), independent of `roleCode`/`CONTACT_ROLE_CODE`
— a person can have both a contact role (PI/Co-PI/etc.) and a
free-text-ish key-person project role simultaneously; do not conflate
the two into one "role" field when archiving.

**Lead-unit selection — two distinct concepts sharing the name "lead
unit," proven not to be the same field**:
1. `PROPOSAL.LEAD_UNIT_NUMBER` — a single, denormalized field
   directly on the Proposal version itself (`InstitutionalProposal.unitNumber`
   → `leadUnit` reference).
2. `PROPOSAL_PERSON_UNITS.LEAD_UNIT_FLAG` — a **per-person, per-unit**
   flag; each project person can be assigned multiple units, with (at
   most) one flagged lead **for that person**. This is not necessarily
   the same "lead unit" as #1 in general — it happens to agree in the
   fixture below, but nothing in the OJB mapping enforces they must.

**Credit/effort**: `InstitutionalProposalPerson` carries
`academicYearEffort`/`calendarYearEffort`/`summerEffort`/`totalEffort`
(all `ScaleTwoDecimal`) plus a `faculty`/`includeInCreditAllocation`
flag pair; `InstitutionalProposalPersonUnit` has its own child,
`InstitutionalProposalPersonUnitCreditSplit`
(`PROPOSAL_PERS_UNIT_CRED_SPLITS`), holding per-unit `credit` by
`invCreditTypeCode` — a third level of nesting (Person → PersonUnit →
CreditSplit), the same depth Award's own credit-split shape has.

**`PROPOSAL_UNIT_CONTACTS` kept separate, proven empirically**: fixture
`PROPOSAL_ID` 2986's `PROPOSAL_PERSONS` has exactly one row (PI Lois
Horwitz, `PERSON_ID U56572816`); its `PROPOSAL_UNIT_CONTACTS` has one
different row (Andrea Cozzi, `PERSON_ID U19663726`, `UNIT_CONTACT_TYPE
= 'CONTACT'`) — a genuinely different person, confirming these are not
the same roster viewed two ways. This is Proposal's counterpart to
Award's already-documented [Central Administration
Contacts](Central%20Administration%20Contacts.md) — administrative/unit
contacts, never project personnel.

Live fixture (`PROPOSAL_ID` 2986):
```
PROPOSAL_PERSONS:      PROPOSAL_PERSON_ID 148162, LOIS K HORWITZ, roleCode=PI
PROPOSAL_PERSON_UNITS:  UNIT_NUMBER 1262160000, LEAD_UNIT_FLAG=Y
PROPOSAL.LEAD_UNIT_NUMBER = 1262160000    -- agrees with the PersonUnit flag in this case
PROPOSAL_UNIT_CONTACTS: Andrea Cozzi, UNIT_CONTACT_TYPE=CONTACT
```

**Important scoping note for future implementation**: the existing
coverage doc records that `InstitutionalProposalPerson` (and its three
child tables) was **deliberately removed** from the archive (`V015`
created `archive.proposal_person`, `V033` dropped it) because *"no
verified Oracle extraction query existed for the full
person/role/effort/credit-split shape."* This investigation's live
query above — `PROPOSAL_PERSONS` joined to `PROPOSAL_PERSON_UNITS` via
`PROPOSAL_PERSON_ID`, scoped by exact `PROPOSAL_ID` — **is** exactly
such a verified extraction path, proven against real data. Whether that
satisfies the original "no verified query" objection well enough to
reopen the decision is a call for whoever implements this next, not
decided here; it is not automatically reopened by this document.

## Comments

`PROPOSAL_COMMENTS` (class `InstitutionalProposalComment`) reuses the
**exact same shared `org.kuali.kra.bo.CommentType` reference class**
Award's own `AWARD_COMMENTS` uses (`COMMENT_TYPE_CODE` → `CommentType`)
— confirmed directly in the OJB mapping and live Oracle: `COMMENT_TYPE`
codes `12`/`13` resolve to `"Proposal Comments"`/`"Proposal IP Review
Comments"` — Proposal-specific descriptions within the shared lookup
table, the same pattern Award's own comment types use with their own
distinct codes. History spans the full `PROPOSAL_NUMBER` family in
principle (`proposalNumber`/`sequenceNumber` are both present as fields
on `InstitutionalProposalComment`, mirroring Award Comments' own
family-spanning shape — see [Award Comments](Award%20Comments.md)/
[Comment History](Comment%20History.md)), though this was not
separately live-verified across multiple versions here — the existing
coverage doc already flags a second, unmapped-in-DataDictionary Java
class (`org.kuali.kra.institutionalproposal.ProposalComment`) pointing
at the same table; treat `InstitutionalProposalComment` as
authoritative, per that doc's own conclusion.

Live fixture: `PROPOSAL_ID` 2986 has 2 real comment rows (types 12 and
13 above).

## Attachments

`PROPOSAL_ATTACHMENTS.FILE_DATA_ID` → **already answered, not a new
finding**: `docs/ATTACHMENT_ARCHITECTURE.md` §3 states directly
*"Proposal uses the same `FILE_DATA`/`FILE_DATA_ID` shape as
Subaward"* — i.e. `KCOEUS.FILE_DATA.ID = PROPOSAL_ATTACHMENTS.FILE_DATA_ID`,
content in `KCOEUS.FILE_DATA.DATA`, read via `FileDataBlobReader`.

**This is a different Oracle table/reader than Award's own
attachments.** Award (and Negotiation/Protocol) read
`KCOEUS.ATTACHMENT_FILE.FILE_DATA` via `FILE_ID`
(`AttachmentFileBlobReader`) — a structurally different pair. So the
answer to "can Proposal attachments reuse the Award attachment
storage/download architecture" is: **yes for the generic
pipeline/storage design** (the S3 key scheme, the ETL
`archive_etl.attachments.runner` binary-extraction flow, the
API's read-only S3 IAM role, the controller/service/repository
pattern) — **no for the specific Oracle reader class**, which must be
the Subaward-style `FileDataBlobReader` against `FILE_DATA`, not
Award's `AttachmentFileBlobReader` against `ATTACHMENT_FILE`. This is
already proven and documented; implementing Proposal attachments is a
matter of wiring the existing generic pipeline with the Subaward-style
reader, not inventing anything new.

`InstitutionalProposalAttachment` (OJB mapping) has `proposalId`,
`proposalNumber`, `fileDataId`, `sequenceNumber`, `attachmentNumber`,
`attachmentTitle`, `attachmentTypeCode`, `fileName`, `contentType`,
`documentStatusCode` — the same metadata shape Award's own attachment
archive table already has.

Fixture note: `PROPOSAL_ID` 2986 has **zero** `PROPOSAL_ATTACHMENTS`
rows — this particular fixture doesn't have a real attachment to
exercise end-to-end; the schema/reader proof above stands on the
already-shipped Subaward architecture regardless.

## Financial fields — distinguishing the four scalar totals from a "Proposal Budget"

**There is no separate "Proposal Budget" object in this codebase's
Institutional Proposal domain to confuse these with.** Verified by
grep: zero references to `Budget`/`getBudgets()` anywhere in
`InstitutionalProposal.java`. The generic `BUDGET`/`BUDGET_PERIODS`/
`BUDGET_DETAILS`/`BUDGET_PERSONS` tables Kuali's Budget module shares
with **Proposal Development** (`org.kuali.coeus.propdev.*`) are
irrelevant here — the existing coverage doc already confirmed
`propdev` has **no Java source, no DataDictionary files, and no module
directory anywhere in this checkout**. Institutional Proposal's own
financial picture begins and ends with four scalar fields, directly on
`PROPOSAL`, already correctly identified and partially archived per
the coverage doc:

```
TOTAL_DIRECT_COST_INITIAL   -- requested at initial (first-period) submission
TOTAL_DIRECT_COST_TOTAL     -- requested across the whole project period
TOTAL_INDIRECT_COST_INITIAL
TOTAL_INDIRECT_COST_TOTAL
```

These are plain stored scalar columns (`ScaleTwoDecimal`, default-zero
on new records) — not computed at read time, not aggregated from any
child table. Live fixture (`PROPOSAL_ID` 2986): `TOTAL_DIRECT_COST_INITIAL
= TOTAL_DIRECT_COST_TOTAL = 10519.00`, `TOTAL_INDIRECT_COST_INITIAL =
TOTAL_INDIRECT_COST_TOTAL = 2735.00` — Initial and Total happen to be
equal here (a single-period proposal, or one where costs didn't change
between initial submission and current total), which is expected
business behavior, not evidence they're redundant fields in general.

`InstitutionalProposalCostShare`/`InstitutionalProposalFandA`/
`InstitutionalProposalUnrecoveredFandA` (real child tables — F&A rate
detail and cost-sharing) are a **different, more granular financial
concept** than these four scalars, already correctly separated in the
coverage doc as distinct NOT YET ARCHIVED rows — not part of a
"Budget," but not the same as the four totals either.

`PROPOSAL_STATE` (from the user's supplied table list) is **not** an
Institutional Proposal table at all — its OJB mapping
(`org.kuali.coeus.propdev.impl.state.ProposalState`, `repository.xml`)
places it squarely in `propdev`, the module confirmed absent from this
checkout. It is a small code/description lookup, has no
reference-descriptor anywhere in `repository-institutionalproposal.xml`
pointing to it, and should be treated as out of scope for this domain
— flagged here so it isn't mistaken for a real InstitutionalProposal
table in a future pass.

## Source-to-target table map

| Oracle table | Real Java class | Key relationship | Archive status |
|---|---|---|---|
| `PROPOSAL` | `InstitutionalProposal` | PK `PROPOSAL_ID`; family `PROPOSAL_NUMBER` | **PARTIALLY ARCHIVED** — `archive.proposal_version` exists (V015) but has **zero rows loaded** (see below) |
| `PROPOSAL_EXTENSION` | `InstitutionalProposalExtension` (BU-specific) | 1:1 via `PROPOSAL_NUMBER` | NOT YET ARCHIVED |
| `PROPOSAL_LOG` | `ProposalLog` | PK `PROPOSAL_NUMBER` (no version axis) | NOT APPLICABLE — separate feature, see above |
| `PROPOSAL_LOG_EXTENSION` | `ProposalLogExtension` (BU-specific) | 1:1 with `PROPOSAL_LOG` | NOT APPLICABLE |
| `PROPOSAL_LOG_STATUS` | `ProposalLogStatus` | Lookup | NOT APPLICABLE |
| `PROPOSAL_PERSONS` | `InstitutionalProposalPerson` | FK `PROPOSAL_ID` (exact version) | NOT APPLICABLE (deliberately removed, `V033` — see People section above for the reopening note) |
| `PROPOSAL_PERSON_UNITS` | `InstitutionalProposalPersonUnit` | FK `PROPOSAL_PERSON_ID` (child of Person) | NOT APPLICABLE (same removal) |
| `PROPOSAL_UNIT_CONTACTS` | `InstitutionalProposalUnitContact` | FK `PROPOSAL_ID` (exact version, sibling of Person) | NOT YET ARCHIVED — never built, distinct from the Person removal |
| `PROPOSAL_COMMENTS` | `InstitutionalProposalComment` | FK `PROPOSAL_ID`; shares `COMMENT_TYPE` lookup with Award | NOT YET ARCHIVED |
| `PROPOSAL_ATTACHMENTS` | `InstitutionalProposalAttachment` | FK `PROPOSAL_ID`; `FILE_DATA_ID` → `KCOEUS.FILE_DATA` (Subaward-shaped, not Award-shaped) | NOT YET ARCHIVED |
| `PROPOSAL_CFDA` | `InstitutionalProposalCfda` | FK `PROPOSAL_ID` | NOT YET ARCHIVED |
| `PROPOSAL_CUSTOM_DATA` | `InstitutionalProposalCustomData` | FK `PROPOSAL_ID`; **3,465,477 rows in Oracle** (live-verified — a genuinely large EAV-style table, size worth planning for) | NOT YET ARCHIVED |
| `PROPOSAL_STATE` | `ProposalState` (`propdev`) | — | **NOT APPLICABLE — belongs to ProposalDevelopment, not this domain** (new finding, see Financial section) |
| `AWARD_FUNDING_PROPOSALS` | `AwardFundingProposal` | FK both `AWARD_ID` and `PROPOSAL_ID` (exact versions); resolved family-wide+active+non-Cancelled in practice | COMPLETE on the Award side (`archive.award_funding_proposal`); independently mirrored as `archive.proposal_award` (also currently 0 rows loaded) |

See `PROPOSAL_ARCHIVE_COVERAGE.md`'s full 24-file DataDictionary matrix
for every remaining table (cost share, F&A, science keyword, special
review, notepad, etc.) — not repeated here.

## Existing archive audit

Beyond the coverage doc's schema-completeness matrix, this
investigation checked **actual loaded data**, live, right now:

```
archive.proposal_version: 0 rows
archive.proposal_award:   0 rows
archive.proposal_person:  table does not exist (dropped by V033, as expected)
```

**The Proposal domain has never been loaded into this archive at all**
— not "partially loaded," genuinely empty, despite `V015`/`V016`
having been applied (confirmed via `public.schema_migration`: versions
15, 16, 33 all recorded as applied). This matches
`GET /api/dashboard`'s live `"proposals": 0, "proposalHistoryRecords": 0`
(observed independently during this session's Global Search
verification). Any future Proposal implementation work needs a real
ETL load pass, not just schema/code changes, before it will show
anything live.

The existing `archive.proposal_version` schema (`V015`) also does not
carry a real `document_number` column at all, despite that field being
proven above as the correct per-version workflow-document identifier —
worth planning for in any schema revision, alongside the ~20 other
missing scalar `PROPOSAL` columns the coverage doc already lists.

## Real verification fixture

**`PROPOSAL_NUMBER = '205'`**, two versions:
- `PROPOSAL_ID` 212, sequence 1, `ARCHIVED`, document `115569`
- `PROPOSAL_ID` 2986, sequence 2, `ACTIVE` (current), document `125761`,
  title *"Quality of Care in the Treatment of Burn Injuries: Validation
  of Clinical Guidelines and Their Impact on Patient Outcomes"*,
  sponsor code `301957`, lead unit `1262160000`, `STATUS_CODE` 2
  (Funded), `TOTAL_DIRECT_COST_INITIAL/TOTAL` = 10519.00,
  `TOTAL_INDIRECT_COST_INITIAL/TOTAL` = 2735.00.

Cross-domain link: `AWARD_FUNDING_PROPOSAL_ID` 148183 → `AWARD_ID`
148155, `PROPOSAL_ID` 2986, `ACTIVE = 'Y'` (a real, live, currently
active funding relationship — this Award is not otherwise a fixture
used elsewhere in this project's Award documentation, chosen because it
was the first real match found, not cherry-picked).

`PROPOSAL_LOG` for this family: `LOG_STATUS = '3'` (Submitted),
`PROPOSAL_LOG_TYPE_CODE = '1'` (Permanent), `INST_PROPOSAL_NUMBER =
NULL` (the naming-trap field, proven unreliable — see Proposal Log
section), `MERGED_WITH = NULL` (never merged, consistent with being a
real, standalone, successfully-promoted family).

`PROPOSAL_PERSONS`: one row, PI Lois K Horwitz (`U56572816`), `roleCode
= 'PI'`. `PROPOSAL_PERSON_UNITS`: one row, unit `1262160000`,
`LEAD_UNIT_FLAG = 'Y'` (agrees with `PROPOSAL.LEAD_UNIT_NUMBER`).
`PROPOSAL_UNIT_CONTACTS`: one row, Andrea Cozzi (`U19663726`), type
`CONTACT` — a different person than the PI, confirming the two tables
are genuinely separate rosters. `PROPOSAL_COMMENTS`: two rows, types 12
("Proposal Comments") and 13 ("Proposal IP Review Comments").
`PROPOSAL_ATTACHMENTS`: zero rows for this fixture (attachment
architecture proven structurally, not exercised end-to-end here — see
Attachments section).

This fixture is real, live, and simultaneously exercises every
relationship this document proves — recommended as the primary fixture
for any future Proposal implementation/testing work, the same role
`103692-00002`/`105698-00002` play for Award/Budget.

## Do not implement yet

Per explicit instruction: no Repository/Service/Controller/DTO/React
code has been written or changed as part of this investigation. This
document, together with the existing `PROPOSAL_ARCHIVE_COVERAGE.md`,
is the complete proof-before-code deliverable for Institutional
Proposal — implementation should follow only after review of both.

## Date last updated

2026-08-04 (initial investigation: Proposal identity/versioning model,
Proposal Log conversion flow including the `INST_PROPOSAL_NUMBER`
naming trap, `AWARD_FUNDING_PROPOSALS` family-wide/active/non-Cancelled
resolution rule proven symmetric on both sides, People/Units/Contacts
relationships including a possible reopening path for the People
removal decision, Comments' shared `COMMENT_TYPE` reuse, Attachments'
already-documented Subaward-shaped `FILE_DATA`/`FILE_DATA_ID` reuse
path, financial-fields scope confirmed to exclude any Proposal Budget
concept, `PROPOSAL_STATE` confirmed out of scope (`propdev`, not this
domain), and confirmation that the Proposal archive currently holds
zero live rows despite schema existing since V015/V016 — all against
real Kuali `.java`/OJB source and live Oracle data, fixture Proposal
family `205`).
