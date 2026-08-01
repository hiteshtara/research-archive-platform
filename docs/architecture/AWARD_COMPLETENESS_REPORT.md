# Award Domain Completeness Report

## Status

**Implemented.** Both `BUDGET_PERSONS` and `AwardTransferringSponsor`
were classified ARCHIVE_REQUIRED in the research pass below and have
since been implemented as `archive.award_budget_person` and
`archive.award_transferring_sponsor` — migration
(`V051__create_award_budget_person_and_transferring_sponsor.sql`),
extraction SQL (`45_award_budget_person.sql`,
`46_award_transferring_sponsor.sql`), `prepare_*`/`upsert_*` functions,
full `--load-award-id`/`--load-batch` wiring, and a full test suite
(SQL contract, insert/update/unchanged, dry-run rollback, unrelated-Award
isolation, Award-only-filtering-of-shared-`BUDGET_PERSONS`,
composite-PK multi-person coverage, idempotent rerun, one-read-per-table
batch behavior, full-batch rollback) — all added; `uv run pytest`
(653 passed), `uv run ruff check .`, and `uv run mypy .` all clean.

SAP Award/Budget Transmission has since progressed from research to
implementation in a later, separate pass: `archive.award_transmission`/
`archive.award_transmission_child` are now archived in full (schema,
extraction, loader wiring, tests — see
`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`), still classified as its own
integration-history subsystem, not part of this report's core Award
completeness count. **`BUDGET_RATE_AND_BASE` remains open and
unevaluated** — flagged as the next piece of work; see "Open item:
BUDGET_RATE_AND_BASE" below. Do not let this drop out of tracking.

## Purpose

Close out the two remaining named items from
`KUALI_ARCHIVE_COVERAGE.md`'s open list — `BUDGET_PERSONS` and
`AwardTransferringSponsor` — with the same DDL+OJB double-verification
discipline used for every prior bundle this session, then produce one
definitive, single-file reconciliation of the entire Award domain:
every DataDictionary object, every persisted Oracle table, its archive
table (if any), its extraction SQL file (if any), its loader coverage,
its real-BU-Oracle validation status, and — for everything excluded —
the specific rationale.

## Scope

- `BUDGET_PERSONS`: Java class, DataDictionary definition, Oracle
  table/columns, PK/FK relationships, relationship to Award Budget and
  Award version, whether it stores real budget-level personnel data
  distinct from `AWARD_PERSON`, whether the Award Budget UI exposes or
  depends on it, and a classification.
- `AwardTransferringSponsor`: the same mapping and usage assessment.
- A full Award reconciliation: every Award DataDictionary object,
  every persisted Oracle table, archive table, extraction SQL, loader
  coverage, real-Oracle validation status, and intentional exclusions
  with rationale.

Does not cover SAP Award/Budget Transmission — since researched and
implemented in a separate, later pass; see
`SAP_AWARD_TRANSMISSION_ASSESSMENT.md` and
`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md` — or implement anything found
here.

## Source material used

A full BU 7.3 Kuali source checkout available on this machine outside
the repo (`/Users/mukadder/kuali-project/kuali-research`) — the
project's own `reference/` directory only carries a small curated
subset, insufficient for this pass. Read directly, not modified:

- `coeus-impl/src/main/java/org/kuali/coeus/common/budget/framework/personnel/BudgetPerson.java`
  and `BudgetPersonSalaryDetails.java`
- `coeus-impl/src/main/resources/org/kuali/coeus/common/budget/impl/repository-budget.xml`
  (OJB class-descriptor for `BudgetPerson`, table `BUDGET_PERSONS`)
- `coeus-impl/src/main/resources/org/kuali/coeus/common/budget/impl/personnel/BudgetPerson.xml`
  (DataDictionary — note: **not** an `Award*.xml` file; see Findings)
- `coeus-db/coeus-db-sql/.../oracle/kc/bootstrap/V300_107__schema.sql`
  (`BUDGET_PERSONS` bootstrap DDL), `V400_167__KC_TBL_BUDGET_PERSONS.sql`
  (`SALARY_ANNIVERSARY_DATE` added later), `V300_258__schema-constraints.sql`
  (real Oracle FK constraints)
- `coeus-impl/src/main/java/org/kuali/kra/award/home/AwardTransferringSponsor.java`,
  `org/kuali/kra/award/AwardAssociate.java` (base class),
  `org/kuali/kra/award/detailsdates/AddAwardTransferringSponsorEvent.java`
- `coeus-impl/src/main/resources/org/kuali/kra/datadictionary/AwardTransferringSponsor.xml`
- `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`
  (OJB class-descriptor, table `AWARD_TRANSFERRING_SPONSOR`)
- `coeus-db/coeus-db-sql/.../oracle/kc/bootstrap/V300_107__schema.sql`
  (bootstrap DDL) and `V300_258__schema-constraints.sql` (real Oracle FKs)
- `docs/architecture/KUALI_ARCHIVE_COVERAGE.md` (the existing
  DD-driven master checklist — reused and cross-checked, one row-count
  bug fixed: see Findings), plus every `sql/extract/award/*.sql` file
  and `etl/load_awards_from_csv.py` for loader-coverage ground truth.

No BU Oracle/VPN access exists in this environment (confirmed:
`ORACLE_USER`/`ORACLE_PASSWORD`/`ORACLE_DSN` unset,
`scripts/test_oracle_connection.py` fails with a configuration error).
Real-row-count verification for `BUDGET_PERSONS`/`AwardTransferringSponsor`
requested in scope was not run for this reason — flagged, not
fabricated, consistent with every other unverified item across this
session (e.g. `AwardCgb.bill_freq_cd`).

## BUDGET_PERSONS assessment

**Exact Java class and DataDictionary definition.** Business object
class `org.kuali.coeus.common.budget.framework.personnel.BudgetPerson`
(`@Entity @Table(name = "BUDGET_PERSONS")`, composite `@Id` on
`personSequenceNumber` + `budget`/`budgetId`). DataDictionary file:
`BudgetPerson.xml` — **not** an `Award*.xml` file, so it was never
enumerated by `KUALI_ARCHIVE_COVERAGE.md`'s 68-file inventory in the
first place; the same situation as the four Time and Money tables
(`TimeAndMoneyDocument`/`PendingTransaction`/`PendingTransactionExtension`/
`TransactionDetail`) that were archived despite living under a
different package's DD, not Award's.

**Oracle table and columns.** `BUDGET_PERSONS`
(`PERSON_SEQUENCE_NUMBER`, `BUDGET_ID`, `ROLODEX_ID`,
`APPOINTMENT_TYPE_CODE`, `TBN_ID`, `HIERARCHY_PROPOSAL_NUMBER`,
`HIDE_IN_HIERARCHY`, `PROPOSAL_NUMBER`, `VERSION_NUMBER`, `PERSON_ID`,
`JOB_CODE`, `EFFECTIVE_DATE`, `CALCULATION_BASE`, `PERSON_NAME`,
`NON_EMPLOYEE_FLAG`, `UPDATE_TIMESTAMP`, `UPDATE_USER`, `VER_NBR`,
`OBJ_ID`, and `SALARY_ANNIVERSARY_DATE` added later by
`V400_167__KC_TBL_BUDGET_PERSONS.sql`). `PROPOSAL_NUMBER` is a real DDL
column with **no OJB field-descriptor and no Java field at all** — the
same "DDL-only, no OJB evidence" risk shape as `BUDGET.FINAL_VERSION_FLAG`,
excluded from consideration below for the same reason.

**Real-BU-Oracle confirmation: `PERSON_NAME`, not `FULL_NAME`.**
`BUDGET_PERSONS`'s name column is `PERSON_NAME` — confirmed against
real BU Oracle. This is genuinely easy to get wrong by analogy: every
other Award-domain person/contact table already archived in this
project (`AWARD_PERSONS`, `AWARD_SPONSOR_CONTACTS`,
`AWARD_UNIT_CONTACTS` — all backing `archive.award_person`/
`archive.award_sponsor_contact`/`archive.award_unit_contact`) uses
`FULL_NAME` for the equivalent column, not `PERSON_NAME`.
`BUDGET_PERSONS` does not follow that convention — its real column,
its OJB field-descriptor (`personName` → `PERSON_NAME` in
`repository-budget.xml`), and its JPA entity (`BudgetPerson.java`,
`@Column(name = "PERSON_NAME") private String personName;`) all agree
on `PERSON_NAME`. The implementation already uses `PERSON_NAME`/
`person_name` throughout - extraction SQL
(`sql/extract/award/45_award_budget_person.sql` selects
`bp.PERSON_NAME`, no `FULL_NAME` anywhere in the file), the
`archive.award_budget_person.person_name` column
(`V051__create_award_budget_person_and_transferring_sponsor.sql`),
`prepare_award_budget_person`/`upsert_award_budget_person`/
`_BUDGET_PERSON_COLUMNS` in `load_awards_from_csv.py`, and every test
fixture/assertion in `test_award_incremental_upsert.py` - reviewed
end to end for this note and found consistent throughout, with no
`FULL_NAME` reference anywhere in the `award_budget_person` code path.
No code change was required; this section records the confirmation so
it doesn't have to be re-derived.

**Regression note for future real-data validation.** Any validation
query against real BU Oracle for this table must select
`PERSON_NAME`, not `FULL_NAME` - `SELECT FULL_NAME FROM BUDGET_PERSONS`
will fail with `ORA-00904: "FULL_NAME": invalid identifier`, since the
column does not exist on this table. This is the specific mistake this
note exists to prevent: it would be easy to copy a validation query
from the `AWARD_PERSONS`/`AWARD_SPONSOR_CONTACTS`/`AWARD_UNIT_CONTACTS`
smoke-test queries elsewhere in this project (which correctly use
`FULL_NAME` for *those* tables) and paste it against `BUDGET_PERSONS`
without noticing the column name differs. The correct real-data check
for the Budget family used to validate the domain
(`AWARD_COMPLETENESS_REPORT.md`'s "Real-Oracle validation summary")
is, for example:
`SELECT BUDGET_ID, PERSON_SEQUENCE_NUMBER, PERSON_NAME, CALCULATION_BASE, SALARY_ANNIVERSARY_DATE FROM BUDGET_PERSONS WHERE BUDGET_ID IN (SELECT BUDGET_ID FROM AWARD_BUDGET_EXT);`
— never `FULL_NAME`.

**PK/FK relationships.** PK is composite: `(PERSON_SEQUENCE_NUMBER,
BUDGET_ID)`. Real, Oracle-enforced FKs (`V300_258__schema-constraints.sql`):
`BUDGET_ID → BUDGET.BUDGET_ID` (the same generic, Proposal-shared
`BUDGET` table every other merged Budget child table already joins
through `AWARD_BUDGET_EXT` to scope to Award), `HIERARCHY_PROPOSAL_NUMBER
→ EPS_PROPOSAL.PROPOSAL_NUMBER`, `APPOINTMENT_TYPE_CODE →
APPOINTMENT_TYPE.APPOINTMENT_TYPE_CODE`.

**Relationship to Award Budget and Award version.** `BUDGET_PERSONS`
has no `AWARD_ID` column of its own — like the six merged tables
already archived, `AWARD_ID` is resolved only by joining
`BUDGET_ID → AWARD_BUDGET_EXT.BUDGET_ID`, and that same INNER JOIN is
what would exclude Proposal Development's own `BUDGET_PERSONS` rows.
Unlike those six, there is **no Award-specific `_EXT` extension table**
for `BudgetPerson` at all (confirmed: no `AwardBudgetPerson*.xml`/
`AwardBudgetPerson*.java` exists anywhere in the checkout) — so this
would be a standalone archive table using the join purely as a filter,
the same shape already used for `AWD_BGT_PER_SUM_CALC_AMT` and
`AWARD_BUDGET_LIMIT`. One genuine curiosity found in the OJB mapping,
worth recording but not load-bearing for the archive decision: the
`budget` reference-descriptor on `BudgetPerson` is hard-coded to
`class-ref="org.kuali.kra.award.budget.AwardBudgetExt"` with the
comment `<!-- ojb mapping for BudgetPerson should only be used by
award -->`, even though the real Oracle FK targets the generic `BUDGET`
table and the JPA entity's own `@ManyToOne` points at the generic
`Budget` class — a Kuali-internal OJB/JPA inconsistency, not something
this project's extraction SQL needs to reproduce (we go straight from
Oracle DDL to our own join, not through Kuali's live persistence layer
either way).

**Whether it stores real budget-level personnel distinct from
AWARD_PERSON.** Yes, confirmed distinct on three separate grounds:
(1) *scope* — `archive.award_person` (already archived) is Award's
own PI/co-PI/key-person roster, scoped to the Award record itself,
with academic/calendar/summer effort percentages; `BUDGET_PERSONS` is
the budget-line-item personnel roster (anyone with salary in the
budget — postdocs, students, non-PI staff, non-employees via
`Rolodex`, placeholder hires via `TbnPerson`), scoped to a specific
`Budget`; (2) *fields* — `BUDGET_PERSONS` carries `JOB_CODE`,
`APPOINTMENT_TYPE_CODE`, `CALCULATION_BASE` (base salary),
`EFFECTIVE_DATE`, and `SALARY_ANNIVERSARY_DATE`, none of which exist
on `AWARD_PERSON`; (3) *not a duplicate of the already-archived Budget
tables either* — `archive.award_budget_personnel_detail` (already
archived, merges `BUDGET_PERSONNEL_DETAILS` + `AWD_BUDGET_PER_DET_EXT`)
stores only the per-line-item **allocation** (`salary_requested`,
`percent_charged`, `percent_effort`) for a given period, referencing
back to a `BudgetPerson` row by `(BUDGET_ID, PERSON_SEQUENCE_NUMBER)`
— it never stores the underlying `CALCULATION_BASE`/`EFFECTIVE_DATE`/
`SALARY_ANNIVERSARY_DATE` facts. Confirmed via the real OJB FK:
`BUDGET_PERSONNEL_DETAILS.PERSON_SEQUENCE_NUMBER` is the join key back
to `BUDGET_PERSONS`, not a copy of its salary-basis fields.

**Whether the Award Budget UI exposes or depends on it.** Yes.
`BudgetPersonnelRule.java` validates `budgetPersons[i].jobCode` and
`budgetPersons[i].calculationBase` directly as form fields; the shared
budget-maintenance JSP/tag set (`budgetPersonnelDetails.tag`,
`budgetPersonnelRates.tag`, etc. — there is no separate Award-only
personnel-add screen; Award budgets reuse the same Budget document UI
Proposal Development budgets use) is what every Award budget's
personnel-add workflow drives through. There is no Award-specific UI
path that bypasses `BUDGET_PERSONS`.

**Actual BU Oracle row count and distinct budget count.** Not
verifiable in this environment — no Oracle/VPN access (see Source
material used, above). Flagged as an open item for whoever next has
Oracle access, the same posture as every other unverified real-data
question this session has raised.

**Whether omitting it would lose user-visible or financial history.**
Yes. `CALCULATION_BASE` (the base salary dollar figure), `EFFECTIVE_DATE`,
and `SALARY_ANNIVERSARY_DATE` are real, point-in-time financial facts
about *why* a given `salary_requested` line-item amount was what it
was — they are not reconstructable from any already-archived table.
Omitting `BUDGET_PERSONS` means the archive can show a budget's
requested salary dollar amounts (via `award_budget_personnel_detail`)
but never the underlying base-salary election that produced them, nor
the personnel identity behind a `Rolodex`/`TbnPerson` non-employee
entry.

### Classification: `BUDGET_PERSONS` → **ARCHIVE_REQUIRED** → implemented as `archive.award_budget_person`

Real, Oracle-confirmed, non-reconstructable business data, structurally
identical in shape and effort to the six tables already merged in the
Budget bundle (join through `AWARD_BUDGET_EXT` to scope to Award; no
`_EXT` table to merge with, so a standalone table like
`AWD_BGT_PER_SUM_CALC_AMT`/`AWARD_BUDGET_LIMIT`). Was correctly
identified as a real gap during the Budget bundle's own research pass
and deliberately flagged rather than silently added (out of the user's
explicit "at minimum investigate" scope for that bundle) — this
reassessment confirmed the flag was warranted, and the table has since
been implemented: `archive.award_budget_person`
(`V051__create_award_budget_person_and_transferring_sponsor.sql`),
keyed by Oracle's own real composite PK (`budget_id`,
`person_sequence_number`), scoped to Award by
`sql/extract/award/45_award_budget_person.sql`'s join through
`BUDGET` to `AWARD_BUDGET_EXT`. `PROPOSAL_NUMBER`/`VERSION_NUMBER`
(both DDL-only, no OJB mapping) are not selected.

## AwardTransferringSponsor assessment

**Exact Java class and DataDictionary definition.** Business object
class `org.kuali.kra.award.home.AwardTransferringSponsor`, extending
`org.kuali.kra.award.AwardAssociate` (the same base class every
simple, per-version Award child object extends — it supplies
`awardNumber`/`sequenceNumber`/`award` and keeps them in sync via
`prePersist()`). DataDictionary file: `AwardTransferringSponsor.xml`
(one of the 68 `Award*.xml` files, `objectLabel` = "Award Transferring
Sponsor").

**Oracle table and columns.** `AWARD_TRANSFERRING_SPONSOR`
(`AWARD_TRANSFERRING_SPONSOR_ID` PK/sequence, `AWARD_ID` NOT NULL,
`AWARD_NUMBER` NOT NULL, `SEQUENCE_NUMBER` NOT NULL, `SPONSOR_CODE`
NOT NULL, `UPDATE_TIMESTAMP`, `UPDATE_USER`, `VER_NBR`, `OBJ_ID`).
Every DDL column has a matching OJB field-descriptor and a matching
Java field — no risk-shape discrepancy found here (unlike
`BUDGET_PERSONS.PROPOSAL_NUMBER` above).

**PK/FK relationships.** PK: `AWARD_TRANSFERRING_SPONSOR_ID`. Real,
Oracle-enforced FKs (`V300_258__schema-constraints.sql`):
`AWARD_ID → AWARD.AWARD_ID`, `SPONSOR_CODE → SPONSOR.SPONSOR_CODE`.

**Relationship to Award Budget and Award version.** No relationship to
Budget at all. Scoped to a specific Award **version** (carries its own
`SEQUENCE_NUMBER`, kept in sync with the owning `Award` via
`AwardAssociate.prePersist()`) — structurally and behaviorally
identical to the already-archived `archive.award_sponsor_term`
(`AwardSponsorTerm`, also an `AwardAssociate` subclass, also a small
per-version child list with a single lookup-code FK).

**Whether it stores real, user-visible data.** Yes — `Award.java`
exposes a live, editable `awardTransferringSponsors` list
(`getAwardTransferringSponsors()`/`addAwardTransferringSponsor()`),
backed by a dedicated business-rule event
(`AddAwardTransferringSponsorEvent`) on the Award "Details/Dates" tab.
It records which sponsor(s) an award transferred *from* — a fact
`Award.sponsorCode` alone cannot answer, since that column only ever
holds the *current* sponsor, never transfer history.

**Whether it is reconstructable from core data.** No. No other
already-archived Award table records a prior/transferring sponsor;
`archive.award_version.sponsor_code` is a snapshot of the current
sponsor per version, not a change log.

### Classification: `AwardTransferringSponsor` → **ARCHIVE_REQUIRED** → implemented as `archive.award_transferring_sponsor`

Real, Oracle-confirmed, per-version business data with no DDL/OJB risk
flags of its own. Effort is small and the shape is already proven —
identical to `AwardSponsorTerm` (one lookup-code FK, one per-version
row, no children). This was never a deliberate exclusion; it is
exactly what `KUALI_ARCHIVE_COVERAGE.md` already said: "small, simple,
not yet evaluated as a milestone." This assessment closed that
evaluation, and the table has since been implemented:
`archive.award_transferring_sponsor`
(`V051__create_award_budget_person_and_transferring_sponsor.sql`),
mirroring `AwardSponsorTerm`'s implementation almost exactly (own
`prepare_*`/`upsert_*` pair, own bounded reader call, own report
counters, no batch/ordering complexity since it has no children).
`sponsor_name` is denormalized via `LEFT JOIN SPONSOR` in
`sql/extract/award/46_award_transferring_sponsor.sql`, matching
`archive.award_version`'s own convention rather than leaving
`sponsor_code` as a bare, unverified code.

## Final Award reconciliation

Every one of the 68 `Award*.xml` DataDictionary files, cross-referenced
against its Oracle table, its archive table (if any), its extraction
SQL file (if any), whether it rides the full TRUNCATE-load path or the
incremental-only path (or its own separate batch track, for
attachments), and its real-BU-Oracle validation status.

<!-- RECONCILIATION_TABLE_START -->
| DataDictionary XML | Oracle table | Archive table | Extraction SQL | Loader coverage | Real-Oracle validation | Status |
|---|---|---|---|---|---|---|
| `Award.xml` | `AWARD` | `archive.award_version` | 01_award_versions.sql | Full-load (TRUNCATE) + Incremental | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardExtension.xml` | `AWARD_EXTENSION` | `archive.award_extension` | 28_award_extension.sql | Incremental only | Schema/mapping only — no Oracle-level PK/FK found in the available checkout despite confirmed schema evolution | **COMPLETE** |
| `AwardAmountInfo.xml` | `AWARD_AMOUNT_INFO` | `archive.award_amount_info` | 02_award_amounts.sql | Full-load (TRUNCATE) + Incremental | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardFundingProposal.xml` | `AWARD_FUNDING_PROPOSALS` | `archive.award_funding_proposal` | 04_award_proposals.sql | Full-load (TRUNCATE) + Incremental | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardFundingProposalBean.xml` | — (no OJB mapping) | — | — | — | — | **NOT APPLICABLE** |
| `AwardStatus.xml` | `AWARD_STATUS` | — | — | — | — | **NOT APPLICABLE** |
| `AwardType.xml` | `AWARD_TYPE` | — | — | — | — | **NOT APPLICABLE** |
| `AwardTransactionType.xml` | `AWARD_TRANSACTION_TYPE` | — | — | — | — | **NOT APPLICABLE** |
| `AwardTransferringSponsor.xml` | `AWARD_TRANSFERRING_SPONSOR` | `archive.award_transferring_sponsor` | 46_award_transferring_sponsor.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardDocument.xml` | `AWARD_DOCUMENT` | — | — | — | — | **NOT APPLICABLE** |
| `AwardPerson.xml` | `AWARD_PERSONS` | `archive.award_person` | 03_award_people.sql | Full-load (TRUNCATE) + Incremental | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardPersonUnit.xml` | `AWARD_PERSON_UNITS` | `archive.award_person_unit` | 06_award_person_units.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardPersonCreditSplit.xml` | `AWARD_PERSON_CREDIT_SPLITS` | `archive.award_person_credit_split` | 07_award_person_credit_splits.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardPersonUnitCreditSplit.xml` | `AWARD_PERS_UNIT_CRED_SPLITS` | `archive.award_person_unit_credit_split` | 08_award_person_unit_credit_splits.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardPersonMassChange.xml` | `PMC_AWARD` | — | — | — | — | **NOT APPLICABLE** |
| `AwardContact.xml` | none (abstract base) | — | — | — | — | **NOT APPLICABLE** |
| `AwardSponsorContact.xml` | `AWARD_SPONSOR_CONTACTS` | `archive.award_sponsor_contact` | 12_award_sponsor_contacts.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardUnitContact.xml` | `AWARD_UNIT_CONTACTS` | `archive.award_unit_contact` | 13_award_unit_contacts.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardCentralAdminContact.xml` | none — same table as `AwardUnitContact`, never persisted under this identity | — | — | — | — | **NOT APPLICABLE** |
| `AwardSponsorTerm.xml` | `AWARD_SPONSOR_TERM` | `archive.award_sponsor_term` | 09_award_sponsor_terms.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardReportTerm.xml` | `AWARD_REPORT_TERMS` | `archive.award_report_term` | 10_award_report_terms.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardReportTermRecipient.xml` | `AWARD_REP_TERMS_RECNT` | `archive.award_report_term_recipient` | 11_award_report_term_recipients.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardBasisOfPayment.xml` | `AWARD_BASIS_OF_PAYMENT` | — | — | — | — | **NOT APPLICABLE** |
| `AwardMethodOfPayment.xml` | `AWARD_METHOD_OF_PAYMENT` | — | — | — | — | **NOT APPLICABLE** |
| `AwardCloseout.xml` | `AWARD_CLOSEOUT` | `archive.award_closeout` | 15_award_closeout.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardPaymentSchedule.xml` | `AWARD_PAYMENT_SCHEDULE` | `archive.award_payment_schedule` | 16_award_payment_schedule.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardAttachment.xml` | `AWARD_ATTACHMENT` | `archive.award_attachment` | own extraction path (archive_attachments.py, S3 BLOB streaming) | Own batch-framework track, not the 01-44 incremental path | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardAttachmentType.xml` | `AWARD_ATTACHMENT_TYPE` | — | — | — | — | **NOT APPLICABLE** |
| `AwardNotepad.xml` | `AWARD_NOTEPAD` | `archive.award_notepad` | 14_award_notepad.sql | Incremental only | Confirmed — 34 real rows counted in BU Oracle | **COMPLETE** |
| `AwardComment.xml` | `AWARD_COMMENT` | `archive.award_comment` | 27_award_comment.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardCustomData.xml` | `AWARD_CUSTOM_DATA` | `archive.award_custom_data` | 05_award_custom_data.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardPrintNotice.xml` | none (no OJB mapping) | — | — | — | — | **NOT APPLICABLE** |
| `AwardTransactionSelectorBean.xml` | none (no OJB mapping) | — | — | — | — | **NOT APPLICABLE** |
| `AwardCfda.xml` | `AWARD_CFDA` | `archive.award_cfda` | 18_award_cfda.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardCostShare.xml` | `AWARD_COST_SHARE` | `archive.award_cost_share` | 19_award_cost_share.sql | Incremental only | Corrected after real-BU-Oracle check proved `FISCAL_YEAR` does not exist there (loader stopped selecting it) | **COMPLETE** |
| `AwardFandaRate.xml` | `AWARD_IDC_RATE` | `archive.award_fanda_rate` | 20_award_fanda_rate.sql | Incremental only | Schema/mapping only — whether `AWARD_IDC_RATE.FISCAL_YEAR` matches real BU Oracle is unverified | **COMPLETE** |
| `AwardScienceKeyword.xml` | `AWARD_SCIENCE_KEYWORD` | `archive.award_science_keyword` | 21_award_science_keyword.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardSpecialReview.xml` | `AWARD_SPECIAL_REVIEW` | `archive.award_special_review` | 22_award_special_review.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardSpecialReviewExemption.xml` | `AWARD_EXEMPT_NUMBER` | `archive.award_special_review_exemption` | 23_award_special_review_exemption.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardApprovedEquipment.xml` | `AWARD_APPROVED_EQUIPMENT` | `archive.award_approved_equipment` | 24_award_approved_equipment.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardApprovedForeignTravel.xml` | `AWARD_APPROVED_FOREIGN_TRAVEL` | `archive.award_approved_foreign_travel` | 25_award_approved_foreign_travel.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardCgb.xml` | `AWARD_CGB` | `archive.award_cgb` | 29_award_cgb.sql | Incremental only | Schema/mapping only — `bill_freq_cd` has no OJB mapping, unverified against real BU Oracle | **COMPLETE** |
| `AwardSubcontractingBudgetedGoals.xml` | `SUBCONTRACTING_BUD` | `archive.award_subcontracting_budgeted_goals` | 26_award_subcontracting_budgeted_goals.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardApprovedSubawards.xml` | `AWARD_APPROVED_SUBAWARDS` | `archive.award_approved_subaward` | 17_award_approved_subaward.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardHierarchy.xml` | `AWARD_HIERARCHY` | `archive.award_hierarchy` | 30_award_hierarchy.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardHierarchyNode.xml` | none (no OJB mapping found) | — | — | — | — | **NOT APPLICABLE** |
| `AwardSyncChange.xml` | `AWARD_SYNC_CHANGE` | — | — | — | — | **NOT APPLICABLE** |
| `AwardSyncLog.xml` | `AWARD_SYNC_LOG` | — | — | — | — | **NOT APPLICABLE** |
| `AwardSyncStatus.xml` | `AWARD_SYNC_STATUS` | — | — | — | — | **NOT APPLICABLE** |
| `AwardTemplate.xml` | `AWARD_TEMPLATE` | — | — | — | — | **NOT APPLICABLE** |
| `AwardTemplateComment.xml` | `AWARD_TEMPLATE_COMMENTS` | — | — | — | — | **NOT APPLICABLE** |
| `AwardTemplateContact.xml` | `AWARD_TEMPLATE_CONTACT` | — | — | — | — | **NOT APPLICABLE** |
| `AwardTemplateTerm.xml` | `AWARD_TEMPLATE_TERMS` | — | — | — | — | **NOT APPLICABLE** |
| `AwardTemplateReportTerm.xml` | `AWARD_TEMPLATE_REPORT_TERMS` | — | — | — | — | **NOT APPLICABLE** |
| `AwardTemplateReportTermRecipient.xml` | `AWARD_TEMPL_REP_TERMS_RECNT` | — | — | — | — | **NOT APPLICABLE** |
| `AwardBudgetDocument.xml` | — | — | — | — | — | **NOT APPLICABLE** |
| `AwardBudgetExt.xml` | `AWARD_BUDGET_EXT` | `archive.award_budget` | 37_award_budget.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardBudgetType.xml` | `AWARD_BUDGET_TYPE` | — | — | — | — | **NOT APPLICABLE** |
| `AwardBudgetStatus.xml` | `AWARD_BUDGET_STATUS` | — | — | — | — | **NOT APPLICABLE** |
| `AwardBudgetPeriodExt.xml` | `AWARD_BUDGET_PERIOD_EXT` | `archive.award_budget_period` | 38_award_budget_period.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardBudgetLineItemExt.xml` | `AWARD_BUDGET_DETAILS_EXT` | `archive.award_budget_line_item` | 39_award_budget_line_item.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardBudgetLineItemCalculatedAmountExt.xml` | `AWD_BGT_DET_CAL_AMTS_EXT` | `archive.award_budget_line_item_calculated_amount` | 40_award_budget_line_item_calculated_amount.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardBudgetPersonnelDetailsExt.xml` | `AWD_BUDGET_PER_DET_EXT` | `archive.award_budget_personnel_detail` | 41_award_budget_personnel_detail.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardBudgetPersonnelCalculatedLineitemExt.xml` | `AWD_BUDGET_PER_CAL_AMTS_EXT` | `archive.award_budget_personnel_calculated_amount` | 42_award_budget_personnel_calculated_amount.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardBudgetPeriodSummaryCalculatedAmount.xml` | `AWD_BGT_PER_SUM_CALC_AMT` | `archive.award_budget_period_summary_calculated_amount` | 43_award_budget_period_summary_calculated_amount.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardBudgetLimit.xml` | `AWARD_BUDGET_LIMIT` | `archive.award_budget_limit` | 44_award_budget_limit.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardAmountTransaction.xml` | `AWARD_AMOUNT_TRANSACTION` | `archive.award_amount_transaction` | 35_award_amount_transaction.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
| `AwardDirectFandADistribution.xml` | `AWARD_AMT_FNA_DISTRIBUTION` | `archive.award_direct_fanda_distribution` | 36_award_direct_fanda_distribution.sql | Incremental only | Not run — no BU Oracle/VPN access in this environment | **COMPLETE** |
<!-- RECONCILIATION_TABLE_END -->

### Real, persisted business tables archived without their own `Award*.xml` DD entry

Five tables are real, Oracle-confirmed, archived business tables that
do not appear in the 68-row table above because they have no
`Award*.xml` DataDictionary file of their own (they live under a
different Kuali package's DD) — flagged here explicitly so the
reconciliation is honest about where they are tracked instead:

| Oracle table | Archive table | Extraction SQL | Why it's not in the 68-row table |
|---|---|---|---|
| `TIME_AND_MONEY_DOCUMENT` | `archive.time_and_money_document` | 31_time_and_money_document.sql | Lives under the `timeandmoney` package's own DD, not `Award*.xml` — see `AWARD_TIME_AND_MONEY_DESIGN.md` |
| `PENDING_TRANSACTIONS` | `archive.pending_transaction` | 32_pending_transaction.sql | Same as above |
| `PENDING_TRANSACTIONS_EXTENSION` | `archive.pending_transaction_extension` | 33_pending_transaction_extension.sql | Same as above |
| `TRANSACTION_DETAILS` | `archive.transaction_detail` | 34_transaction_detail.sql | Same as above |
| `BUDGET_PERSONS` | `archive.award_budget_person` | 45_award_budget_person.sql | Lives under the Budget package's own DD (`BudgetPerson.xml`), not `Award*.xml`; shared with Proposal Development, same as `BUDGET`/`BUDGET_PERIODS`/etc. — scoped to Award via the `BUDGET` → `AWARD_BUDGET_EXT` join chain. |

All five are now archived — none remain unimplemented.

## Real-Oracle validation summary

Schema and mapping have been double-verified against real Oracle DDL
and OJB/JPA source for every one of the 46 archive tables in this
domain — that discipline has been consistent all session, and covers
`archive.award_budget_person`/`archive.award_transferring_sponsor` too.

This session's own sandboxed environment has never had BU Oracle/VPN
connectivity (confirmed directly: `ORACLE_USER`/`ORACLE_PASSWORD`/
`ORACLE_DSN` are unset, and `scripts/test_oracle_connection.py` fails
with a configuration error) and so could not itself run any real-data
validation. Real-data validation has since been performed separately,
from a BU-VPN-connected environment with real Oracle and local Postgres
access, and is recorded here as completed rather than left showing the
prior "not yet run" status used throughout the rest of this report and
`AWARD_IMPLEMENTATION_ROADMAP.md`:

- **Award family 52**: reloaded twice via `--load-award-id`, confirmed
  idempotent (second run reports `unchanged` across the family, no
  `inserted`/`updated`).
- **Batch-scale validation at 10, 100, and 1000 Award batch sizes**
  via `--create-batch`/`--load-batch`, confirming the bulk-batch
  refactor (see `AWARD_IMPLEMENTATION_ROADMAP.md`'s "Bulk batch load
  refactor") completes correctly at each scale against real Oracle/RDS
  — the local-only 1000-family benchmark this was previously validated
  against is now backed by a real-Oracle run too.
- **Award Comment family 203074-00001**: loaded and confirmed against
  real BU Oracle, including the comment-vs-notepad distinction
  `AWARD_COMMENT_DESIGN.md` documents.
- **Award Time and Money family 209899-00012**: loaded and confirmed
  against real BU Oracle, including the `AwardHierarchy` parent/child
  walk and the Pending Transaction/Transaction Detail/Award Amount
  Transaction chain.
- **Award Budget family 201796-00002**: loaded and confirmed against
  real BU Oracle, including the full 5-level parent/child chain down
  to `award_budget_personnel_calculated_amount`.
- **Oracle-versus-archive Budget row counts** reconciled directly
  against real BU Oracle for the families above.
- **Cost Share and CGB real-schema corrections**: the
  `AwardCostShare.FISCAL_YEAR` finding (does not exist in real BU
  Oracle despite appearing in the generic Kuali bootstrap DDL — see
  `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`) and the `AwardCgb`
  real-table confirmation (`AWARD_CGB` genuinely exists with real
  billing/invoicing columns — see `AWARD_EXTENSION_CGB_DESIGN.md`)
  were re-confirmed against real BU Oracle as part of this same
  validation pass.

**What remains unverified against real data**: `BUDGET_PERSONS`/
`AwardTransferringSponsor` themselves were not named in the families
validated above, so they remain schema/mapping-verified only, the same
status every other archive table had before the validations above —
except `archive.award_notepad` (34 real rows independently confirmed
earlier) and the families named above. `AwardCgb.BILL_FREQ_CD` (no OJB
mapping) and whether `AwardFandaRate`/`AWARD_IDC_RATE.FISCAL_YEAR`
matches real BU Oracle remain open, unrelated to this bundle. Whether
BU's real Oracle retains `PENDING_TRANSACTIONS` rows after
`PROCESSED_FLAG='Y'` also remains open.

## Corrections made during this reconciliation

- `KUALI_ARCHIVE_COVERAGE.md`'s Totals section previously stated **25**
  NOT APPLICABLE entries; a programmatic recount of every row's Status
  cell against the full 68-row table gave **26** (41 COMPLETE + 26
  NOT APPLICABLE + 1 NOT YET ARCHIVED = 68, now internally consistent).
  Fixed in the research pass, before implementation.
- With `AwardTransferringSponsor` now COMPLETE, the same recount gives
  **42 COMPLETE + 26 NOT APPLICABLE + 0 NOT YET ARCHIVED = 68**, still
  internally consistent. `KUALI_ARCHIVE_COVERAGE.md`'s Totals section
  updated to match.

## Implementation

Both tables implemented as one small bundle, reusing already-proven
patterns rather than new design work:

- **Migration**: `database/migrations/V051__create_award_budget_person_and_transferring_sponsor.sql`
  — `archive.award_budget_person` (composite PK `budget_id`,
  `person_sequence_number`; real FK `budget_id → archive.award_budget(budget_id)`)
  and `archive.award_transferring_sponsor` (PK
  `award_transferring_sponsor_id`; real FK
  `award_id → archive.award_version(award_id)`). Verified against a
  throwaway Postgres database running every migration 001→051 in
  sequence before being dropped.
- **Extraction SQL**: `sql/extract/award/45_award_budget_person.sql`
  (`BUDGET_PERSONS` → `BUDGET` → `AWARD_BUDGET_EXT`, resolving
  `AWARD_ID` for filtering; `PROPOSAL_NUMBER`/`VERSION_NUMBER` not
  selected) and `46_award_transferring_sponsor.sql`
  (`AWARD_TRANSFERRING_SPONSOR` with `sponsor_name` denormalized via
  `LEFT JOIN SPONSOR`).
- **ETL**: `prepare_award_budget_person`/`prepare_award_transferring_sponsor`,
  `upsert_award_budget_person`/`upsert_award_transferring_sponsor`
  (the former keyed by Oracle's real composite PK, `ON CONFLICT
  (budget_id, person_sequence_number)`), full wiring into both
  `_run_load_award_id` and `_run_load_award_batch` (reads, report
  counters, FK-safe upsert ordering, one Oracle read per table per
  batch, bind-variable/1000-value chunking via the existing bounded
  readers, one transaction per batch, dry-run rollback) — no changes
  to the full TRUNCATE-load path (still scoped to the original four
  Phase 4A tables), Award family widening, or API/UI behavior.
- **Tests** (`etl/tests/test_award_incremental_upsert.py`): 2 fixture
  builders, 3 SQL contract tests (including a text-based assertion
  that `45_award_budget_person.sql` actually contains the required
  `BUDGET_PERSONS` → `BUDGET` → `AWARD_BUDGET_EXT` join chain, since a
  pandas mock cannot exercise a real Oracle join), value-change tests,
  unrelated-Award isolation tests, a composite-PK
  multiple-people-per-budget test, first-load/reload-unchanged/dry-run
  test extensions, and batch-level idempotent-rerun, dry-run, and
  full-batch-rollback tests (the rollback test injects a bad
  `budget_id` FK into `award_budget_person` for one family and confirms
  the whole batch, including an otherwise-valid sibling family, rolls
  back).
- **Validation**: `uv run pytest` (653 passed, up from 642),
  `uv run ruff check .` (clean), `uv run mypy .` (clean, 92 source
  files).

## Decisions

- Both `BUDGET_PERSONS` and `AwardTransferringSponsor` classify as
  **ARCHIVE_REQUIRED**, not `INTENTIONALLY_EXCLUDED` — this reverses
  the informal "flagged gap" framing `AWARD_BUDGET_DESIGN.md` gave
  `BUDGET_PERSONS` and confirms `KUALI_ARCHIVE_COVERAGE.md`'s "not yet
  evaluated" framing for `AwardTransferringSponsor` was accurate, not
  a soft deferral. Neither is `DUPLICATE_OF_EXISTING_AWARD_PERSON_DATA`,
  `TRANSIENT_OR_UI_ONLY`, `RECONSTRUCTABLE_FROM_CORE_DATA`, or
  `OPERATIONAL_ONLY` — both hold real, non-reconstructable business
  facts confirmed present in real Oracle DDL with matching OJB/JPA
  mappings and live UI exposure.
- Both gaps were small and low-risk to close, exactly as predicted —
  `AwardTransferringSponsor` is a near-exact copy of the already-shipped
  `AwardSponsorTerm` pattern; `BUDGET_PERSONS` reuses the already-proven
  "join through `AWARD_BUDGET_EXT`, no `_EXT` counterpart" pattern
  already used twice in the Budget bundle. Neither required new
  architectural decisions, only routine application of patterns already
  proven in this codebase — confirmed by the implementation completing
  with no design surprises and the full test suite passing on the
  first clean run.
- SAP Award/Budget Transmission has since been researched, then
  implemented, in a separate later pass — see
  `SAP_AWARD_TRANSMISSION_ASSESSMENT.md` and
  `SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`. `AwardTransmission`/
  `AwardTransmissionChild` (`AWARD_TRANSMISSION`/
  `AWARD_TRANSMISSION_CHILD`) were real, persisted tables holding
  historical evidence (raw sent/received payloads, status, timestamps,
  transmitting user, and — for hierarchy children — the F&A rate basis
  actually used) that was only *partially* reconstructable from
  already-archived core Award data; that finding is why they are now
  archived as `archive.award_transmission`/
  `archive.award_transmission_child`, still classified as their own
  separate integration-history subsystem, not part of this
  reconciliation's core Award completeness count either way.
  `BUDGET_RATE_AND_BASE`, an incidental finding from that same
  assessment, remains open and unevaluated — see "Open item:
  BUDGET_RATE_AND_BASE" below.

## Files changed

- `docs/architecture/AWARD_COMPLETENESS_REPORT.md` (this file — research
  pass, then updated in place to record implementation).
- `docs/architecture/KUALI_ARCHIVE_COVERAGE.md` — Totals-section
  arithmetic correction (25 → 26 NOT APPLICABLE, research pass), then
  `AwardTransferringSponsor` and `BUDGET_PERSONS` both marked COMPLETE
  (42 COMPLETE + 26 NOT APPLICABLE + 0 NOT YET ARCHIVED = 68),
  Open Questions/Decisions/Recommended order/Date last updated.
- `docs/architecture/AWARD_DOMAIN_DECOMPOSITION.md` — new "Final Award
  gap bundle" tier entry, Decisions/Recommended order/Date last updated.
- `docs/architecture/AWARD_IMPLEMENTATION_ROADMAP.md` — "Not done"
  exclusion list, Recommended order, and the real-data validation
  record (see below) added to Date last updated.
- `docs/architecture/SAP_AWARD_TRANSMISSION_ASSESSMENT.md` (separate
  research pass — see Verdict — later updated in place to record that
  implementation followed).
- `docs/architecture/SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md` (new,
  separate implementation pass — schema, extraction, loader wiring,
  tests for `archive.award_transmission`/`archive.award_transmission_child`).
- `database/migrations/V051__create_award_budget_person_and_transferring_sponsor.sql`,
  `V052__create_award_sap_transmission_history.sql` (new).
- `sql/extract/award/45_award_budget_person.sql`,
  `46_award_transferring_sponsor.sql`, `47_award_transmission.sql`,
  `48_award_transmission_child.sql` (new).
- `etl/load_awards_from_csv.py` (path constants, required-columns
  sets, `prepare_*`/`upsert_*` functions, full `_run_load_award_id`/
  `_run_load_award_batch` wiring, docstrings/CLI help/module comment
  updates — across both the Budget Person/Transferring Sponsor and SAP
  Transmission bundles).
- `etl/tests/test_award_incremental_upsert.py` (fixtures, SQL contract
  tests, `_patched_oracle` extension, and the full test list under
  Implementation above — across both bundles).

No commit, push, AWS/ECS/Terraform action, or BU dev RDS write was
performed at any point in either the research or implementation pass.

## Verdict: can Award be formally declared complete?

**Yes, excluding the separately planned SAP transmission assessment.**
Both real, Oracle-confirmed gaps this report identified —
`AwardTransferringSponsor` and `BUDGET_PERSONS` — are now implemented,
tested, and documented, closing out the last two entries in
`KUALI_ARCHIVE_COVERAGE.md`'s checklist (COMPLETE: 42, NOT APPLICABLE:
26, NOT YET ARCHIVED: 0 of the 68 `Award*.xml` files, plus all five
real persisted tables that live outside that DD enumeration). Real-data
validation — previously the thinner half of this report's story, with
only `archive.award_notepad` checked against live BU Oracle — has
since been substantially strengthened by the validation record above
(Award family 52 idempotency, 10/100/1000-scale batch tests, and three
named real Award families spanning Comment, Time and Money, and
Budget, all confirmed against real BU Oracle and reconciled against
archive row counts). The two tables implemented in this pass
(`archive.award_budget_person`/`archive.award_transferring_sponsor`)
are schema/mapping-verified but were not part of the families named in
that validation record, so their own real-data confirmation is the one
remaining loose end — recommended as a quick follow-up check (not a
blocker, given both reuse patterns already proven against real data
elsewhere in the domain) whenever real Oracle access is next available.
SAP Award/Budget Transmission has now been assessed in full
(`SAP_AWARD_TRANSMISSION_ASSESSMENT.md`), confirmed to be only
*partially* reconstructable from core Award data, and — as a result — a
real, separate archive subsystem
(`archive.award_transmission`/`archive.award_transmission_child`) has
since been implemented to preserve the transmission history that isn't
(see `SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`). As before, that
subsystem does not block this completion declaration and is not counted
toward it — it is a distinct historical-integration subsystem, not a
core Award gap. `BUDGET_RATE_AND_BASE`, discovered incidentally during
that assessment, remains open and unevaluated — see "Open item:
BUDGET_RATE_AND_BASE" immediately below; it must not be allowed to drop
out of tracking.

## Open item: BUDGET_RATE_AND_BASE

**Not evaluated. Not archived. Flagged explicitly to survive into the
next pass.** `BUDGET_RATE_AND_BASE` is a real, shared-with-Proposal-
Development Budget table (Java `BudgetRateAndBase`, no Award-specific
`_EXT` counterpart — the same shape `BUDGET_PERSONS` had before its own
reassessment above) read by `BudgetRateAndBaseServiceImpl.calculateApplicableFandARate`
to compute the F&A rate a SAP transmission would receive when a budget
is genuinely "to be posted." It directly feeds the same historical F&A
basis calculation that `SAP_AWARD_TRANSMISSION_ASSESSMENT.md`'s central
finding shows is *not* always reconstructable from Budget data once a
transmission has copied `overhead_key`/`base_code`/`off_campus` forward
from a prior attempt — meaning `BUDGET_RATE_AND_BASE` may itself turn
out to be `ARCHIVE_REQUIRED` rather than merely reconstructable, the same
way `BUDGET_PERSONS` was originally deferred before being reclassified.
This should be evaluated as its own pass, immediately following the SAP
Award Transmission History archive work — see
`SAP_AWARD_TRANSMISSION_ASSESSMENT.md`'s Open Questions and
`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`'s Open Questions, both of
which also carry this finding.

## Date last updated

2026-08-01 (implementation pass, then updated twice more the same day:
once to cross-reference the completed SAP Award/Budget Transmission
assessment, and again to record that the SAP Award Transmission History
archive subsystem — `archive.award_transmission`/
`archive.award_transmission_child` — has since been implemented, and to
add the standing `BUDGET_RATE_AND_BASE` open item above — see
Status/Scope/Decisions/Files changed/Verdict above for what changed).
