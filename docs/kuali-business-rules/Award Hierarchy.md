# Award Hierarchy

## The rule

**No financial aggregation exists anywhere in Kuali source, for Award
Hierarchy.** The root Award of a parent/child hierarchy does not sum or
roll up its children's obligated/anticipated totals, and vice versa. An
exhaustive search of the real Kuali Coeus source for `cumulative`,
`rollUp`, and similar terms in the hierarchy code path found nothing.
Each Award node — root or child — keeps its own independent, append-only
`AwardAmountInfo` ledger and its own Budget. There is no view, no
computed column, and no service method anywhere that reports a
hierarchy-wide total.

Award Hierarchy is a **funding-flow topology, not an aggregation
structure.** Money only ever moves between hierarchy nodes through
explicit, auditable Time & Money transfer transactions routed along
parent/child edges (`ActivePendingTransactionsServiceImpl`) — never
through implicit rollup. See [Time and Money](Time%20and%20Money.md) for
how those transfers actually work.

## The "Medusa" trap

"Medusa" (the Proposal → Award → Negotiation → Subaward → Protocol
cross-reference graph) is a **completely separate, unrelated feature**
from Award Hierarchy — but both UIs use the word "cumulative" in their
labels, which makes them easy to confuse when reading Kuali source or
screenshots out of context. Award Hierarchy is strictly
parent-Award-to-child-Award. Medusa is a much broader object-relationship
graph with no financial-aggregation behavior of its own either.

## Why this matters

This project's archive must **never invent hierarchy-wide aggregation**
even when it would be convenient to show one number for a whole
hierarchy — doing so would misrepresent a real Kuali screen that
deliberately never shows that number, because the underlying data model
was designed around explicit transfers, not implicit totals. Each
hierarchy node must remain financially independent in this archive, the
same as it is in the real system.

## Evidence

- `AwardHierarchy.java`, `AwardHierarchyServiceImpl.java`
  (`createNewChildAward`, lines 208-221) — new hierarchy nodes get a
  brand-new `award_number`/workflow document, confirming hierarchy and
  Award versioning are orthogonal (see [Award
  Versions](Award%20Versions.md)).
- `Award.java` (`initializeAwardWithDefaultValues`),
  `AwardActionsAction.java`.
- `ActivePendingTransactionsServiceImpl.java`,
  `MedusaServiceImpl.java` — confirm the funding-flow-vs-aggregation and
  Medusa-vs-Hierarchy distinctions respectively.
- `awardHierarchy.js`, `medusaAwardSummary.tag` (UI layer, confirming
  the "cumulative" label collision between the two features).
- Real example: award number suffix increments on hierarchy creation,
  e.g. `100567-00001` → `100567-00002`.

## Caveat

No BU-specific customization of `AWARD_HIERARCHY` or the hierarchy
service classes was found in the available checkout — this does not
rule out a private fork or `ParameterService`-driven behavioral
difference on BU's real production instance, and BU's actual
hierarchy-usage frequency has not been checked (no direct Oracle access
during this investigation).

## See also

[`docs/architecture/AWARD_HIERARCHY_FINANCIAL_SEMANTICS.md`](../architecture/AWARD_HIERARCHY_FINANCIAL_SEMANTICS.md)
for the full investigation.
