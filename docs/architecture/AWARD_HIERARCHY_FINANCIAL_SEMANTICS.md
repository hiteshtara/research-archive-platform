# Award Hierarchy Financial Semantics

## Status

**Research only — no implementation changes made.** This document answers a
single question raised before any Time & Money, Budget, or hierarchy-UI
implementation work begins: does `archive.award_hierarchy`/
`AwardHierarchyNodeResponse`'s current parent/child tree (each node showing
its own `currentObligatedAmount` independently) correctly reflect what real
Kuali Coeus does, or is the root Award actually a financial aggregation
point that child Awards feed into? The premise was investigated against real
Kuali Java source and Oracle DDL, not assumed. See
`AWARD_TIME_AND_MONEY_DESIGN.md` for the object graph this document builds
on and `AWARD_IDENTIFIER_MODEL.md` for the identifier vocabulary
(`award_number`/`award_id`/`sequence_number`/`workflow_document_number`)
used throughout.

## Purpose

Settle, with file:line citations, three questions before any hierarchy-aware
Time & Money/Budget/UI design is written:

1. Is "Award hierarchy" (parent/child) the same concept as "Award
   sequence/version," or orthogonal to it?
2. Does the root Award aggregate child Awards' obligated/anticipated
   amounts, budget, or Time & Money history — via a real service method,
   view, or report — or does each Award in the hierarchy carry its own
   independent financial state?
3. Does each hierarchy node have its own independent workflow document, or
   is there a shared/root-level document?

## Scope

Research only. No migration, ETL, API, or UI code was touched. The only
change made by this task is this new file.

## Source material used

Local Kuali Research checkout: `/Users/mukadder/kuali-project/kuali-research`
(the most complete of the three available checkouts; cross-checked against
`/Users/mukadder/Downloads/kuali-research-master` and
`/Users/mukadder/kuali_Home/kuali-research` where file lists differed — no
substantive differences found for the files cited below).

Java, read in full or in the relevant sections:
- `coeus-impl/src/main/java/org/kuali/kra/award/awardhierarchy/AwardHierarchy.java`
- `coeus-impl/src/main/java/org/kuali/kra/award/awardhierarchy/AwardHierarchyServiceImpl.java`
- `coeus-impl/src/main/java/org/kuali/kra/award/awardhierarchy/AwardHierarchyBean.java`
- `coeus-impl/src/main/java/org/kuali/kra/award/service/impl/AwardHierarchyUIServiceImpl.java`
- `coeus-impl/src/main/java/org/kuali/kra/award/web/struts/action/AwardActionsAction.java`
  (the real Struts action behind the Award "Hierarchy Actions" tab)
- `coeus-impl/src/main/java/org/kuali/kra/award/home/Award.java`
  (`initializeAwardWithDefaultValues`)
- `coeus-impl/src/main/java/org/kuali/kra/timeandmoney/service/impl/ActivePendingTransactionsServiceImpl.java`
  (the full money-routing algorithm, already partially documented in
  `AWARD_TIME_AND_MONEY_DESIGN.md`, re-read here specifically for
  hierarchy-wide aggregation)
- `coeus-impl/src/main/java/org/kuali/coeus/common/impl/medusa/MedusaServiceImpl.java`,
  `coeus-impl/src/main/java/org/kuali/kra/award/web/struts/action/AwardMedusaAction.java`
  (read to positively rule out a false lead — see Findings)

DataDictionary/OJB mapping:
- `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`
  (`AwardHierarchy`'s class-descriptor, lines 1448–1459)

Oracle DDL:
- `coeus-db/coeus-db-sql/src/main/resources/co/kuali/coeus/data/migration/sql/oracle/kc/bootstrap/V300_107__schema.sql`
  (`AWARD_HIERARCHY`'s base `CREATE TABLE`, lines 1124–1146)
- `coeus-db/coeus-db-sql/.../oracle/kc/bootstrap/V510_133__KC_IX_KRACOEUS-6157.sql`
  (later index additions)

Web/JS (to check what the hierarchy UI itself actually displays, since a
UI-only "cumulative" label would be exactly the kind of thing that could be
mistaken for a real aggregation):
- `coeus-webapp/src/main/webapp/scripts/awardHierarchy.js`
- `coeus-webapp/src/main/webapp/WEB-INF/tags/medusa/medusaAwardSummary.tag`

This archive's own prior research, read for reconciliation, not
re-derivation: `AWARD_TIME_AND_MONEY_DESIGN.md`, `AWARD_DOMAIN_DECOMPOSITION.md`,
`AWARD_IDENTIFIER_MODEL.md`. (`AWARD_DOMAIN_STUDY.md` was not separately
re-read — Decomposition already synthesizes it, and Time & Money's own
document is the more directly relevant cross-check for this question.)

This archive's current implementation, read for the "as-built" baseline:
`api/src/main/java/edu/bu/archive/adapter/in/web/dto/award/AwardHierarchyNodeResponse.java`,
`api/src/main/java/edu/bu/archive/adapter/out/persistence/AwardArchiveRepository.java`
(`findHierarchyRoot`/`findHierarchyEdges`/`findSummaryCards`, lines 530–583).

## The verdict

**The user's premise is half right, and the half that's wrong matters.**
"Hierarchy" and "sequence/version" are two completely orthogonal concepts in
real Kuali — confirmed, not assumed (see Finding 1). But **the root Award
does not aggregate child Awards' financial totals**, and no code, view, or
report was found anywhere in the source tree that sums descendant-Award
amounts into a root-level cumulative figure (see Finding 2). Every Award in
a hierarchy — root or child — maintains its own independent, real,
append-only `AwardAmountInfo` ledger, its own independent workflow document,
and (per `AWARD_DOMAIN_DECOMPOSITION.md`'s already-established Budget
scoping) its own independent Budget. What *is* true, and is presumably the
real observation behind the user's framing, is that money moves between
hierarchy nodes only through explicit, auditable Time & Money transfers
routed along the parent/child path (Finding 2's routing algorithm) — the
hierarchy is a real **funding-flow topology**, not a **financial
aggregation** structure. A child Award is not "an amendment of the root
recorded on a separate row" (that's what sequence/version is for); it is a
genuinely separate Award family that happens to receive its funding, in
whole or in part, via tracked transfers from its parent, and that reports
its own true obligated/anticipated totals independently.

This archive's current model — `archive.award_hierarchy` as a plain
parent/child edge list keyed by `award_number`, each
`AwardHierarchyNodeResponse` node carrying its own independent
`currentObligatedAmount` — is **not a simplification that needs correcting
for the aggregation the user described**, because that aggregation does not
exist in Kuali either. It is, however, missing the funding-flow/transfer
data (Time & Money's `TransactionDetail`/`AwardAmountTransaction`, already
archived per `AWARD_TIME_AND_MONEY_DESIGN.md`) that would let a future UI
show *why* a child's total changed — see "What this means for this
archive" below.

## Findings

### 1. Hierarchy and sequence/version are orthogonal — proven, not assumed

`AwardHierarchy` (`org.kuali.kra.award.awardhierarchy.AwardHierarchy.java`)
is keyed by `awardNumber`, `parentAwardNumber`, `rootAwardNumber` — every
node in a hierarchy has its **own distinct `award_number`** (line 42, and
the Oracle DDL below: `AWARD_NUMBER VARCHAR2(12) NOT NULL`, no
`SEQUENCE_NUMBER` column exists on the table at all). A "child Award" is a
different Award **family**, not a later `sequence_number` of the same
family.

This is confirmed by how a child is actually created,
`AwardHierarchyServiceImpl.createNewChildAward` (lines 208–221):

```java
public AwardHierarchy createNewChildAward(AwardHierarchy targetNode) {
    Award newAward = new Award();
    Award copyDateAward = targetNode.getAward();
    newAward.setAwardNumber(targetNode.generateNextAwardNumberInSequence());
    AwardHierarchy newNode = new AwardHierarchy(targetNode.getRoot(), targetNode, newAward.getAwardNumber(), newAward.getAwardNumber());
    ...
}
```

`new Award()` calls `initializeAwardWithDefaultValues()`
(`Award.java` lines 346–362), which sets `setSequenceNumber(1)`
unconditionally — the new child Award starts its **own** version lineage
at sequence 1, completely independent of whatever sequence number the
parent is currently on. `generateNextAwardNumberInSequence()`
(`AwardHierarchy.java` lines 257–268) increments the numeric suffix of the
award number string itself (e.g. `100567-00001` → `100567-00002`) — this
suffix is part of `award_number`, textually similar to a version ordinal
but a completely different column/concept from `sequence_number`.

The real user-facing trigger for this is a dedicated UI action, not the
normal "new version"/amendment flow: `AwardActionsAction.create()`
(`AwardActionsAction.java` lines ~301–330) is the handler for "Create New
Child" on the Award's **Hierarchy Actions** tab, a UI panel entirely
separate from Award's version/amendment actions. Each new child Award goes
through `prepareToForwardToNewChildAward` (lines 819–837), which calls
`awardForm.setCommand(KewApiConstants.INITIATE_COMMAND); createDocument(awardForm);`
— i.e. it **initiates a brand-new KEW workflow document** for the child,
the same "start a new document" call path used for any new Award, not a
step within the parent's existing document.

**Conclusion**: the user's framing that a "child Award" might really just
be "the same award_number's next sequence_number" is incorrect — verified
directly, not merely by convention. Hierarchy and sequence/version are two
independent axes: an Award can gain new sequence/version rows (amendments)
without ever entering a hierarchy, and a hierarchy child is a full new
Award family, with its own sequence-1 start and its own workflow document,
linked to its parent only by the `AWARD_HIERARCHY` edge.

### 2. No aggregation of hierarchy financial totals exists anywhere in source

Searched exhaustively (`grep -rn` for `cumulative`/`Cumulative`,
`rollUp`/`RollUp`, `sumAcrossHierarchy`, `hierarchyTotal`,
`aggregateHierarchy`, `totalOfHierarchy` across
`coeus-impl/src/main/java/org/kuali/kra/award/`,
`coeus-impl/src/main/java/org/kuali/kra/timeandmoney/`, and the full
`coeus-webapp` JSP/tag/JS tree) — **no method, view, JSP, or report was
found that sums a root Award's descendants' `AwardAmountInfo` totals into a
single hierarchy-wide figure.**

What does exist, and what plausibly gave rise to the user's framing, is
`ActivePendingTransactionsServiceImpl` — the real money-routing algorithm
(already partially documented in `AWARD_TIME_AND_MONEY_DESIGN.md`'s
"Pending vs. history" section, re-read here specifically for
aggregation). Every method in this class
(`processPendingTransactionWhenParentChildRelationShipExists`,
`...WhenChildParentRelationShipExists`,
`...WithIndirectRelationship`, lines 312–404) walks the `AWARD_HIERARCHY`
parent/child edges to route one Time & Money transaction's dollar amount
from a **source** award to a **destination** award, creating one new
`AwardAmountInfo` row per award actually touched along that path (via
`handleTransaction`, e.g. `getUpdatedDestinationDownNodeAmountInfo`, lines
657–691, which does `awardAmountInfo.getAmountObligatedToDate().add(pendingTransaction.getObligatedAmount())`
— an **additive update to that one award's own running ledger**, not a
write to any shared/root total). A transaction from an external sponsor to
a non-root child, for example
(`processPendingTransactionWhenSourceIsExternal`, lines 247–276), creates
`INTERMEDIATE` `TransactionDetail` rows for every hop from root down to
that child — proving money conceptually "passes through" ancestor
awards — but each ancestor's own `AwardAmountInfo` total is only updated if
`handleTransaction` is actually called for that node along the path;
intermediate hops do not automatically inherit a share of the money in a
way visible outside the ledger updates the algorithm itself performs.

Critically: **every Award in the hierarchy — root or child — has its own
independent `AwardAmountInfo` collection**, resolved per-award by
`awardAmountInfoService.fetchAwardAmountInfoWithHighestTransactionId(award.getAwardAmountInfos())`
(`AwardHierarchyServiceImpl.java` line 648, and again in
`ActivePendingTransactionsServiceImpl.java` line 130) — there is no
"the root's `AwardAmountInfo` list also contains the children's rows"
sharing of any kind. Budget is scoped identically: per
`AWARD_DOMAIN_DECOMPOSITION.md`'s "Tier 2 — Award Budget" entry, Budget
"Depends on: Core Award only" (one `award_id`), with no hierarchy-wide
dependency documented or found.

The one place "cumulative" appears in the UI,
`medusaAwardSummary.tag`'s "Anticipated Cumulative"/"Obligated Cumulative"
labels (bound to `node.extraInfo.anticipatedTotalAmount`/
`amountObligatedToDate`), is confirmed to be **that one node's own running
total** (its own `AwardAmountInfo`'s current state), not a sum across the
hierarchy — and, more importantly, this "Medusa" page is a **different
feature entirely**: `MedusaServiceImpl.buildGraph(Award)` (lines 367–390)
builds a cross-domain relationship graph (Award ↔ Proposal ↔ Negotiation ↔
Subaward ↔ Protocol), not the Award-hierarchy parent/child tree. Conflating
"Medusa" with "Award Hierarchy" would have been an easy mistake reading the
UI alone — ruled out directly from source. `awardHierarchy.js` (the actual
Hierarchy tab's own script), line 380–381, likewise only ever writes one
node's own `anticipatedTotalAmount`/`amountObligatedToDate` into that
node's own table row — confirming the real Hierarchy UI, like this
archive's current `AwardHierarchyNodeResponse`, shows each node's amount
independently.

**Conclusion**: no aggregation mechanism exists. The root Award does not
"own" the hierarchy's cumulative total in any code-enforced sense. Each
Award's obligated/anticipated totals are its own real, independently
tracked financial state — the hierarchy only governs *how money is allowed
to move between them* (an explicit, auditable transfer, never an implicit
share), not *whose ledger the total ultimately lives on*.

### 3. Each hierarchy node has its own independent workflow document

Confirmed directly (Finding 1's `prepareToForwardToNewChildAward` citation):
creating a hierarchy child issues a real `INITIATE_COMMAND`/`createDocument`
call, the same KEW-document-creation path any new Award goes through. There
is no "placeholder document" reused as the ongoing workflow record for
child Awards in normal use — `AwardHierarchyServiceImpl`'s
`loadPlaceholderDocument`/`createPlaceholderDocument`
(lines 298–319, 518–532) is a narrowly-scoped bulk-persistence helper used
only inside `persistAwardHierarchy`, to batch-save multiple **newly
copied** Award rows in one document transaction when an entire branch is
copied programmatically (`copyAwardAndAllDescendantsAsNewHierarchy` and
similar bulk-copy operations) — it is not how an individual "Create New
Child Award" UI action persists its result, and it is blanket-approved
immediately on creation (`documentService.blanketApproveDocument(...)`,
line 527), so it never appears as a live, user-facing workflow document.
This matches `AWARD_IDENTIFIER_MODEL.md`'s already-established fact that
`workflow_document_number` is globally unique across every archived
`award_version` row (26,930 of 26,930 distinct) — consistent with "every
Award, root or child, gets its own document," not a shared one.

### 4. No "cumulative funding across the hierarchy" computation exists

Per Finding 2: searched thoroughly, found none. The closest candidates —
`AwardHierarchyUIServiceImpl` (builds per-node JSON for the JQuery
hierarchy tree UI, no summation across nodes),
`AwardHierarchyServiceImpl.populateAwardHierarchyNodesForTandMDoc`
(lines 686–743, which does add unprocessed `PendingTransaction` amounts
into **that one node's own** projected total for a single-node Time & Money
view — still per-node, not cross-hierarchy), and the Medusa/`isBudgetVersionSummaryCumulative`
false leads (Finding 2) — were each individually ruled out. This is stated
explicitly per instruction rather than left implicit: **no such method,
view, or report exists in the available Kuali Coeus source.**

## Cross-check against this archive's own prior research

- `AWARD_TIME_AND_MONEY_DESIGN.md` already correctly identifies
  `AWARD_HIERARCHY` as "the literal, real, Oracle-PK-enforced parent/child
  Award relationship that Time and Money's own core money-routing algorithm
  reads and walks on every single transaction approval" — this document
  confirms that characterization is accurate and extends it: the algorithm
  *routes* money along that relationship, it does not *aggregate* against
  it.
- That document's `originating_award_version` finding
  (`AWARD_AMOUNT_INFO.ORIGINATING_AWARD_VERSION` records which
  **sequence_number** of a given `award_number` a Time & Money snapshot
  belongs to) is a different, correct axis — it disambiguates *which
  version of one Award family* a ledger row belongs to, orthogonal to
  *which Award family in the hierarchy* it belongs to (`award_number` on
  the same row already does that). Nothing here contradicts it.
- `AWARD_IDENTIFIER_MODEL.md`'s six-identifier model already keeps
  `award_number`/`sequence_number`/`workflow_document_number` conceptually
  separate — this document is a direct extension of that same discipline
  to the hierarchy relationship, not a new naming scheme.
- `AWARD_DOMAIN_DECOMPOSITION.md`'s Budget entry ("Depends on: Core Award
  only") is corroborated, not contradicted, by this pass — no hierarchy
  dependency was found for Budget either.

## What this means for this archive

**Already correctly modeled:**
- `archive.award_hierarchy` as a version-agnostic (`award_number`-keyed, no
  `sequence_number`) parent/child edge list is the right shape — it
  matches `AWARD_HIERARCHY`'s real Oracle DDL exactly (no
  `AWARD_ID`/`SEQUENCE_NUMBER` column exists on the source table).
- `AwardHierarchyNodeResponse.currentObligatedAmount` being each node's own
  independent amount is **correct, not a gap** — it matches both the real
  Oracle data model (each Award has its own `AWARD_AMOUNT_INFO` rows) and
  the real Kuali hierarchy UI's own behavior (`awardHierarchy.js` only ever
  displays one node's own total).
- Time & Money's `originating_award_version` (already archived per
  `AWARD_TIME_AND_MONEY_DESIGN.md`) correctly keeps "which version" and
  "which hierarchy member" as separate facts on the same ledger row.

**What a future Time & Money/Budget/hierarchy-UI implementation should not
get wrong** (flagged, not designed, per this task's scope):
- Do not build a "total across the whole hierarchy" figure and present it
  as if it were a Kuali-native concept — it would be this archive
  *inventing* a rollup Kuali itself never computed, not reproducing one.
  If a future feature wants that number, it must be clearly labeled as a
  derived/computed figure, not "the" obligated amount.
- Do not assume a child Award's obligated/anticipated totals are somehow
  "backed" by the root's budget in a way that makes the child's own
  `AWARD_AMOUNT_INFO` redundant — they are the child's own real, distinct
  financial state, and the archive already treats them that way (per
  award/version) correctly.
- Do treat the hierarchy as a genuine **funding-flow graph**: a future
  feature that wants to explain *why* a child's total changed should join
  `archive.award_hierarchy` against the already-archived
  `archive.transaction_detail`/`archive.award_amount_transaction` (Time &
  Money, per `AWARD_TIME_AND_MONEY_DESIGN.md`'s Implementation section) —
  that is where the real, auditable transfer history lives, not in any new
  aggregation logic.
- Do not conflate the Award Hierarchy tree with "Medusa" (the
  Proposal/Award/Negotiation/Subaward/Protocol relationship graph) if that
  term or its "cumulative" labels are ever encountered in further Kuali
  source spelunking — they are unrelated features that happen to share the
  word "cumulative" in one UI label.
- Budget remains per-`award_id`, not hierarchy-wide — no future Budget work
  should assume a child Award can read or extend the root's Budget rows
  directly.

## Explicitly flagged — not verified

- **No BU-specific customization of `AWARD_HIERARCHY` or the
  hierarchy-creation Java classes was found** in the available checkout —
  every file cited above lives under the generic `org.kuali.kra`/
  `org.kuali.coeus` packages, not the `edu.bu.kuali.kra` BU-override
  packages used elsewhere in this codebase (e.g.
  `PendingTransactionExtension`, confirmed BU-specific in
  `AWARD_TIME_AND_MONEY_DESIGN.md`). This document cannot rule out that
  BU customized hierarchy-creation behavior, UI labels, or the routing
  algorithm's edge cases in a private fork or a runtime parameter
  (`ParameterService`-driven behavior, e.g.
  `ALLOW_TM_WHEN_PENDING_AWARD_PARAM`, is already known to change routing
  behavior at BU's discretion per `AWARD_TIME_AND_MONEY_DESIGN.md`) beyond
  what the generic Kuali Coeus source shows — this would need real BU
  Oracle data (e.g. do any two rows in BU's actual `AWARD_HIERARCHY` table
  show a pattern the generic algorithm wouldn't produce) to fully close
  out, the same class of caveat already flagged for BU's `UNIT_ADMINISTRATOR_TYPE`
  customization in the Central Admin Contacts investigation.
- The "Placeholder Document" bulk-persistence path (Finding 3) was traced
  far enough to confirm it is not the normal single-child-creation path,
  but its full set of callers (which bulk "copy entire hierarchy branch"
  UI actions actually invoke `persistAwardHierarchies`/
  `persistAwardHierarchy(node, RECURS_HIERARCHY)` in practice) was not
  exhaustively enumerated — flagged as unverified in case a future pass
  needs the complete list of hierarchy-bulk-copy entry points.
- Whether BU's real production `AWARD_HIERARCHY` table contains any rows
  at meaningful scale (i.e., whether hierarchies are a common,
  occasionally-used, or vanishingly rare pattern in BU's actual award
  population) was not checked — this document establishes what the
  *code* does, not how often BU users actually exercise "Create New Child
  Award" versus a simple amendment. No BU Oracle access was available in
  this environment, the same limitation noted in
  `AWARD_TIME_AND_MONEY_DESIGN.md`'s smoke-test plan.

## Date last updated

2026-08-03 (initial research pass, no prior version).
