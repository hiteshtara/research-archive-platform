# Budget

## Authoritative scoping rule

**`archive.award_budget` (and every table beneath it) is keyed to one
exact `award_id`, but the Budget screen and every Budget-selection
service method operate on the whole `award_number` family, bounded to
sequences ≤ the Award version being viewed** — the same bounded-family
pattern already found for [Award Comments](Award%20Comments.md) and
[Time and Money](Time%20and%20Money.md), now confirmed a third time,
this time for Budget.

This directly overturns the "exact `awardId`" default the Time & Money
incident argued for defaulting to — Budget is provably the same
family-wide shape, not the exception.

### Source proof

`Award.getBudgets()` (`Award.java`) is the method every Budget-selection
service call in Kuali actually uses — not `getCurrentVersionBudgets()`
(the literal FK-scoped collection for one `award_id`, which exists but
is not what drives the screen):

```java
public List<AwardBudgetExt> getBudgets() {
    if (budgets == null || budgets.isEmpty()) {
        budgets = getAwardBudgetService().getAllBudgetsForAward(this);
    }
    return budgets;
}
```

`AwardBudgetServiceImpl.getAllBudgetsForAward` resolves the whole
version history by **`award.getAwardNumber()`** (the family), then
includes a sequence's budgets only if that sequence is **≤ the current
Award's own `sequence_number`**:

```java
public List<AwardBudgetExt> getAllBudgetsForAward(Award award) {
    HashSet<AwardBudgetExt> result = new HashSet<>();
    List<VersionHistory> versions = getVersionHistoryService()
        .loadVersionHistory(Award.class, award.getAwardNumber());
    for (VersionHistory version : versions) {
        if (version.getSequenceOwnerSequenceNumber() <= award.getSequenceNumber()
                && version.getSequenceOwner() != null
                && ((Award) version.getSequenceOwner()).getAwardDocument() != null) {
            result.addAll(((Award) version.getSequenceOwner()).getCurrentVersionBudgets());
        }
    }
    List<AwardBudgetExt> listResult = new ArrayList<>(result);
    Collections.sort(listResult);
    return listResult;
}
```

Both `getCurrentBudget(award)` (drives the Budget Summary screen) and
`getLastBudgetVersion(awardDocument)` (used by SAP integration, see
below) iterate over this same family-wide `award.getBudgets()` list —
neither one is scoped to a single `award_id`.

### Real-data proof

Award family `103692-00002` (26 Award sequences, `award_id`s 881365
through 3831872) has 38 `archive.award_budget` rows spread across
**26 distinct `award_id`s** — one or more budgets on nearly every
sequence, not concentrated on one version:

```
award_id=881365  seq=14  budget_version_number=1..12   (12 budgets on ONE sequence)
award_id=1114674 seq=15  budget_version_number=13
award_id=1226449 seq=16  budget_version_number=14
award_id=1377197 seq=17  budget_version_number=15
...
award_id=3831872 seq=46  budget_version_number=38
```

**`budget_version_number` is a monotonically increasing counter across
the whole family, not a per-`award_id` counter that restarts at 1 for
each new sequence.** Version 1–12 all belong to `award_id` 881365
(twelve separate budget revisions while the Award stayed on that one
sequence); version 13 is the first budget of the *next* sequence
(881365 → 1114674); the counter keeps climbing without resetting. This
is only interpretable at the family level — grouping strictly by
`award_id` would present versions 1–12 as "the whole history" and
completely miss that the family has 38.

## The real "current budget" rule — and why it doesn't transfer directly to a closed archive

Kuali's actual `getCurrentBudget()` is **not** "highest version number."
It is the newest budget, among `award.getBudgets()`, whose status is
one of three specific in-progress codes:

```java
protected AwardBudgetExt getCurrentBudget(Award award) {
    return getNewestBudgetByStatus(award,
        Arrays.asList(BUDGET_STATUS_CODE_IN_PROGRESS,   // "1"
                      BUDGET_STATUS_CODE_SUBMITTED,      // "5"
                      BUDGET_STATUS_CODE_TO_BE_POSTED)); // "10"
}
```

A *separate* method, `getPreviousBudget()`, returns the newest budget
whose status is the parameter-configured "Posted" status:

```java
protected AwardBudgetExt getPreviousBudget(Award award) {
    return getNewestBudgetByStatus(award, Collections.singletonList(getPostedBudgetStatus()));
}
```

**Confirmed against real archived data: status codes `1`
(In Progress) and `5` (Submitted) never appear anywhere in the whole
archive** (12,195 `award_budget` rows total). Only three codes exist in
practice: `9` = Posted (11,475 rows, the overwhelming majority), `14` =
Cancelled (711 rows), `10` = To Be Posted (9 rows total, archive-wide).
This makes sense for a **closed, historical archive**: every Award here
finished its real workflow long ago, so a budget "in progress" or
"submitted" essentially never survives to be the state Oracle has today
— those states are transient and get superseded by Posted or Cancelled
almost immediately in the live system.

**Practical consequence: Kuali's own `getCurrentBudget()` would return
an empty stub for nearly every Award in this archive**, because its
three target statuses almost never exist in already-closed data. The
archive's meaningful equivalent of "current" is therefore
`getPreviousBudget()`'s definition, not `getCurrentBudget()`'s literal
one:

> **Authoritative rule for this archive: "Current Budget Version" =
> the highest `budget_version_number`, among budgets scoped to
> sequences ≤ the Award version being viewed, whose
> `award_budget_status_code = '9'` (Posted). If no Posted budget
> exists in scope, fall back to the highest version number overall
> (excluding Cancelled) rather than showing nothing.**

This also explains real data like `award_id=881365`'s versions 2–9,
all `Cancelled`, sitting between a `Posted` version 1 and a `Posted`
version 10 — sequential version numbers routinely include abandoned
revision attempts. Naively picking "the highest version number" without
filtering by status would surface a `Cancelled` budget as if it were
the real current one.

## Hierarchy behavior

Budget does **not** cross Award Hierarchy boundaries (parent/child
Award, different `award_number`s). Every scoping mechanism found —
`AWARD_BUDGET_EXT.AWARD_ID`'s real Oracle FK, `getAllBudgetsForAward`'s
family resolution by `award.getAwardNumber()` — operates strictly
within one `award_number`. Nothing in `AwardBudgetServiceImpl`,
`AwardHierarchyServiceImpl`, or the OJB mappings joins Budget to a
different `award_number`. This matches [Award
Hierarchy](Award%20Hierarchy.md)'s own finding that hierarchy nodes are
financially independent — Budget is exact-`award_number`-family-scoped,
the same shape as Comments and Time & Money, not hierarchy-wide.

## Are Budget totals stored or computed?

**Stored, not computed.** `archive.award_budget.total_cost` /
`.total_direct_cost` / `.total_indirect_cost` are real, persisted
Oracle columns (`BUDGET.TOTAL_COST`/etc.), already-calculated values
Kuali itself maintains — not something this archive (or its API) needs
to sum from periods/line items. Real values above (e.g. `award_id
2104047`: total_cost=95.00, total_direct_cost=95.00,
total_indirect_cost=0.00) are internally consistent
(direct + indirect = total) in every sampled row. `archive.award_budget_period`
carries its own independent `total_cost`/`total_direct_cost`/
`total_indirect_cost` per period, likewise stored, not derived. The API
must surface these stored values directly and must not invent its own
summation logic — consistent with the user's explicit "no invented
calculations" rule.

This is true of `total_cost`/`total_direct_cost`/`total_indirect_cost`
specifically — the selected Budget version's **own requested amount**.
It does **not** extend to `Budget Total Cost Limit`/`Budget Change Total
Cost Limit`, a genuinely different pair of concepts covered next.

## Budget Total Cost Limit vs. Budget Change Total Cost Limit vs. Version Total Cost

A live comparison against real Kuali (Award `105698-00002`) found that
Kuali's Budget Overview screen displays **three different numbers**
side by side, and the archive's first implementation of Budget Summary
only exposed one of them (`total_cost`, mislabeled as if it were the
complete picture). This section is the proven source-to-screen mapping
that closed that gap — see `AwardBudgetVersions.jsp` (real Kuali JSP,
lines 45–121) for the screen source.

### Source-to-screen mapping

| Screen label | JSP binding | Real Java source | Real Oracle column |
|---|---|---|---|
| Budget Start/End Date | `document.budgetVersionOverview.startDate/endDate` | selected Budget version's own dates | `AWARD_BUDGET.START_DATE`/`END_DATE` |
| Budget Version Number | `document.budgetVersionOverview.budgetVersionNumber` | selected version | `AWARD_BUDGET.BUDGET_VERSION_NUMBER` |
| **Budget Total Cost Limit** | `document.award.budgetTotalCostLimit` | `Award.getBudgetTotalCostLimit()` = `MIN(awardBudgetLimit[totalCost], obligatedDistributableTotal)` | **`AWARD_BUDGET.OBLIGATED_TOTAL`** — the archive-facing name is `awardBudgetTotalCostLimit` |
| **Budget Change Total Cost Limit** | `document.budgetVersionOverview.totalCostLimit` | `AwardBudgetServiceImpl.getTotalCostLimit(award)` = `MIN(limit, obligatedTotal) − SUM(totalCost of that award's Posted budgets)` | **`AWARD_BUDGET.TOTAL_COST_LIMIT`** — the archive-facing name is `budgetChangeTotalCostLimit` |
| Version Total Cost | `document.budget.totalCost` (Budget Versions table) | the version's own requested amount | `AWARD_BUDGET.TOTAL_COST` |

### Both values are frozen, per-version snapshots — not live Award queries

`AwardBudgetServiceImpl.setBudgetLimits()` (called once, from
`createNewBudgetDocument()`, when a Budget version is first created)
writes both:

```java
public void setBudgetLimits(AwardBudgetDocument awardBudgetDocument, Award award) {
    AwardBudgetExt awardBudget = awardBudgetDocument.getAwardBudget();
    awardBudget.setTotalCostLimit(getTotalCostLimit(award));
    awardBudget.setObligatedTotal(new ScaleTwoDecimal(award.getBudgetTotalCostLimit().bigDecimalValue()));
    ...
}
```

Both `obligatedTotal` (→ `OBLIGATED_TOTAL`) and `totalCostLimit` (→
`TOTAL_COST_LIMIT`) are real OJB-mapped columns on `AWARD_BUDGET`
(`repository-budget.xml`), **not** transient/derived-at-render fields.
So the screen is not summarizing the Award live on every view — it is
displaying a value that was computed once, from the Award's state at
that moment, and then frozen onto that specific Budget version's row.
Answering the user's own suspicion directly: **the archive had
correctly implemented "Selected Budget Version," but had labeled only
its own `total_cost` as "Budget Summary."** The other two numbers on
Kuali's real header are a different business object (an Award-level
ceiling snapshot and a remaining-headroom snapshot), not the selected
version's own total — and both were already archived, unexposed.

### Live proof (Award `105698-00002`, budget_id 176666, version 5)

Both archived columns match Kuali's live screen to the cent:

```
archive.award_budget: total_cost_limit = 0.01        → screen "Budget Change Total Cost Limit: 0.01"    ✓ exact
archive.award_budget: obligated_total  = 699246.57    → screen "Budget Total Cost Limit: 699,246.57"      ✓ exact
```

And the `getTotalCostLimit` formula reproduces to the cent from
already-archived data — this award's four prior Posted budgets
(versions 1–4) total `585707.00 + 88902.00 + 52265.00 + (−27627.44) =
699246.56`:

```
699246.57 − 699246.56 = 0.01   ✓ matches "Budget Change Total Cost Limit" exactly
```

### A real, honest archive gap: not every historical version has these snapshots

For this same fixture, `obligated_total` (`awardBudgetTotalCostLimit`)
is `NULL` on the award's own versions 1–4 (all "Converted Budget
Document" — i.e. pre-migration/legacy versions created before Kuali's
`OBLIGATED_TOTAL` snapshot existed) and only populated starting version
5 — live-verified: `GET .../budget/versions` for this family returns
`awardBudgetTotalCostLimit: null` for versions 1–4 and `699246.57` for
version 5. `total_cost_limit` (`budgetChangeTotalCostLimit`) is a
*separate* column and is **not** null for those same versions — it was
populated on every version (585707.00 / 88902.00 / 52265.00 /
−27627.44 for versions 1–4 respectively, each exactly equal to that
version's own `total_cost` since no prior Posted budget existed yet to
subtract). The two snapshot columns were evidently introduced into
Kuali's `setBudgetLimits()` at different points, or one was
value-equal-to-zero-headroom by coincidence on early versions — do not
assume both are null or both are populated together; each must be
rendered independently. This is not a defect to hide: Kuali's live
screen would also show nothing meaningful for `Budget Total Cost Limit`
on a version whose Award-level ceiling was never snapshotted. The
API/UI must render a missing value as `null`/`—`, never `$0.00`, and
must **never recompute either field** from `award_budget_limit`/
`award_amount_info` — both columns already exist, verbatim, on
`archive.award_budget`, population-independent of ETL/migration
changes.

### Implementation

`AwardBudgetSummaryResponse`/`AwardBudgetVersionResponse` expose
`awardBudgetTotalCostLimit` (← `award_budget.obligated_total`) and
`budgetChangeTotalCostLimit` (← `award_budget.total_cost_limit`)
alongside — never merged into — `totalDirectCost`/`totalIndirectCost`/
`totalCost`. The UI's Budget Summary panel visually separates "Version
Total Direct/Indirect/Cost" (this version's own requested amount) from
"Budget Total Cost Limit"/"Budget Change Total Cost Limit" (Award-level
snapshots), and the Budget Versions table carries both new columns per
row.

## Budget ↔ SAP: related but not linked by any foreign key

This is the second confirmed case in the Award domain (after Time &
Money's shared use of `AwardAmountInfo`) of two subsystems that share a
real *data* dependency without sharing a real *foreign key*.

### What actually happens, traced from `SapIntegrationServiceImpl`

- The Budget package (`org.kuali.kra.award.budget.*`) has **zero**
  references to `SapIntegrationService` or anything SAP-related — the
  dependency runs the other direction: `SapIntegrationServiceImpl`
  imports and reads `AwardBudgetExt`, not the reverse.
- When building an outbound SAP payload, `SapIntegrationServiceImpl`
  calls `getLastBudgetVersion(awardDocument)`, which is — again —
  `award.getBudgets()` (the same family-wide, sequence-bounded list),
  taking the last (highest-version) entry:
  ```java
  protected AwardBudgetExt getLastBudgetVersion(AwardDocument awardDocument) {
      List<AwardBudgetExt> versions = awardDocument.getAward().getBudgets();
      return versions.isEmpty() ? null : versions.get(versions.size() - 1);
  }
  ```
- Whether that budget's totals are actually included in the SAP payload
  is gated on one specific status check:
  ```java
  String budgetStatus = abvoe.getAwardBudgetStatusCode();
  boolean awardBudgetVersionToBePosted =
      Constants.BUDGET_STATUS_CODE_TO_BE_POSTED.equalsIgnoreCase(budgetStatus); // "10"
  ...
  if (awardBudgetVersionToBePosted && budget != null && budget.getTotalDirectCost() != null) {
      sponsoredProgramStructure.setBUDGETTDC(budget.getTotalDirectCost().bigDecimalValue());
  }
  ```
  **The transmission-eligible moment is specifically "To Be Posted"
  (status `10`) — not "Posted" (status `9`).** "To Be Posted" is the
  transient pre-final state immediately before Kuali flips the same row
  to "Posted."
- `archive.award_transmission`/`archive.award_transmission_child` (see
  [`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`](../architecture/SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md))
  have **no `budget_id` column at all**, on either table — confirmed by
  querying `information_schema.columns` directly. The transmission
  record is purely Award-scoped (`award_id`, `award_number`,
  `sequence_number`); it captures *that a transmission happened*, not
  *which budget row supplied its numbers*.
- That existing document's own finding reinforces this: F&A rate-basis
  columns on `award_transmission_child`
  (`overhead_key`/`base_code`/`off_campus`) are "frequently copied
  forward from the *prior* transmission's own child row (not recomputed
  from current Budget) once a budget moves past 'to be posted'" — i.e.
  even Kuali's own live system doesn't always re-derive these from the
  current budget on every transmission.

### Why the two lifecycles cannot be reliably joined after the fact

1. **No shared key.** Neither `award_transmission` nor
   `award_transmission_child` references `budget_id`, so no query can
   attribute a specific archived transmission to a specific archived
   budget row with certainty.
2. **The triggering status doesn't survive to archival.** Because
   status `10` (To Be Posted) is Oracle's live value only in the brief
   window before it flips to `9` (Posted) — and this archive UPSERTs
   each budget row keyed by its own stable `budget_id`, always
   reflecting Oracle's *current* value, never a history of status
   transitions — an archived Posted budget gives no evidence of whether
   it was ever transmitted while briefly at "To Be Posted."
3. **Confirmed empirically**: in the ~8,590-family sample loaded so
   far, **zero families have both a budget and a transmission row at
   all** (19,621 `award_transmission` rows total, 12,195 `award_budget`
   rows total, no overlap by `award_number`). This is very likely a
   sampling artifact of loading Oracle's smallest `award_id`s first
   (not yet a representative cross-section), not proof that Budget and
   SAP transmission never co-occur in the full population — but it does
   mean **no real example is available today** to further validate any
   attempted correlation. This should be re-checked once a broader
   sample is loaded, but the *absence of a `budget_id` column* is
   proof enough on its own that no reliable join will ever exist,
   regardless of sample size.

### Recommended UI treatment (per explicit instruction)

Keep Budget and SAP as two independent sections, never gated on each
other:

- **Budget Status**: `award_budget_status_description` (Posted / To Be
  Posted / Cancelled), straight from the archived row — this is
  Budget's own lifecycle.
- **SAP Transmission Status**: a separate section, populated from
  `archive.award_transmission` scoped to the exact `award_id` (matching
  the existing, already-shipped `/sap-transmissions` endpoint's own
  scoping — see `AwardArchiveRepository.countTransmissions`/
  `findTransmissionRows`, both `WHERE award_id = :awardId`) — **not**
  matched to any specific budget version. Show `success_indicator`
  (Success/Failure) and `transmission_date` for the latest transmission
  if any exist; do not synthesize a richer Draft/Final/Posted/Sent/
  Accepted/Rejected state machine, because the archive genuinely does
  not have that data — only a boolean-ish `success_indicator` plus raw,
  unparsed `sent_data`/`returned_data` XML. **Do not block or hide the
  Budget section when no transmission exists** — a budget with zero
  SAP activity is still real, useful data.
- If no transmission rows exist for this `award_id` (the common case —
  see empirical finding above), show that plainly ("No SAP transmission
  recorded for this Award") rather than omitting the section entirely,
  so its absence is visibly confirmed rather than silently missing.

## Should a historical Award version show only its own budgets, or the whole family?

**Neither absolute answer — the real rule is bounded-family**: when
viewing Award version at sequence *N*, show every budget belonging to
sequences 1..*N* of that `award_number` (not sequence *N* alone, and
not sequences beyond *N* either). This exactly mirrors
`getAllBudgetsForAward`'s own bound
(`sequenceOwnerSequenceNumber <= award.getSequenceNumber()`). Viewing
an *older* historical Award version should see less budget history than
viewing the current one — never more, never a totally different set.

## Source-to-target mapping (already implemented at the ETL layer — no changes needed)

| Archive table | Role | Scoped by |
|---|---|---|
| `archive.award_budget` | Root budget document, one row per `budget_version_number` | exact `award_id` (real FK); family assembled by joining across sibling `award_id`s of the same `award_number` |
| `archive.award_budget_period` | Periods within one budget | `budget_id` |
| `archive.award_budget_line_item` | Non-personnel line items within a period | `budget_period_id` |
| `archive.award_budget_line_item_calculated_amount` | Calculated (rate-applied) amounts per line item | `budget_line_item_id` |
| `archive.award_budget_personnel_detail` | Personnel cost entries within a period | `budget_line_item_id` |
| `archive.award_budget_personnel_calculated_amount` | Calculated (rate-applied) personnel amounts | `budget_personnel_line_item_id` |
| `archive.award_budget_period_summary_calculated_amount` | Fringe (`rate_class_type='E'`)/F&A (`='O'`) summary per period | `budget_period_id` |
| `archive.award_budget_limit` | Budget ceilings | `award_id` + `budget_id` (both real FKs) |
| `archive.award_transmission`/`_child` | SAP transmission history — related but **not** budget-linked (see above) | exact `award_id` |

See
[`docs/architecture/AWARD_BUDGET_DESIGN.md`](../architecture/AWARD_BUDGET_DESIGN.md)
for the full schema derivation, nullable-column decisions, and the
six-level merged-`_EXT`-pair structure.

## Recommended API semantics

Given the bounded-family rule above, every `/api/v1/awards/{awardId}/budget/*`
endpoint should:

1. Resolve `awardId` → `award_number` + `sequence_number` (already a
   standard step via `requireAwardNumberForId`-style lookup).
2. Query `archive.award_budget` (joined to `archive.award_version` for
   the bound) `WHERE award_number = :awardNumber AND sequence_number <= :sequenceNumber`
   — not `WHERE award_id = :awardId` alone.
3. Determine "current" as: highest `budget_version_number` among rows
   with `award_budget_status_code = '9'` in that scoped set; if none,
   highest `budget_version_number` overall excluding `'14'` (Cancelled).
4. Child tables (`period`/`line_item`/`personnel_*`/etc.) join down from
   whichever `budget_id`(s) are in scope for the requested view (e.g.
   "current budget's periods" vs. "every version's periods" — the
   summary/versions/periods/details endpoints differ in how much of the
   scoped set they expand, not in the family-vs-exact-id question
   itself, which is answered the same way for all of them).
5. `document_number` on `archive.award_budget` is Budget's **own**
   workflow document number (from the now-not-independently-archived
   `BUDGET_DOCUMENT` KEW envelope) — display it as "Workflow Document"
   per-budget-version, the same pattern already used for Award's own
   `workflow_document_number` (see [Workflow
   Documents](Workflow%20Documents.md)), but note it is a **different**
   document number than the Award's own — never conflate or reuse
   Award's `workflow_document_number` for Budget, and never substitute
   `award_budget_type_code`/`modification_number` for it.

## Real fixture Award

**`award_number = "103692-00002"`**, 26 sequences (`award_id` 881365
through 3831872), 38 budget versions (`budget_version_number` 1–38),
statuses spanning Posted/Cancelled — real, deep, multi-sequence budget
history, ideal for verifying both the family-wide bound and the
Posted-status "current" rule end to end. Does not currently have SAP
transmission rows (see the empirical SAP finding above), so it cannot
by itself validate the SAP section's empty-state UI — a second fixture
with real transmission data should be located separately once a larger
population sample is loaded, if SAP-Budget co-occurrence needs live
verification later.

**`award_number = "105698-00002"`**, 9 sequences (`award_id` 7388
through 2280896), 5 budget versions — the fixture used to verify the
Budget Total Cost Limit / Budget Change Total Cost Limit semantic fix
above, and live-verified end to end against the deployed API
(`GET /api/v1/awards/{awardId}/budget/summary|versions`) after
deployment. Versions 1–4 (`award_id` 558547, sequence 8) are legacy
"Converted Budget Document"/amendment versions with `obligated_total`
(`awardBudgetTotalCostLimit`) `NULL` but `total_cost_limit`
(`budgetChangeTotalCostLimit`) populated (585707.00 / 88902.00 /
52265.00 / −27627.44 respectively — each equal to that version's own
`total_cost`, since no prior Posted budget existed yet to subtract);
version 5 (`budget_id` 176666, `award_id` 2280896, sequence 9) is the
"closeout" version with `total_cost = 0.01`,
`awardBudgetTotalCostLimit = 699246.57`,
`budgetChangeTotalCostLimit = 0.01` — proven against a live Kuali
screenshot of this exact Award, and matched to the cent by the live
deployed API.

## Budget Personnel: why the panel is usually empty (investigated, not a bug)

The Personnel tab/panel (`archive.award_budget_personnel_detail`/
`_calculated_amount`) reads as empty for nearly every Award in this
archive. Traced end to end (Oracle → extraction SQL → ETL prepare/
upsert → archive) for the real fixture (Award 105698-00002, Budget
version 1, `budget_id` 126805, whose live Kuali "Personnel" tab shows
four roster rows for Gael Orsmond with red "not found" job codes and
$0.00 salaries):

| Table | Oracle row count | Archive row count |
|---|---|---|
| `BUDGET_PERSONS` (roster) | 4,431, Award-scoped (confirmed: joining through `AWARD_BUDGET_EXT` doesn't reduce the count — every row belongs to an Award budget, none to Proposal Development) | 202 (187 distinct budgets) |
| `BUDGET_PERSONNEL_DETAILS` + `AWD_BUDGET_PER_DET_EXT` | **0** — a direct, unfiltered `COUNT(*)` against production Oracle, every Award and every Proposal, not scoped to our loaded population at all | 0 |
| `BUDGET_PERSONNEL_CAL_AMTS` + `AWD_BUDGET_PER_CAL_AMTS_EXT` | **0** — same direct check | 0 |

**Conclusion (proven): the archive faithfully mirrors Oracle. There is
no failing extraction/staging/upsert layer to fix, and no migration/ETL
change is warranted.** For `budget_id` 126805 specifically, direct
Oracle queries confirm **zero** rows in all four personnel-adjacent
tables checked: `BUDGET_PERSONS`, `BUDGET_PERSON_SALARY_DETAILS` (see
below), `BUDGET_PERSONNEL_DETAILS`, `BUDGET_PERSONNEL_CAL_AMTS`. The
Personnel endpoint/UI must stay faithful to persisted Oracle data —
report "no persisted roster," never synthesize or infer rows to match
what Kuali's screen happens to display.

**Open, deliberately untraced question:** why does Kuali's live
Personnel tab render four rows (Gael Orsmond, job codes flagged "not
found") for a budget with zero real `BUDGET_PERSONS` rows? A plausible
but **unproven** hypothesis is that Kuali synthesizes a default
personnel view (seeded from the Award's own investigator list) when no
real roster row exists, and that BU's actual cost entry for this budget
went through the bulk "Personnel (ONLY IF PERSONNEL TAB IS NOT USED)"
non-personnel line item instead (already correctly archived —
cost_element `51`, $309,513 direct on this same budget_id). Tracing the
exact Budget Personnel JSP/Java population path to confirm this is a
separate future task, not a blocker for anything currently in progress.

Checked and ruled out along the way:
- **Join keys**: all three extraction queries correctly join
  `BUDGET_ID` → `BUDGET` → `AWARD_BUDGET_EXT` (roster) or
  `BUDGET_PERSONNEL_DETAILS_ID`/`BUDGET_DETAILS_ID` (detail/calculated-
  amount chains) — no wrong-key join found.
- **Load order/FK dependencies**: `_AWARD_OWNED_TABLES` in
  `load_awards_from_csv.py` (child-before-parent) is a deletion-safe
  order, not the insert order — the real upsert call sequence inserts
  parent-before-child correctly throughout the whole 5-level Budget
  bundle.
- **Staging/upsert silently dropping rows**: not applicable — Oracle
  itself has zero `BUDGET_PERSONNEL_DETAILS`/`BUDGET_PERSONNEL_CAL_AMTS`
  rows to drop, archive-wide, not just for this fixture.
- **Roster shortfall (202 vs. Oracle's 4,431)**: partially, not fully,
  explained by population coverage — this archive currently holds
  12,195 of Oracle's 75,034 total Award budgets (16.2%), which would
  proportionally predict ~720 roster rows; the real 202 is ~28% of that
  estimate. Worth re-checking as the population grows; not conclusively
  a bug today, since roster usage likely isn't uniformly distributed
  across `award_id`s and this population was loaded in `award_id` order,
  not a random sample.

**New, previously-uncaptured table found during this investigation:**
`BUDGET_PERSON_SALARY_DETAILS` (owner `KCOEUS`, a real schema table, not
custom to BU) — `BUDGET_PERSON_SALARY_DETAIL_ID`, `PERSON_SEQUENCE_NUMBER`,
`BUDGET_ID`, `BUDGET_PERIOD`, `BASE_SALARY`, `PERSON_ID`, plus standard
OJB audit columns. 1,431 rows total, **all** Award-scoped (joining
through `AWARD_BUDGET_EXT` preserves the full count — Award-only,
unlike the shared `BUDGET_PERSONS`), but **every single row has
`BASE_SALARY = 0`** — zero non-zero rows archive-wide in Oracle. No FK
relationship in either direction; standalone table, own PK only. Not
yet archived. Real and Award-scoped, but would add all-zero data if
archived as-is — a decision for a future session, not acted on here.

## Date last updated

2026-08-04 (Budget Total Cost Limit / Budget Change Total Cost Limit
semantic fix: live comparison against real Kuali for Award
105698-00002 found the original Budget Summary implementation exposed
only the selected version's own `total_cost`, not the two Award-level
snapshot fields Kuali's own header also displays — both were already
archived on `archive.award_budget` and required no migration/ETL
change, only DTO/repository/service/UI exposure. Same-day follow-up:
Budget Personnel data-gap investigation traced all three personnel
tables to genuinely empty/near-empty Oracle source data — no code
fix applied, only a clarifying empty-state message in the UI — and
surfaced one new, unarchived Oracle table, `BUDGET_PERSON_SALARY_DETAILS`,
for a future decision).
