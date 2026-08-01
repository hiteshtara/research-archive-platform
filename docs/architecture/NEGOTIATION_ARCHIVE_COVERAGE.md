# Kuali Negotiation Archive Coverage — Master Checklist

## Purpose

The authoritative checklist for declaring the Negotiation domain complete,
built the same way as `KUALI_ARCHIVE_COVERAGE.md` (Award's equivalent
document): from Kuali's own **DataDictionary** definitions
(`coeus-impl/src/main/resources/org/kuali/kra/datadictionary/Negotiation*.xml`),
not from counting Oracle tables or from this project's own migration list.
Every `Negotiation*.xml` file is a business object *Kuali itself* considers
part of the Negotiation module's functional surface.

Unlike the Award pass, this is **not** a green-field audit: this project
already has a real, working Negotiation archive
(`V017__create_negotiation_archive_tables.sql`, `etl/load_negotiations_from_csv.py`
— the filename is legacy; it reads live from Oracle, not CSV, per
`docs/DECISIONS.md`'s CSV-retirement note). This document verifies that
existing coverage against the DataDictionary/OJB/DDL chain rather than
building it from scratch, and the headline finding is the opposite of
Award's: the DataDictionary enumeration **undercounts** what is really
archived, because one already-archived, already-verified table
(`NEGOTIATION_NOTIFICATION`) has no corresponding DataDictionary file at
all. See "Beyond the DataDictionary enumeration" below.

## Scope

Every one of the 12 `Negotiation*.xml` files directly under
`coeus-impl/src/main/resources/org/kuali/kra/datadictionary/` (the
`datadictionary/docs/*MaintenanceDocument.xml` files one level down are
lookup-maintenance routing wrappers around business objects already listed
here — e.g. `NegotiationStatusMaintenanceDocument.xml` wraps
`NegotiationStatus` — and are excluded from the count, exactly as Award's
equivalent `docs/Award*MaintenanceDocument.xml` files were). For each: the
Java business object class, the underlying Oracle table (if any), whether
it is a **persisted business entity**, a **lookup/reference**, or a
**workflow envelope**, the archive mapping, and a status of **COMPLETE**,
**PARTIALLY ARCHIVED**, **NOT YET ARCHIVED**, or **NOT APPLICABLE**.

## Source material used

Direct enumeration of every `Negotiation*.xml` file in
`/Users/mukadder/kuali-project/kuali-research/coeus-impl/src/main/resources/org/kuali/kra/datadictionary/`,
cross-referenced against each file's `businessObjectClass` (or
`documentClass`, for `NegotiationDocument.xml`) declaration, then against
the single real OJB mapping file that backs every one of these classes —
`coeus-impl/src/main/resources/org/kuali/kra/negotiation/repository-negotiation.xml`
— to confirm each one's real Oracle table. That mapping surfaced the one
genuine "don't trust the class name" gotcha in this domain:
`NegotiationActivityAttachment` (the DD/class name) maps to table
`NEGOTIATION_ATTACHMENT` (not `NEGOTIATION_ACTIVITY_ATTACHMENT` — no such
table exists), confirmed by reading the `class-descriptor` line itself, not
inferred. `NegotiationPersonMassChange`'s class-descriptor lives in the
shared `org/kuali/kra/personmasschange/repository-personmasschange.xml`
file (same file Award's `AwardPersonMassChange` uses), not in
`repository-negotiation.xml`, and was looked up there.

Cross-checked against the real Oracle bootstrap DDL and every later
`ALTER TABLE` under
`coeus-db/coeus-db-sql/src/main/resources/co/kuali/coeus/data/migration/sql/oracle/kc/bootstrap/`:
`V320_153` (`NEGOTIATION`), `V320_154` (`NEGOTIATION_ACTIVITY`), `V320_155`–
`V320_157` (the three type lookups), `V320_158` (`NEGOTIATION_ATTACHMENT`),
`V320_159` (`NEGOTIATION_CUSTOM_DATA`), `V320_160` (`NEGOTIATION_DOCUMENT`),
`V320_161`/`V320_162` (`NEGOTIATION_LOCATION`/`NEGOTIATION_STATUS`),
`V320_163` (`NEGOTIATION_UNASSOC_DETAIL`), the `V320_226`–`V320_230` FK
migrations, `V400_191` (`PMC_NEGOTIATION`), and `V510_081`
(`NEGOTIATION_NOTIFICATION`, added in a later Kuali release than the rest
of the domain — explaining why it never got a matching DataDictionary
entry; see below). Two later column-width `ALTER TABLE` migrations
(`V1807_001__SponsorCodeLength.sql`, widening `SPONSOR_CODE`/
`PRIME_SPONSOR_CODE`/`LEAD_UNIT` to `VARCHAR2(20)`) were checked and found
already accommodated by this project's wider `VARCHAR(100)`/`VARCHAR(20)`
archive columns — no gap.

Cross-checked against this project's actual shipped schema and ETL:
`database/migrations/V017__create_negotiation_archive_tables.sql`,
`database/migrations/V020__create_archived_attachment.sql` (generic
attachment table, `module_code = 'NEGOTIATION'`), `etl/load_negotiations_from_csv.py`
(despite the filename, its `ORACLE_SQL` dict points at real Oracle
extraction queries under `oracle/negotiation/*.sql` — `export_negotiations.sql`,
`export_negotiation_activities.sql`, `export_negotiation_custom_data.sql`,
`export_negotiation_notifications.sql`, `export_negotiation_unassociated.sql`
— all five confirmed present and readable, per
`etl/tests/test_negotiation_loader_framework.py`), and
`etl/archive_etl/attachments/plugins/negotiation.py` (the
`NegotiationAttachmentPlugin`, wired into `etl/archive_etl/attachments/runner.py`
and `etl/archive_etl/__main__.py`, which ingests Negotiation Activity
Attachment metadata + binaries into the shared `archive.archived_attachment`
table — the same CSV-metadata-driven, S3-binary pattern used for Award and
Subaward attachments, exempted from the "no CSV" structured-data rule per
`docs/DECISIONS.md` because attachment binaries are not structured data).

## Negotiation Feature Coverage Matrix

### Core Negotiation, activity, and detail

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `Negotiation.xml` | `Negotiation` | `NEGOTIATION` | Persisted entity | `archive.negotiation` | **COMPLETE** | All 12 real business columns captured; the parent `NEGOTIATION_DOCUMENT` row's 4 columns are also folded in as `document_source_*` (see `NegotiationDocument.xml` below); Oracle's own column is misspelled `NEGOTATION_STATUS_ID` (missing an "I") — the archive column is corrected to `negotiation_status_id` |
| `NegotiationActivity.xml` | `NegotiationActivity` | `NEGOTIATION_ACTIVITY` | Persisted entity (child of Negotiation) | `archive.negotiation_activity` | **COMPLETE** | All 16 physical columns captured; `activity_type_id`/`location_id` denormalized with code+description from their lookups |
| `NegotiationUnassociatedDetail.xml` | `NegotiationUnassociatedDetail` | `NEGOTIATION_UNASSOC_DETAIL` | Persisted entity (child of Negotiation) | `archive.negotiation_unassociated_detail` | **COMPLETE** | Captured when a Negotiation isn't linked to another module's document; all 15 physical columns present; later `SponsorCodeLength`/RESKC-3515 migrations (widened `SPONSOR_CODE`/`PRIME_SPONSOR_CODE`/`LEAD_UNIT`, dropped/re-added two FKs) don't add new columns and are already accommodated |
| `NegotiationCustomData.xml` | `NegotiationCustomData` | `NEGOTIATION_CUSTOM_DATA` | Persisted entity (child of Negotiation) | `archive.negotiation_custom_data` | **COMPLETE** | Same "generic custom-attribute value" shape Award's `AwardCustomData` copied from this table — CLAUDE.md's own reference point; `custom_attribute_id` retained without a lookup join (source lookup object not verified), matching the ETL SQL's own comment |
| `NegotiationDocument.xml` | `NegotiationDocument` (workflow doc) | `NEGOTIATION_DOCUMENT` | Workflow envelope | — (folded into `archive.negotiation.document_source_*`) | **NOT APPLICABLE** | KEW routing/document-header metadata, not Negotiation business content, exactly like `AwardDocument.xml` in the Award matrix — but unlike Award, all 4 of its non-key columns (`update_timestamp`/`update_user`/`ver_nbr`/`obj_id`) are already denormalized onto `archive.negotiation` as `document_source_update_timestamp`/etc., so there is no gap despite no dedicated archive table |

### Lookups and reference data

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `NegotiationStatus.xml` | `NegotiationStatus` | `NEGOTIATION_STATUS` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized as `archive.negotiation.negotiation_status_code`/`negotiation_status_description` |
| `NegotiationAgreementType.xml` | `NegotiationAgreementType` | `NEGOTIATION_AGREEMENT_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized as `archive.negotiation.negotiation_agreement_type_code`/`_description` |
| `NegotiationAssociationType.xml` | `NegotiationAssociationType` | `NEGOTIATION_ASSOCIATION_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized as `archive.negotiation.negotiation_association_type_code`/`_description`; this lookup is what tells a reader how to interpret `associated_document_id` (Proposal vs. Award vs. Subaward document number) |
| `NegotiationActivityType.xml` | `NegotiationActivityType` | `NEGOTIATION_ACTIVITY_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized as `archive.negotiation_activity.activity_type_code`/`_description` |
| `NegotiationLocation.xml` | `NegotiationLocation` | `NEGOTIATION_LOCATION` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized as `archive.negotiation_activity.location_code`/`_description` |

### Attachments

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `NegotiationActivityAttachment.xml` | `NegotiationActivityAttachment` | `NEGOTIATION_ATTACHMENT` | Persisted entity (child of NegotiationActivity) | `archive.archived_attachment` (`module_code = 'NEGOTIATION'`) | **COMPLETE** | The one "don't trust the class name" gotcha in this domain — the OJB `class-descriptor` maps this class to table `NEGOTIATION_ATTACHMENT`, **not** `NEGOTIATION_ACTIVITY_ATTACHMENT` (no such table exists); ingested via the generic cross-domain attachment pipeline (`NegotiationAttachmentPlugin`, same shape as Award/Subaward attachments), CSV-metadata + S3-binary, exempted from the structured-data CSV retirement per `docs/DECISIONS.md` |

### Administrative bulk-utility (not Negotiation business content)

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `NegotiationPersonMassChange.xml` | `org.kuali.kra.personmasschange.bo.NegotiationPersonMassChange` | `PMC_NEGOTIATION` | Persisted entity, different feature | — | **NOT APPLICABLE** | Same pattern as Award's `AwardPersonMassChange` → `PMC_AWARD`: an administrative bulk-personnel-change utility/audit table (tracks which Negotiations a person-mass-change batch touched), not Negotiation business data. Mapping lives in the shared `repository-personmasschange.xml`, not `repository-negotiation.xml` |

## Beyond the DataDictionary enumeration

`archive.negotiation_notification` is already shipped
(`V017__create_negotiation_archive_tables.sql`) and already has a verified
Oracle extraction query (`oracle/negotiation/export_negotiation_notifications.sql`,
selecting from `KCOEUS.NEGOTIATION_NOTIFICATION`). Its class,
`org.kuali.kra.negotiations.notifications.NegotiationNotification`, has a
real OJB `class-descriptor` in `repository-negotiation.xml` mapping to
table `NEGOTIATION_NOTIFICATION`, and that table exists in the Oracle
bootstrap DDL (`V510_081__KC_TBL_NEGOTIATION_NOTIFICATION.sql`, notably a
much later bootstrap version number than the rest of the domain's `V320_*`
files — this feature was added to Kuali well after the initial Negotiation
module shipped). All of that is genuine, verified persistence.

But **there is no `NegotiationNotification.xml`** anywhere under
`coeus-impl/src/main/resources/org/kuali/kra/datadictionary/` (confirmed by
exhaustive `find` for the filename and by `grep` for the class name across
every `*.xml` in that tree) — the class is a plain business object driven
entirely by `NegotiationNotificationRenderer`/`NegotiationNotificationAction`
(Struts action + custom renderer), never registered as a
DataDictionary-maintainable business object. This is the mirror image of
Award's `AwardComment` finding (a DD entry for a real table that turned out
to be un-archived): here it's a real, archived table with no DD entry to
even enumerate it. Counted as **COMPLETE** but **outside the 12-file scope
total** below, and called out explicitly so the DataDictionary-driven
method doesn't get mistaken for a complete inventory of "everything
Negotiation persists" — it only inventories "everything Negotiation's own
UI/maintenance framework treats as a business object."

## Totals

- **COMPLETE**: 5 of the 12 scoped DD files — `Negotiation`,
  `NegotiationActivity`, `NegotiationUnassociatedDetail`,
  `NegotiationCustomData`, `NegotiationActivityAttachment` — plus 1 real
  persisted business entity outside the DD-file scope
  (`NegotiationNotification`, see above) = **6 total archived Negotiation
  business entities**.
- **PARTIALLY ARCHIVED**: 0.
- **NOT YET ARCHIVED**: 0.
- **NOT APPLICABLE**: 7 of the 12 scoped DD files — `NegotiationStatus`,
  `NegotiationAgreementType`, `NegotiationAssociationType`,
  `NegotiationActivityType`, `NegotiationLocation` (all lookup/reference,
  denormalized), `NegotiationDocument` (workflow envelope, its 4 columns
  folded into `archive.negotiation`), and `NegotiationPersonMassChange`
  (administrative bulk-utility, different feature).

12 `Negotiation*.xml` files total: 5 COMPLETE + 7 NOT APPLICABLE = 12.
**Unlike the Award domain, every real, persisted, non-lookup,
non-workflow, non-administrative-utility Negotiation business object found
in the DataDictionary is already archived and column-complete** — there is
no NOT YET ARCHIVED or PARTIALLY ARCHIVED row in this domain.

## Open questions

- **Business grain, not historical grain.** Unlike `AWARD`, the Oracle
  `NEGOTIATION` table has no `sequence_number`/version-chain concept — one
  row per real-world Negotiation, `VER_NBR` is plain OJB optimistic-locking
  metadata, not a business version marker. `archive.negotiation` mirrors
  that 1:1: business grain and historical grain are the same thing here
  (`COUNT(*)` on `archive.negotiation` is both "number of Negotiations" and
  "number of historical Negotiation records"). Any future dashboard count
  label for Negotiation should say simply "Negotiations", not invent an
  Award-style "Historical Negotiation Records" distinction that doesn't
  exist in the source data — confirm this against `information_schema`/the
  migration before adding such a label, per CLAUDE.md's grain-inspection
  rule, rather than assuming it from this note alone.
- `NegotiationNotification`'s missing DataDictionary entry (see "Beyond the
  DataDictionary enumeration") raises the same methodological question
  Award's `AwardComment` raised in reverse: are there other Kuali business
  objects, anywhere in this codebase's domains, that are real and persisted
  but never got a DataDictionary registration at all (e.g. because they
  were added in a later release, like `NEGOTIATION_NOTIFICATION`'s `V510`
  bootstrap version vs. the rest of the domain's `V320`)? Not
  investigated beyond Negotiation itself.
- `NegotiationAssociationType`/`associated_document_id`: the association
  type lookup describes what kind of document `associated_document_id`
  points at (Proposal, Award, Subaward document number), but
  `archive.negotiation` stores `associated_document_id` as an opaque
  `TEXT` column with no enforced FK to any other archive domain table (by
  design — Kuali itself has no Oracle-level FK here either, only an
  association-type-driven interpretation at the application layer). Not
  resolved into a typed cross-domain reference; flagged only.
- `NegotiationCustomData.custom_attribute_id` and
  `NegotiationNotification.notification_type_id`: both retained as raw IDs
  with no lookup join, per the ETL SQL's own comments, because their
  source lookup objects (`CustomAttribute`, `NotificationType`) are shared
  cross-module tables not yet verified for this project. Same open
  question Award already carries for its own custom-data/notification-style
  fields; not resolved here either.

## Decisions

- Mirrors the Award domain's decision: the DataDictionary-driven matrix is
  the primary checklist for "what does the Negotiation module's own
  UI/maintenance surface consider part of Negotiation," verified against
  the real OJB mapping and Oracle DDL rather than inferred from class or
  table names.
- `NegotiationActivityAttachment` → `NEGOTIATION_ATTACHMENT` is treated as
  COMPLETE via the shared `archive.archived_attachment` table
  (`module_code = 'NEGOTIATION'`) rather than a dedicated
  `archive.negotiation_attachment` table — consistent with how Award and
  Subaward attachments are archived, and with `V020__create_archived_attachment.sql`'s
  `ck_archived_attachment_module` check constraint already listing
  `'NEGOTIATION'` as a valid module code.
- `NegotiationPersonMassChange` is NOT APPLICABLE for the same reason
  Award's `AwardPersonMassChange` is: a real, persisted, but
  administrative bulk-utility/audit table, not Negotiation business
  content — not archived, and not recommended for archiving.
- `NegotiationNotification` is counted as COMPLETE despite having no
  DataDictionary entry, because completeness is defined by "is this a
  real, persisted, non-lookup, non-utility business record, and is it
  archived and column-complete" — a DD entry is sufficient evidence of
  being in-scope (as established by the Award document) but is not
  necessary; the OJB mapping + Oracle DDL + a verified, tested Oracle
  extraction query are independently sufficient proof of both persistence
  and being business content, and this project's archive already has it.
- Lookup/reference tables and workflow envelopes are NOT APPLICABLE
  regardless of DataDictionary presence, exactly as decided in the Award
  document — a DD entry confirms module-surface membership, not
  "business record worth archiving on its own."

## Recommended implementation order

The Negotiation domain's DataDictionary-scoped business content is already
fully archived; there is no remaining implementation work driven by this
matrix. If this document is revisited:

1. Resolve the "business grain" open question above before any dashboard
   or UI work adds a Negotiation count label, to avoid inventing a
   historical/business grain distinction the source data doesn't have.
2. If a future Kuali/BU data refresh surfaces a `NegotiationNotification`-
   style object (real, persisted, no DD entry) in another domain, apply the
   same independent-verification standard used here (OJB mapping + Oracle
   DDL + tested extraction query) rather than requiring a DataDictionary
   entry as a precondition for archiving it.
3. No Tier 2/deferred-subsystem equivalent exists for Negotiation (Award's
   Budget/Time-and-Money tiers have no Negotiation counterpart in the
   DataDictionary) — nothing is deferred here.

## Date last updated

2026-07-31 (initial build, mirroring `KUALI_ARCHIVE_COVERAGE.md`'s
DataDictionary-driven method for the Negotiation domain; found the domain
already fully archived against its own DataDictionary scope, plus one
additional archived table, `NEGOTIATION_NOTIFICATION`, with no
corresponding DataDictionary entry at all).
