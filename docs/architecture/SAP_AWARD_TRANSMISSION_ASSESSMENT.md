# SAP Award and Budget Transmission Assessment

## Status

**Research complete; archive subsystem now implemented.** This
assessment's verdict (partially reconstructable — a separate archive
subsystem required) has since been acted on: `archive.award_transmission`/
`archive.award_transmission_child` were implemented in full — migration,
extraction SQL, loader wiring, and tests — as their own subsystem,
separate from the already-complete core Award domain (see
`AWARD_COMPLETENESS_REPORT.md`, whose completeness verdict this does not
change). Full implementation detail lives in
[`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`](SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md).
At no point was SAP itself called, nor any AWS/ECS/Terraform/BU dev RDS
action taken; nothing beyond what the implementation required was
committed or pushed. Everything below this point is the original research
pass and is retained as-written, including the sections describing the
work as "not implemented" at the time they were researched.

## Purpose

Determine what SAP-related historical data — if any — must be
archived as its own subsystem, separate from the core Award domain,
by researching Boston University's real SAP integration code
(`edu.bu.kuali.kra.award.sapintegration.SapIntegrationServiceImpl` and
its supporting classes), the persisted `AwardTransmission`/
`AwardTransmissionChild` business objects, and every SAP wire
structure the integration builds and receives.

## Scope

Everything the user's source-discovery list named: `SapIntegrationService`/
`SapIntegrationServiceImpl`, `SapTransmission`, `AwardTransmission`,
`AwardTransmissionChild`, `SIKCRMPROCESSOUTBOUND`,
`ZGMKCRMINTERFACE`, `sapService.*` configuration, and the
`AWARD_TRANSMISSION`/`AWARD_TRANSMISSION_CHILD` Oracle tables. Does
not cover rebuilding, calling, or testing the SAP integration itself,
and does not implement any archive table for what this assessment
finds.

## Source material used

The same full BU 7.3 Kuali source checkout used for the final Award
gap bundle (`/Users/mukadder/kuali-project/kuali-research`), read
directly, not modified:

- `coeus-impl/src/main/java/edu/bu/kuali/kra/award/sapintegration/`
  — `SapIntegrationService.java`, `SapIntegrationServiceImpl.java`
  (2,769 lines — read in full for structure/lifecycle, deeply for the
  field-mapping methods named below), `SapTransmission.java`,
  `SapTransmissionResponse.java`, `ValidationResults.java`,
  `ValidationError.java`, `CustomAwardDataHelper.java`,
  `BudgetRateAndBaseService.java`/`BudgetRateAndBaseServiceImpl.java`.
- `coeus-impl/src/main/java/edu/bu/kuali/kra/bo/AwardTransmission.java`,
  `AwardTransmissionChild.java` (the persisted business objects).
- `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`
  — the OJB class-descriptors for `AwardTransmission`
  (`AWARD_TRANSMISSION`) and `AwardTransmissionChild`
  (`AWARD_TRANSMISSION_CHILD`), and for `AwardExtension`'s
  `awardTransmissions` collection-descriptor.
- `coeus-impl/src/main/java/edu/bu/kuali/kra/award/home/AwardExtension.java`,
  `coeus-impl/src/main/java/org/kuali/kra/award/home/AwardServiceImpl.java`,
  `coeus-impl/src/main/java/org/kuali/kra/award/awardhierarchy/AwardHierarchyServiceImpl.java`,
  `coeus-impl/src/main/java/org/kuali/kra/award/web/struts/action/AwardActionsAction.java`
  (the actual transmission-triggering, versioning-copy, and
  transmission-history-migration call sites).
- `coeus-webapp/src/main/webapp/WEB-INF/tags/award/awardTransmission.tag`,
  `awardTransmissionAwardDetail.tag`, `WEB-INF/jsp/award/AwardActions.jsp`
  (the user-facing transmission-history screen).
- `coeus-impl/src/main/java/org/kuali/kra/infrastructure/Constants.java`
  (`SAP_TIMEOUT_PARM`).
- This project's own `database/migrations/`, `sql/extract/award/*.sql`
  for cross-checking which Award/Budget/Cost Share/People fields are
  already archived and therefore reconstructable as SAP-transmission
  *inputs*.

**No Oracle bootstrap DDL for `AWARD_TRANSMISSION`/
`AWARD_TRANSMISSION_CHILD` was found anywhere on this machine** —
searched every available Kuali checkout's `*.sql` files and a full
filesystem search for `AWARD_TRANSMISSION`; none exist outside the OJB
mapping file. This is the same situation already documented for
`AWARD_EXTENSION` (`AWARD_EXTENSION_CGB_DESIGN.md`: "no `ALTER TABLE
... ADD CONSTRAINT` was found ... despite confirmed real schema
evolution") — BU's own DDL for these two tables evidently lives in a
private `bu-db`-style repository not present in any checkout available
here. The OJB mapping is still authoritative for column names and
Java-level types (it is what the live application actually binds
against), but real Oracle-level PK/FK constraints, exact column
widths, and NOT NULL constraints are **not independently confirmed**
— flagged as an open question below, the same discipline already
applied to `AwardExtension`.

No BU Oracle/VPN access exists in this environment (as established
throughout this session) — this is a pure source-code research pass;
no row counts, no real transmission history was queried.

## Object graph

```
Award (archive.award_version)
 └─ AwardExtension (archive.award_extension)
     ├─ awardTransmissions: List<AwardTransmission>          [AWARD_TRANSMISSION — not yet archived]
     │    └─ transmissionChildren: List<AwardTransmissionChild>  [AWARD_TRANSMISSION_CHILD — not yet archived]
     ├─ lastTransmissionDate                                  [archive.award_extension.last_transmission_date — already archived]
     ├─ proposedForTransmissionIndicator                      [archive.award_extension.proposed_for_transmission_indicator — already archived]
     ├─ walkerSourceNumber, grantNumber                       [already archived — SAP-assigned IDs written back post-transmission]
     └─ (child_type, major_project, a133Cluster, arraCode, avcIndicator,
         fringeNotAllowedIndicator, interestEarned, steppedUpRate, ...)  [already archived]

SapTransmission (transient, never persisted)
 ├─ award: Award                     (root/parent of the hierarchy being transmitted)
 └─ childAwards: List<Award>         (the hierarchy nodes selected for this transmission)

SapIntegrationServiceImpl.transmit(SapTransmission)
 → validate(...)                     → ValidationResults / ValidationError (transient, never persisted)
 → constructSapInterface(...)        → ZGMKCRMINTERFACE (JAXB wire request, never persisted independently)
     ├─ constructGrant(Award)                → ZGMGRANTSTRUCTURE / ZBAPI0035HEADER / ZBAPI0035HEADERADD / ZFIGRANTDATA / ZGMFACREDIT / ZBAPI0035RESPONSIBLE
     ├─ constructSponsor(Award)              → ZGMSPONSORSTRUCTURE
     ├─ constructSponsorFromBillingPartner   → ZGMSPONSORSTRUCTURE (billing partner variant)
     ├─ constructBillingPlan(Award)          → ZGMBILLINGPLANSTRUCTURE
     ├─ constructSponsoredObjects(...)       → ZBAPI0035SPONSOREDOBJECTST / ZBAPI0035SPONSOREDOBJECTS
     └─ constructSponsoredProgram(Award,..)  → ZGMSPPROGRAMSTRUCTURE (x1 per hierarchy child) / ZGMSPPROGRAMGRPSTRUCTURE
 → executeSapService(...)             → SIKCRMPROCESSOUTBOUND (generated SOAP client) → real network call to BU's SAP endpoint
     ← ZGMKCRMINTERFACEResponse       → GRANTMESSAGES / SPONSORMESSAGES / SPONSOREDPROGRAMSMESSAGES / RETURN / SPXWALKT
 → SapTransmissionResponse (transient: status, message, sentData (raw sent XML), receivedData (raw response XML),
                             sponsoredProgramIds, walkerIds, warningMessages)

AwardActionsAction.transmitAward(...)  [the only code path that PERSISTS anything from a transmission]
 → new AwardTransmission()             — always created when transmissionResponse != null, success or failure
     .sentData / .returnedData         ← transmissionResponse raw XML (full outbound + full inbound payload)
     .successIndicator                 ← "Y"/"N"
     .initiatorId / .transmitterId     ← current user's principal ID (both set to the same user in this code path)
     .transmissionDate                 ← wall-clock time of this attempt
     .basisOfPaymentCode/.accountTypeCode/.sponsorCode/.methodOfPaymentCode/.documentNumber
                                        ← snapshot of the PRIMARY Award at the moment of transmission
     .transmissionChildren: List<AwardTransmissionChild>
         each with .overheadKey/.baseCode/.offCampus   ← pulled from HTTP-session-scoped transient values
                                                           set moments earlier by constructSponsoredProgram(),
                                                           which itself often copies these forward from the
                                                           PRIOR transmission's own AwardTransmissionChild row
                                                           (see Findings) rather than recomputing from Budget
 → boService.save(newAwardTransmission)   [only persistence call in the entire transmission lifecycle]
```

## Oracle table inventory

| Table | Confirmed via | PK | Real Oracle FK confirmed? |
|---|---|---|---|
| `AWARD_TRANSMISSION` | OJB class-descriptor (`repository-award.xml`) | `TRANSMISSION_ID` (BIGINT, autoincrement, `SEQUENCE_AWARD_TRANSMISSION_ID`) | Not found in any available checkout — no bootstrap DDL located anywhere on this machine (see Source material used). `AWARD_ID` is a bare column at the Java/OJB level; whether Oracle itself enforces a real FK to `AWARD.AWARD_ID` is unverified. |
| `AWARD_TRANSMISSION_CHILD` | Same | `TRANSMISSION_CHILD_ID` (BIGINT, autoincrement, `SEQUENCE_AWARD_TRANS_CHILD_ID`) | Same caveat — `TRANSMISSION_ID`/`AWARD_ID` are bare OJB-level columns only. |

No other Oracle tables belong to this subsystem specifically — everything else the integration reads (`AWARD`, `AWARD_HIERARCHY`, `AWARD_AMOUNT_INFO`, `TIME_AND_MONEY_DOCUMENT`, `AWARD_COST_SHARE`, `AWARD_PERSONS`, `AWARD_PERSON_UNITS`, `AWARD_PERS_UNIT_CRED_SPLITS`, `AWARD_SPONSOR_CONTACTS`, `AWARD_UNIT_CONTACTS`, `AWARD_SPONSOR_TERM`, `AWARD_APPROVED_SUBAWARDS`, `AWARD_REPORT_TERMS`, `AWARD_PAYMENT_SCHEDULE`, `AWARD_EXTENSION`, `AWARD_BUDGET_EXT`/`BUDGET`) already belongs to the core Award domain and is already archived (see Field-level mapping below). One additional table was discovered organically during this pass and is **not yet archived, not part of this bundle's scope**: `BUDGET_RATE_AND_BASE` (used by `BudgetRateAndBaseServiceImpl.calculateApplicableFandARate` to compute the F&A rate SAP would receive when a budget is genuinely "to be posted" — see Open Questions).

### `AWARD_TRANSMISSION` column inventory (from OJB, real DDL unconfirmed)

| Column | Java field | Notes |
|---|---|---|
| `TRANSMISSION_ID` | `transmissionId` | PK, sequence `SEQUENCE_AWARD_TRANSMISSION_ID` |
| `AWARD_ID` | `awardId` | Bare column — see Findings on reassignment across versions |
| `INITIATOR_ID` | `initiatorId` | Principal ID of the user who initiated |
| `TRANSMITTER_ID` | `transmitterId` | Principal ID of the user who actually transmitted (same value as initiator in the only active call site found) |
| `SUCCESS_INDICATOR` | `successIndicator` | `"Y"`/`"N"` |
| `TRANSMISSION_DATE` | `transmissionDate` | Wall-clock date of the attempt |
| `SENT_DATA` | `sentData` | **Full raw outbound XML** (the actual `ZGMKCRMINTERFACE` payload as sent) |
| `RETURNED_DATA` | `returnedData` | **Full raw inbound XML** (the actual `ZGMKCRMINTERFACEResponse` as received, including every per-grant/per-sponsor/per-program message) |
| `BASIS_OF_PAYMENT_CODE` | `basisOfPaymentCode` | Snapshot of the primary Award's value at transmission time |
| `ACCOUNT_TYPE_CODE` | `accountTypeCode` | Same |
| `SPONSOR_CODE` | `sponsorCode` | Same |
| `METHOD_OF_PAYMENT_CODE` | `methodOfPaymentCode` | Same |
| `DOC_NBR` | `documentNumber` | Primary Award's document number at transmission time |
| `VER_NBR`, `OBJ_ID` | `versionNumber`, `objectId` | Standard Rice optimistic-locking/object-id columns |
| `UPDATE_TIMESTAMP`, `UPDATE_USER` | — | Standard audit columns |

### `AWARD_TRANSMISSION_CHILD` column inventory (from OJB, real DDL unconfirmed)

| Column | Java field | Notes |
|---|---|---|
| `TRANSMISSION_CHILD_ID` | `transmissionChildId` | PK, sequence `SEQUENCE_AWARD_TRANS_CHILD_ID` |
| `TRANSMISSION_ID` | `transmissionId` | Bare column back to the parent `AWARD_TRANSMISSION` row |
| `AWARD_ID` | `awardId` | The specific hierarchy-child Award this row represents |
| `PARENT_DOC_NBR` | `parentDocumentNumber` | Primary Award's document number at transmission time |
| `CHILD_DOC_NBR` | `childDocumentNumber` | This child Award's document number at transmission time |
| `LEAD_UNIT_NBR` | `leadUnitNumber` | Snapshot |
| `CHILD_TYPE` | `childType` | Snapshot of `AwardExtension.childType` at transmission time |
| `AWARD_NUMBER` | `awardNumber` | This child's award number |
| `OVERHEAD_KEY` | `overheadKey` | **The F&A rate basis actually transmitted for this child** — see Findings, this is frequently copied forward from a *prior* transmission's own row, not the current archived Budget |
| `BASE_CODE` | `baseCode` | The F&A base code actually transmitted — same provenance caveat |
| `OFF_CAMPUS` | `offCampus` | The on/off-campus flag actually transmitted — same provenance caveat |
| `VER_NBR`, `OBJ_ID` | `versionNumber`, `objectId` | Standard |
| `UPDATE_TIMESTAMP`, `UPDATE_USER` | — | Standard |

## Java class inventory

| Class | Persisted? | Role |
|---|---|---|
| `SapIntegrationService` / `SapIntegrationServiceImpl` | No | Service — builds outbound XML, validates, invokes the web service, parses the response. Never itself writes to Oracle. |
| `SapTransmission` | No | Transient parameter object — one root `Award` + its selected `childAwards`. |
| `SapTransmissionResponse` | No | Transient result object — status, message, raw sent/received XML strings, generated sponsored-program/walker IDs, warnings. Its *data* is copied into `AwardTransmission` by the caller (`AwardActionsAction`), not by this class itself. |
| `ValidationResults` / `ValidationError` | No | Transient pre-transmission validation results, surfaced only as UI messages (`GlobalVariables.getMessageMap()`); never persisted, no history of past validation runs kept anywhere. |
| `CustomAwardDataHelper` | No (pure wrapper) | Thin accessor over `AwardExtension` fields already archived (`interestEarned`, `majorProject`, `a133Cluster`, `arraCode`/`isArra()`, `avcIndicator`, `childType`, `childDescription`, `fringeNotAllowedIndicator`/`isFringeNotAllowed()`, `lastTransmissionDate`). Adds no new data. |
| `BudgetRateAndBaseService` / `BudgetRateAndBaseServiceImpl` | No (reads `BUDGET_RATE_AND_BASE`, not yet archived) | Calculates the applicable F&A rate from `BudgetRateAndBase` rows when a budget is genuinely in "to be posted" status. |
| `edu.bu.kuali.kra.bo.AwardTransmission` | **Yes** — `AWARD_TRANSMISSION` | The one real historical record of a transmission attempt. |
| `edu.bu.kuali.kra.bo.AwardTransmissionChild` | **Yes** — `AWARD_TRANSMISSION_CHILD` | One row per hierarchy-child Award included in a given transmission. |
| `SIKCRMPROCESSOUTBOUND` / `SIKCRMPROCESSOUTBOUNDService` | No | Generated CXF/JAX-WS SOAP client stub for BU's `ZGMKCRMINTERFACE` web service. Pure wire-protocol plumbing. |
| `ZGMKCRMINTERFACE` / `ZGMKCRMINTERFACEResponse` and every `Z*`/`GMSPPROGRAMFMBT*`/`BAPIRET2` class | No | JAXB-generated request/response wire-format classes (`com.sap.document.sap.rfc.functions`). None are independently persisted; their only surviving trace after a transmission is the raw XML embedded in `AwardTransmission.sentData`/`.returnedData`. |

## Transmission lifecycle

1. A user on the Award "Actions" tab (`awardTransmission.tag`, embedded in `AwardActions.jsp`) marks one or more hierarchy-child Awards `proposedForTransmissionIndicator = 'Y'` and clicks "Validate and Review Awards" (`AwardActionsAction.validateForTransmission`), which calls `SapIntegrationService.validate(SapTransmission)` — a purely in-memory rules check (dates, dollar amounts, lead unit, sub-award rules, federal/NIH rules, cost-sharing-on-child rules, whether the Time and Money document has finished KEW routing, etc.). Results are shown as UI errors/warnings; **nothing about a validation attempt is ever persisted**.
2. On success, the user clicks "Transmit to SAP" (`AwardActionsAction.transmitAward`), which builds a fresh `SapTransmission` (root Award + the list of validated child Awards) and calls `SapIntegrationService.transmit(...)`.
3. `transmit()` re-validates, then calls `executeSapService(...)`, which builds the full `ZGMKCRMINTERFACE` request via `constructSapInterface(...)` (see Outbound payload composition), invokes the real `SIKCRMPROCESSOUTBOUND` SOAP client, and captures the raw request/response XML via CXF logging interceptors (`LoggingOutInterceptor`/`LoggingInInterceptor` writing into `sendWriter`/`receiveWriter`).
4. The response is parsed for per-grant/per-sponsor/per-sponsored-program error and warning messages (`processResponseMessages`/`processWarningMessages`), and for SAP-generated identifiers (`extractSponsoredProgramIds`, `extractWalkerIds`).
5. Back in `AwardActionsAction.transmitAward`, **one `AwardTransmission` row is always created** when the response object itself is non-null (both success and transmission-failure cases — a `SocketTimeoutException` during the SOAP call is itself caught and turned into a non-null `TRANSMISSION_FAILURE` response, so even network failures produce a persisted row; only a validation failure earlier, or a genuinely null response, skips this and shows an error with no row created).
6. On success: `lastTransmissionDate` is set on the primary and every (non-"Group") child Award's `AwardExtension`; any SAP-generated sponsored-program ID is written into `award.accountNumber`; any SAP-generated Walker ID is written into `AwardExtension.walkerSourceNumber`; `grantNumber` is (re)derived; all touched Awards are saved via `boService.save(award)`.
7. Regardless of success/failure, a **brand-new** `AwardTransmission` object is constructed (never reused), populated with `sentData`/`returnedData`/`successIndicator`/timestamps/user/the primary Award's basis-of-payment/account-type/sponsor/method-of-payment/document-number snapshot, and one `AwardTransmissionChild` per selected child Award (pulling `overheadKey`/`baseCode`/`offCampus` out of HTTP-session-scoped values written moments earlier by `constructSponsoredProgram`, then immediately removed from the session). This is saved once via `boService.save(newAwardTransmission)` (OJB cascades the child collection).
8. **Retransmission always creates a new `AwardTransmission` row** — there is no code path that updates or overwrites a previous row. The full history accumulates as a list on `AwardExtension.awardTransmissions`.
9. **Award versioning and transmission history**: when a brand-new Award version is created (`AwardServiceImpl.generateAndPopulateAwardDocument`), the new in-memory version's `awardTransmissions` collection is explicitly cleared before it is ever saved — a new version starts, in memory, with no transmission history. Separately, `AwardServiceImpl.updateTransmissionHistory(AwardDocument)` — an actively-used method, distinct from the dead `AwardActionsAction.createAwardTransmission` helper described next — finds the OLD active award's persisted `AwardTransmission` rows and **reassigns their `AWARD_ID` in place** to the new award_id, then saves. This is a real update, not a copy: after this runs, those transmission rows no longer show the award_id they were originally created against. A second, textually similar method, `AwardActionsAction.createAwardTransmission(AwardExtension, AwardExtension)`, does deep-copy transmission rows onto a new award_id instead of reassigning — but it has **no callers anywhere in the checkout** (confirmed by search); it is dead code, not part of the live behavior.
10. Award hierarchy child-node copy operations (`AwardHierarchyServiceImpl`, `AwardActionsAction.nullifyTransmissionDateAndHistory`) explicitly null out `lastTransmissionDate` and clear `awardTransmissions` on the copy — a copied/new hierarchy node starts fresh, with no inherited transmission history.

## Outbound payload composition

`constructSapInterface(SapTransmission, List<Long>)` assembles one `ZGMKCRMINTERFACE` request per transmission, covering:

- **One `ZGMGRANTSTRUCTURE`** (`constructGrant`) — built from the **primary/root Award only** (Specification section 1.7.1/1.7.2/1.7.3). Carries `ZBAPI0035HEADER`/`ZBAPI0035HEADERADD` (grant number, type, sponsor, authorization group, award type, obligated total, valid-from/to dates, external/internal reference, CFDA number, billing rule, letter-of-credit code, FAIN, invoice frequency/form, advance-payment indicator, fund center, title, interest-earned code, ARRA flag, major-project flag, property-owner title, cost-share memo match, AVC tolerance, NSF category, A-133 cluster, project begin/end dates, prime sponsor code, user status, billing partner rolodex ID), `ZFIGRANTDATA` (the `ZZ*`-prefixed custom fields, same section), `ZGMFACREDITT`/`ZGMFACREDIT` (one row per Award-person/unit F&A credit split), and `ZBAPI0035RESPONSIBLET`/`ZBAPI0035RESPONSIBLE` (one row per project person, with PI/Co-PI responsibility codes).
- **One `SPONSOR`/`ZGMSPONSORSTRUCTURE`** per transmission for the primary Award's own sponsor (`constructSponsor`), plus a second one if a billing partner exists (`constructSponsorFromBillingPartner`) — sponsor code/type/name, contact name/address/city/state/postal/country/phone/fax (all sourced from the shared KC `Sponsor`/`Rolodex` master records, **not** Award-domain data), and DODAC fund code (from the sponsor or, if present, the prime sponsor).
- **One `ZGMBILLINGPLANSTRUCTURE`** (`constructBillingPlan`) if the primary Award has a payment schedule — billing date and amount from the most recent `AwardPaymentSchedule` row.
- **One `ZBAPI0035SPONSOREDOBJECTST`** (`constructSponsoredObjects`) — a deduplicated set of (sponsored program, sponsored class) pairs built from every Award's `AwardSponsorTerm` rows (via a code-conversion lookup), inherited down from parent to child.
- **One `ZGMSPPROGRAMSTRUCTURE` per selected child Award** (`constructSponsoredProgram`, spec sections 1.7.4/1.7.5/1.7.6) — program number/account number, valid-from/to dates (from `AwardAmountInfo`), business area (from lead unit), functional area (from activity type code), F&A rate basis (`OVERHEADKEY`/`BASECODE`/`OFFCAMPUS` — see Findings on provenance), budget total-direct-cost/total-indirect-cost (from the current `AwardBudgetExt`, zeroed if the budget is not "to be posted" or the parent transaction type is a no-cost-extension/administrative-change), fringe code, child-type-derived order category, description, and — for cost-sharing programs — a parallel structure sourced from `AwardCostShare`.
- **One `ZGMSPPROGRAMGRPSTRUCTURE`** (`constructSponsoredProgramGroups`) for "group"-type hierarchy children, recursively walking the live `AwardHierarchy`/`AwardHierarchyNode` tree.

Every one of the Award-domain source objects listed above (`Award`, `AwardAmountInfo`, `AwardReportTerm`, `AwardPaymentSchedule`, `AwardSponsorTerm`, `AwardCostShare`, `AwardPerson`, `AwardPersonUnit`, `AwardPersonUnitCreditSplit`, `AwardExtension`, `AwardHierarchy`) is **already archived** in this project. The **shared KC `Sponsor`/`Rolodex`/`Organization`/`Unit` master data** referenced for sponsor contact detail and business-area lookups is **not** Award-domain data and is out of this project's scope entirely (only `sponsor_code`/`sponsor_name` are already denormalized onto `archive.award_version`; full sponsor address/contact detail is not archived anywhere in this project).

## Inbound response persistence

The full raw response XML (`ZGMKCRMINTERFACEResponse`, everything CXF's `LoggingInInterceptor` captured) is stored verbatim in `AwardTransmission.returnedData` — this is the **only** place any part of the SAP response is persisted. The structured per-object messages the service code itself parses out of the response (`GRANTMESSAGES`/`SPONSORMESSAGES`/`SPONSOREDPROGRAMSMESSAGES`, each with a `TYPE` of error or informational and a `MESSAGE` string) are used only to build a transient, human-readable summary string shown once as a UI error message — **that summary itself is never persisted**; it is reconstructable only by re-parsing the raw `returnedData` XML, which means `returnedData` is genuinely load-bearing, not a redundant convenience copy. SAP-generated sponsored-program IDs and Walker IDs are extracted from the response and written back onto live Award fields (`account_number`, `AwardExtension.walker_source_number`) — these two specific outcomes **are** already reconstructable from core archived Award data, though only as of *today's* value, not as a historical "what did SAP return for transmission N specifically" record (a later transmission or manual edit could change `account_number` again).

## Retry and failure behavior

There is **no automatic retry** anywhere in this code. A single synchronous SOAP call is made per `transmitAward` action. A `SocketTimeoutException` is caught and converted into a `TRANSMISSION_FAILURE` response (still carrying whatever partial `sentData`/`receivedData` the interceptors captured); any other SOAP fault is rethrown uncaught, in which case the action fails outright and (per the code read) **no `AwardTransmission` row is created for that attempt at all** — a hard SOAP fault that isn't a timeout produces no archival trace whatsoever, only application logs outside this system's scope. A user-initiated retry is simply clicking "Transmit to SAP" again, which runs the whole lifecycle again and creates a wholly new `AwardTransmission` row — failed and successful attempts sit side by side in `AwardExtension.awardTransmissions`, distinguished only by `successIndicator`.

## Findings

- **Full outbound and inbound payloads are stored as raw XML strings**, not reconstructed at read time and not partially stored — `AwardTransmission.sentData`/`.returnedData` are exactly what CXF's logging interceptors captured for that specific call.
- **Both failed and successful attempts are preserved** as long as the SOAP call itself returns *any* response object (including a caught timeout) — only a validation failure (before any network call) or an uncaught non-timeout SOAP fault produce no row.
- **Retransmission always creates new history; nothing is ever overwritten.**
- **One transmission covers one Award hierarchy** — a root/primary Award plus the specific set of hierarchy-child Awards the user selected and validated for that transmission (not necessarily every child in the full hierarchy, and not scoped to a single Award version in isolation — the primary Award parameter is always the currently-loaded version, but see the next bullet on how that identity can drift after the fact).
- **`AWARD_ID` on existing `AwardTransmission` rows can be reassigned to a new Award version after the fact** (`AwardServiceImpl.updateTransmissionHistory`) rather than always being fixed at the version that was actually live at transmission time. This is an update-in-place, not an insert of new rows — real BU Oracle data would need to be inspected to know how often this occurs and whether it means some historical transmission rows currently point at a *later* award_id than the one genuinely transmitted. Flagged as an open question.
- **Transmission children represent hierarchy nodes** (specific child Awards included in that transmission), each carrying a *snapshot* of that child's document number, lead unit, child type, and — critically — the F&A rate basis (`overheadKey`/`baseCode`/`offCampus`) actually used for that transmission.
- **The F&A rate basis fields are frequently not derived from current Budget data at all.** When the child's budget is not in "to be posted" status (the common case for an already-transmitted hierarchy node), `constructSponsoredProgram` pulls `overheadKey`/`baseCode`/`offCampus` from the **most recent prior transmission's own `AwardTransmissionChild` row** (`getLatestChildAwardTransmitted`), not from `archive.award_budget`. This creates a real lineage: transmission *N*'s rate-basis values can depend on transmission *N-1*'s, which depend on *N-2*'s, and so on, back to whichever transmission first computed them from a genuinely-"to-be-posted" budget. **If `AwardTransmissionChild` is not archived, this lineage is permanently unrecoverable** — it cannot be recomputed from Budget data once the budget has moved past "to be posted," which is exactly the situation for most historical Award versions. This is the single strongest concrete argument for archiving `AwardTransmissionChild`, not just `AwardTransmission`.
- **Which values are transformed and therefore not reconstructable byte-for-byte from core Award data**: every BU-specific code-conversion method (`convertAccountTypeToGrantType`, `convertAwardTypeCodeToAwardType`, `convertBasisOfPaymentToBillingRule`, `convertMethodOfPaymentToLetterOfCredit`, `convertStatusCodeToResponsibility`, `convertInterestEarnedCode`, `convertAvcIndicatorToAvcTolerance`, `convertSponsorTermToSponsorClass`, `convertLeadUnitToBusinessArea`, `convertActivityTypeToFunctionalArea`, `convertChildType`, `FringeCodeMapping.mapToSapFringeCode`, `deriveGrantNumber`) takes an already-archived Award/Extension code value and maps it to a SAP-specific output value using logic (and, in places, KC `ParameterService` sub-parameters) that lives only in this Java class and Rice parameter tables — **not** in the Award archive. The archived *input* codes are reconstructable; the *transformed output actually transmitted* is only reconstructable exactly by re-running this exact version of this exact code against the exact parameter values in effect at that time — which is precisely what a raw `sentData` snapshot avoids needing to do.
- **Some SAP-assigned identifiers already round-trip into already-archived core Award data**: a successful transmission's generated sponsored-program ID is written into `Award.accountNumber` (`archive.award_version.account_number`), and its Walker ID into `AwardExtension.walkerSourceNumber` (`archive.award_extension.walker_source_number`) — both already archived. This means the *current* value of these identifiers is already reconstructable; what is not reconstructable is *which specific transmission* produced them, or what the value was between two transmissions if it changed.
- **`AwardBudgetVersionOverviewExt` is imported but never used** anywhere in `SapIntegrationServiceImpl` — consistent with this project's own earlier finding (`AWARD_BUDGET_DESIGN.md`) that it is a lighter version-listing projection over the same `AWARD_BUDGET_EXT` table, not a distinct data source.
- **A related, not-yet-archived Budget table was discovered organically**: `BUDGET_RATE_AND_BASE` (Java `BudgetRateAndBase`, shared with Proposal Development, same "no Award-specific `_EXT`" shape as `BUDGET_PERSONS`), read by `BudgetRateAndBaseServiceImpl.calculateApplicableFandARate` to compute the F&A rate SAP would receive when a budget genuinely is "to be posted." This is not part of this assessment's scope to classify or implement, but is flagged for a future pass — see Open Questions.
- **No DataDictionary entry, maintenance document, or lookup/inquiry screen exists for `AwardTransmission`/`AwardTransmissionChild`** — they are visible to end users only via the inline "Award Transmission History" table on the Award "Actions" tab (`awardTransmission.tag`), which shows Initiator, Transmitter, all Document IDs transmitted, Success, Transmission Date, and an expandable row revealing the **raw Sent XML and Received XML side by side**. This confirms the raw payloads are genuine, intentionally user-facing historical business records at BU today, not internal debug artifacts.
- **No application-level retry, no persisted validation history, no persisted per-object success/failure breakdown** beyond what is embedded in the raw XML.

## Field-level source-to-SAP mapping

| SAP structure | Field(s) | Source | Already archived? |
|---|---|---|---|
| `ZBAPI0035HEADER` | `GRANTNBR` | `deriveGrantNumber(award.getAwardNumber())` (derived) | Award number: yes. Derivation logic: no (code only). |
| `ZBAPI0035HEADER` | `PARENTTRANSACTIONTYPE` | `Award.awardTransactionType.description` | Yes — `archive.award_version.transaction_type` |
| `ZBAPI0035HEADER` | `GRANTTYPE` | `convertAccountTypeToGrantType(award.accountTypeCode)` | Input yes; transform logic no |
| `ZBAPI0035HEADER` | `SPONSOR` | `Award.sponsorCode` | Yes — `archive.award_version.sponsor_code` |
| `ZBAPI0035HEADERADD` | `AUTHGROUP` | `Award.leadUnitNumber` (padded) | Yes — `archive.award_version.lead_unit_number` |
| `ZBAPI0035HEADERADD` | `AWARDTYPE` | `convertAwardTypeCodeToAwardType(award.awardTypeCode)` | Input partial (`award_type_code` not currently archived on `archive.award_version` — see Open Questions); transform logic no |
| `ZBAPI0035HEADERADD` | `GRANTTOTAL` | `AwardAmountInfo.amountObligatedToDate` | Yes — `archive.award_amount_info` |
| `ZBAPI0035HEADER` | `VALIDFROM`/`VALIDFROMBUDGET` | `AwardAmountInfo.currentFundEffectiveDate` (± 3 months) | Yes (input); the ±3-month adjustment is code logic |
| `ZBAPI0035HEADER` | `VALIDTO`/`VALIDTOBUDGET` | `AwardAmountInfo.obligationExpirationDate` (+ 1 year) | Yes (input); +1-year adjustment is code logic |
| `ZBAPI0035HEADER` | `EXTREFERENCE`/`INTREFERENCE` | `Award.sponsorAwardNumber`/`Award.awardNumber` | Yes |
| `ZBAPI0035HEADERADD` | `CFDANBR` | `Award.cfdaNumber` | Yes — `archive.award_cfda`/Award-level CFDA |
| `ZBAPI0035HEADERADD` | `BILLINGRULE` | `convertBasisOfPaymentToBillingRule(award.basisOfPaymentCode)` | Input yes (`archive.award_version.basis_of_payment_code`); transform logic no |
| `ZBAPI0035HEADERADD` | `LETTEROFCREDIT` | `convertMethodOfPaymentToLetterOfCredit(award.methodOfPaymentCode)` | Input yes (`archive.award_version.method_of_payment_code`); transform logic no |
| `ZBAPI0035HEADERADD` | `FUNDINGORIGIN` | `Award.fainId` | Partial — verify archived column name |
| `ZFIGRANTDATA` | `ZZINVOICEFREQ`/`ZZINVOICEFORM` | `AwardReportTerm.frequencyCode`/`.reportCode` (converted) | Input yes — `archive.award_report_term`; transform logic no |
| `ZFIGRANTDATA` | `ZZADVPYMNTIND` | `determineAdvancePayment(award)` (derived) | Derived from already-archived fields; logic no |
| `ZFIGRANTDATA` | `ZZFUNDCENTER` | `Award.leadUnitNumber` (padded) | Yes |
| `ZFIGRANTDATA` | `ZZAWARDTITLE` | `Award.title` | Yes |
| `ZFIGRANTDATA` | `ZZINTEARNED` | `AwardExtension.interestEarned` (converted) | Input yes — `archive.award_extension.interest_earned`; transform logic no |
| `ZFIGRANTDATA` | `ZZLDCODE` | `AwardExtension.arraCode` (`isArra()`) | Yes — `archive.award_extension.arra_code` |
| `ZFIGRANTDATA` | `ZZMJRPRJCT` | `AwardExtension.majorProject` | Yes — `archive.award_extension.major_project` |
| `ZFIGRANTDATA` | `ZZPRPRTYOWNR` | `determinePropertyOwnerTitle(award)` (derived from `AwardSponsorTerm`) | Input yes; derivation logic no |
| `ZFIGRANTDATA` | `ZZCOSTSHARE` | `determineCostShareMemoMatch(award)` (derived from `AwardSponsorTerm`) | Input yes; derivation logic no |
| `ZFIGRANTDATA` | `ZZAVCTOLERANCE` | `AwardExtension.avcIndicator` (converted) | Input yes — `archive.award_extension.avc_indicator`; transform logic no |
| `ZFIGRANTDATA` | `ZZNSFCTGRY` | `Award.nsfCodeBo.nsfCode` | Verify archived (Award-level NSF code — not confirmed archived by name in this pass) |
| `ZFIGRANTDATA` | `ZZA133CLSTR` | `AwardExtension.a133Cluster` | Yes — `archive.award_extension.a133_cluster` |
| `ZFIGRANTDATA` | `ZZPROJBEGDA` | `Award.awardEffectiveDate` | Yes — `archive.award_version.award_effective_date` |
| `ZFIGRANTDATA` | `ZZPROJENDDA` | `AwardAmountInfo.finalExpirationDate` | Yes |
| `ZFIGRANTDATA` | `ZZAWARDNO` | `Award.awardNumber` | Yes |
| `ZFIGRANTDATA` | `ZZSPONSOR` | `Award.primeSponsorCode` | Yes — `archive.award_version.prime_sponsor_code` |
| `ZBAPI0035HEADER` | `USERSTATUS` | `convertStatusCodeToResponsibility(award.statusCode)` | Input yes (`archive.award_version.status_code`); transform logic no |
| `ZFIGRANTDATA` | `ZZBILLPARTNER` | Most recent `AwardReportTerm`'s first `AwardReportTermRecipient.rolodexId` | Yes — `archive.award_report_term_recipient` |
| `ZGMFACREDIT` | `GRANTNBR`/`DEPT`/`PERCENTAGE` | `AwardPerson` → `AwardPersonUnit` → `AwardPersonUnitCreditSplit` | Yes — all three archived |
| `ZBAPI0035RESPONSIBLE` | `USERID`/`USERNAME`/`RESPONSIBILITY` | `AwardPerson.personId`/`.fullName`/PI-multiplicity logic | Yes — `archive.award_person` |
| `ZGMSPONSORSTRUCTURE` | `SPONSOR`/`SPONSORNAME` | `Sponsor.sponsorCode`/`.sponsorName` | Yes (denormalized on `archive.award_version`) |
| `ZGMSPONSORSTRUCTURE` | `SPONSORTYPE`, contact/address/phone/fax, `FUND` (DODAC) | Shared KC `Sponsor`/`Rolodex` master data | **No** — out of Award-archive scope entirely, not just unarchived |
| `ZGMBILLINGPLANSTRUCTURE` | `BILLINGDATE`/`BILLINGVALUE` | `AwardPaymentSchedule.dueDate`/`.amount` | Yes — `archive.award_payment_schedule` |
| `ZBAPI0035SPONSOREDOBJECTS` | `SPONSOREDPROG`/`SPONSOREDCLASS` | `AwardSponsorTerm` (converted) | Input yes — `archive.award_sponsor_term`; transform logic no |
| `ZGMSPPROGRAMSTRUCTURE` | `SPPROGRAMNUMBER`/`SPONSOREDPROG` | `Award.accountNumber`/`.awardNumber` | Yes |
| `ZGMSPPROGRAMSTRUCTURE` | `ZZVALIDFROM`/`ZZVALIDTO` | `AwardAmountInfo.currentFundEffectiveDate`/`.obligationExpirationDate` | Yes |
| `ZGMSPPROGRAMSTRUCTURE` | `BUSINESSAREA` | Lead unit (converted); shared `Unit` for name only | Input mostly yes |
| `ZGMSPPROGRAMSTRUCTURE`/`GMSPPROGRAMFMBT` | `FUNCTIONALAREA` | `Award.activityTypeCode` (converted) | Input yes; transform logic no |
| `ZGMSPPROGRAMSTRUCTURE` | `OVERHEADKEY`/`BASECODE`/`ZZOFFCAMPUS` | Current `AwardBudgetExt` **or** the prior transmission's own `AwardTransmissionChild` row (see Findings) | **Only sometimes** — depends on budget status at the time; the actually-transmitted historical value is **not reconstructable** once the budget has moved past "to be posted" |
| `ZGMSPPROGRAMSTRUCTURE` | `BUDGETTDC`/`BUDGETFA` | `AwardBudgetExt.totalDirectCost`/`.totalIndirectCost` (zeroed under specific transaction-type/status conditions) | Yes — `archive.award_budget`; zeroing conditions are code logic |
| `ZGMSPPROGRAMSTRUCTURE` | `FRINGECODE` | `AwardExtension.fringeNotAllowedIndicator` + `Award.accountTypeCode` (converted) | Input yes; transform logic no |
| `ZGMSPPROGRAMSTRUCTURE` | `ZZORDCAT` | `AwardExtension.childType` (converted) | Input yes — `archive.award_extension.child_type`; transform logic no |
| `ZGMSPPROGRAMSTRUCTURE` | `DESCRIPTION` | `AwardExtension.childDescription` | Yes — `archive.award_extension.child_description` |
| `ZGMSPPROGRAMSTRUCTURE` (cost-sharing variant) | Same shape, sourced from `AwardCostShare` | `AwardCostShare` | Yes — `archive.award_cost_share` |
| `ZGMKCRMINTERFACEResponse.GRANTMESSAGES`/`SPONSORMESSAGES`/`SPONSOREDPROGRAMSMESSAGES`/`RETURN`/`SPXWALKT` | error/warning text, generated IDs | SAP itself, at response time | **No** — only recoverable from `AwardTransmission.returnedData`'s raw XML |

## Classification

### ARCHIVE_REQUIRED

- **`AwardTransmission`** (`AWARD_TRANSMISSION`) — the full outbound/inbound payload, status, timestamp, transmitting/initiating user, and a point-in-time snapshot of several primary-Award fields. None of this is reconstructable from core Award data: the archive can show what an Award's fields are *today*, never what was actually sent to SAP on a specific past date, whether it succeeded, or what SAP said back.
- **`AwardTransmissionChild`** (`AWARD_TRANSMISSION_CHILD`) — same reasoning, plus the F&A-rate-basis lineage problem described in Findings, which makes this table's `overhead_key`/`base_code`/`off_campus` columns specifically unrecoverable once the source budget has moved past "to be posted."

### RECONSTRUCTABLE_FROM_CORE_AWARD_DATA

Every already-archived Award/Budget/People/Terms/Contacts/Extension table used as a transmission *input*: `Award` (`archive.award_version`), `AwardAmountInfo`, `AwardHierarchy`, `TimeAndMoneyDocument`, `AwardCostShare`, `AwardPerson`, `AwardPersonUnit`, `AwardPersonUnitCreditSplit`, `AwardSponsorContact`, `AwardUnitContact`, `AwardSponsorTerm`, `AwardReportTerm`, `AwardReportTermRecipient`, `AwardApprovedSubaward`, `AwardPaymentSchedule`, `AwardExtension` (including `last_transmission_date`, `proposed_for_transmission_indicator`, `walker_source_number`, `grant_number`, and every `CustomAwardDataHelper`-exposed field), `AwardBudgetExt`/`archive.award_budget`. **Reconstructable as inputs only** — the exact transmitted/transformed *output* value is not guaranteed reconstructable without re-running the identical BU transformation code and parameter values in effect at the historical time (see Findings).

### OPERATIONAL_ONLY

`SapIntegrationService`/`SapIntegrationServiceImpl`, `SapTransmission`, `SapTransmissionResponse`, `ValidationResults`, `ValidationError`, `CustomAwardDataHelper`, `BudgetRateAndBaseService`/`BudgetRateAndBaseServiceImpl`, `SIKCRMPROCESSOUTBOUND`/`SIKCRMPROCESSOUTBOUNDService`, every JAXB wire-format class (`ZGMKCRMINTERFACE`, `ZGMKCRMINTERFACEResponse` and all nested types, `ZFIGRANTDATA`, `ZFIKCRMSPXWALK`, `ZGMGRANTSTRUCTURE`, `ZGMSPONSORSTRUCTURE`, `ZGMSPPROGRAMSTRUCTURE`, `ZGMSPPROGRAMGRPSTRUCTURE`, `ZGMBILLINGPLANSTRUCTURE`, `ZGMFACREDIT`/`ZGMFACREDITT`, `ZGMSPRESPONSIBLEKCRM`, `ZBAPI0035HEADER`/`ZBAPI0035HEADERADD`/`ZBAPI0035RESPONSIBLE`/`ZBAPI0035SPONSOREDOBJECTS` and their `*T` collection wrappers, `BAPIRET2`, `GMSPPROGRAMFMBT`/`GMSPPROGRAMFMBTTT`). None of these persist independently; all are pure service/wire-format code whose only historical trace lives inside `AwardTransmission.sentData`/`.returnedData`.

### CONFIGURATION_ONLY

The `sapService.wsdl.url`/`sapService.url`/`sapService.username`/`sapService.password`/`sapService.connectionTimeout`/`sapService.receiveTimeout` Rice `ConfigContext` properties, and the `SAP_TIMEOUT_PARM` KC system parameter. No DataDictionary entry exists for any of these; they are ordinary environment/deployment configuration, not business data.

### LOOKUP_ONLY

`AWARD_BUDGET_STATUS`/`AWARD_BUDGET_TYPE`-style code lookups referenced indirectly through the `convert*` methods; the shared KC `Sponsor`/`Rolodex`/`Organization`/`Unit` master tables referenced for sponsor contact detail and business-area name lookups (already partially out-of-scope precedent in this project: only `sponsor_code`/`sponsor_name` denormalized, never the full Sponsor/Rolodex record).

## Recommended archive implementation order (now implemented — see SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md)

This section is retained as originally researched. The order below was
followed as written in the actual implementation; see
[`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`](SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md)
for what was actually built, including the one place implementation
diverged from this recommendation (both tables are read independently,
each filtered on their own `AWARD_ID`, rather than a two-step parent-then-
child extraction — see that document's "Extraction/read strategy"
section for why).

1. Confirm real Oracle DDL for `AWARD_TRANSMISSION`/`AWARD_TRANSMISSION_CHILD` against real BU Oracle (currently unconfirmed — see Open Questions) before writing a migration, exactly as this project's own discipline requires for every new table.
2. `archive.award_transmission` — one row per transmission attempt, keyed by Oracle's real surrogate `TRANSMISSION_ID`, with a bare (not necessarily enforceable, given the `AWARD_ID`-reassignment finding) reference to `archive.award_version.award_id`, capturing `sent_data`/`returned_data`/`success_indicator`/`transmission_date`/`initiator_id`/`transmitter_id`/the basis-of-payment/account-type/sponsor/method-of-payment/document-number snapshot fields.
3. `archive.award_transmission_child` — one row per hierarchy-child included in a transmission, FK to `archive.award_transmission.transmission_id`, capturing `parent_document_number`/`child_document_number`/`lead_unit_number`/`child_type`/`award_number`/`overhead_key`/`base_code`/`off_campus`.
4. Extraction SQL for both, scoped by `AWARD_ID` the same way every other Award child table already is — but see the `AWARD_ID`-reassignment open question below before assuming a simple `WHERE AWARD_ID IN (...)` filter captures a transmission's *original* award correctly for every historical row.
5. Standard `prepare_*`/`upsert_*` wiring into `--load-award-id`/`--load-batch`, following the exact FK-safe two-level pattern already used for `award_hierarchy`→ nothing, or `award_budget`→`award_budget_limit` (parent inserted, then children, one Postgres transaction per batch).
6. Full test suite matching this project's established pattern (SQL contract, insert/update/unchanged, unrelated-Award isolation, dry-run rollback, idempotent rerun, one-read-per-table batch behavior, full-batch rollback) plus a dedicated test proving the composite parent/child relationship survives the FK-reassignment quirk correctly (i.e., that re-extracting after a real `updateTransmissionHistory` migration doesn't produce duplicate or orphaned rows).

This recommendation has since been carried out — see
`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md` for the full implementation
record.

## Open questions requiring real BU Oracle data

- **No bootstrap DDL for `AWARD_TRANSMISSION`/`AWARD_TRANSMISSION_CHILD` was found anywhere on this machine.** Real column types/widths, NOT NULL constraints, and whether `AWARD_ID`/`TRANSMISSION_ID` are genuinely Oracle-enforced FKs (or bare columns, as `AwardExtension`'s own FK situation turned out to be) are unconfirmed. Whoever next has BU Oracle/VPN access should run the equivalent of `DESC AWARD_TRANSMISSION`/`DESC AWARD_TRANSMISSION_CHILD` and check `information_schema`/`ALL_CONSTRAINTS` before trusting the OJB-mapping-only column list the way this report does.
- **How often, and by how much history, does `AwardServiceImpl.updateTransmissionHistory`'s `AWARD_ID` reassignment actually occur in real BU Oracle data?** If it is common, most historical `AWARD_TRANSMISSION` rows may already show a *later* award_id than the one genuinely live at transmission time — real data is needed to know whether this materially affects which Award version a given transmission row should be associated with once archived.
- **Real row counts.** How many `AWARD_TRANSMISSION`/`AWARD_TRANSMISSION_CHILD` rows exist in real BU Oracle, and over what date range — needed to size any future implementation and its extraction/batch performance the same way this project already benchmarked Budget/Time and Money at scale.
- **Whether `AWARD_TYPE_CODE` is genuinely reconstructable.** `ZBAPI0035HEADERADD.AWARDTYPE` depends on `Award.getAwardTypeCode()`; this project's own coverage docs should be checked (not done in this pass) to confirm whether `award_type_code` is already archived on `archive.award_version` or is a genuine additional gap.
- **`Award.nsfCodeBo`/`Award.fainId`** — whether these are already archived under different column names, or represent additional small gaps, was not confirmed in this pass and should be checked before any future implementation.
- **`BUDGET_RATE_AND_BASE`** (see Findings) — a real, not-yet-archived Budget table that directly feeds SAP's F&A rate field. Not classified or scoped by this assessment; flagged for a separate future evaluation, the same way `BUDGET_PERSONS` was originally flagged during the Budget bundle before its own later reassessment.
- **Whether BU's real Oracle retains transmission rows indefinitely or purges old ones** — this project has already flagged an analogous open question for `PENDING_TRANSACTIONS` (`AWARD_TIME_AND_MONEY_DESIGN.md`); the same question applies here and is equally unverifiable without real Oracle access.

## Files changed

Research pass (this document's original scope):

- `docs/architecture/SAP_AWARD_TRANSMISSION_ASSESSMENT.md` (this file, new).
- `docs/architecture/KUALI_ARCHIVE_COVERAGE.md` — new section recording SAP Award/Budget Transmission as a separate, not-yet-decided integration-history subsystem, outside the core Award domain's completeness count.
- `docs/architecture/AWARD_COMPLETENESS_REPORT.md` — verdict section cross-referenced to this assessment, unchanged otherwise (the core Award domain's own completeness declaration stands).

Implementation pass (see `SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md` for the
full record): `database/migrations/V052__create_award_sap_transmission_history.sql`,
`sql/extract/award/47_award_transmission.sql`,
`sql/extract/award/48_award_transmission_child.sql`,
`etl/load_awards_from_csv.py`, `etl/tests/test_award_incremental_upsert.py`,
plus updates to `KUALI_ARCHIVE_COVERAGE.md`, `AWARD_COMPLETENESS_REPORT.md`,
`AWARD_IMPLEMENTATION_ROADMAP.md`, and `SAP_TRANSMISSION_SESSION_SUMMARY.md`.

No SAP call, AWS/ECS/Terraform action, or BU dev RDS access was performed
at any point across either pass. No commit or push was made as part of
either pass.

## Verdict: is SAP transmission history reconstructable?

**Partially reconstructable, and that is not enough — a real, separate archive subsystem would be required to preserve what core Award data cannot.**

The overwhelming majority of what feeds a SAP transmission — every Award, Budget, Cost Share, People, Terms, Contacts, and Extension field this integration reads — is already archived in the core Award domain and is fully reconstructable as *input* data. That is the good news this research confirms concretely, field by field.

What is **not** reconstructable, and would require its own archive tables (`archive.award_transmission`, `archive.award_transmission_child`) if BU ever wants this history preserved:

- the exact outbound payload actually sent for a specific historical transmission (subject to BU-specific transformation logic and parameter values that can drift over time, not just archived input codes),
- the exact inbound SAP response, including per-object success/failure messages,
- whether a given attempt succeeded or failed, and when, by whom,
- the F&A rate basis (`overhead_key`/`base_code`/`off_campus`) actually used for a given hierarchy child — which, per this pass's central finding, is frequently *not* derivable from Budget at all once that budget has moved past "to be posted," since the live system itself copies it forward from the prior transmission rather than recomputing it.

This subsystem is correctly treated as a separate, standalone integration-history archive question — not a blocker to the core Award domain's already-declared completeness (`AWARD_COMPLETENESS_REPORT.md`), and not something to fold into the existing Award loader without its own dedicated design pass (real DDL confirmation, the `AWARD_ID`-reassignment question, and real row-count/date-range sizing all need real BU Oracle access first).

**Update, implementation pass**: `archive.award_transmission`/
`archive.award_transmission_child` have since been implemented in full —
schema, extraction, loader wiring, and tests — as their own subsystem,
still separate from the core Award domain. Real DDL confirmation and the
`AWARD_ID`-reassignment-frequency question remain open, requiring real BU
Oracle access (see `SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`'s Open
Questions). `BUDGET_RATE_AND_BASE`, flagged above, also remains open and
unevaluated — do not let it drop out of tracking.

## Date last updated

2026-08-01
