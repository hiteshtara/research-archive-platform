# Negotiation authorization — data profile and rule options

Profiled against **dev RDS**, read-only, over the SSM tunnel, 2026-10-27.
SQL: [`negotiation-authorization-profile.sql`](negotiation-authorization-profile.sql).

**No rule is proposed as decided here.** This document sets out what the data
supports so a rule can be chosen. Nothing is implemented.

---

## 1. Grain — Negotiation is flat, unlike every other module

| | |
|---|---|
| `archive.negotiation` rows | **10,775** |
| distinct `negotiation_id` | **10,775** |
| distinct `document_number` | **10,775**, zero null |
| `document_number` values used more than once | **0** |

There is no `negotiation_number` + `sequence_number` pair and no version
table. **One row is one Negotiation.** The family-vs-version problem that
dominated Award, Proposal and Subaward does not exist here, and question 9
("do associations change across versions") is therefore **not applicable** —
there are no versions to change between.

## 2. Association types

| Association type | Negotiations | With an `associated_document_id` |
|---|---|---|
| **None** | **8,533** (79.2%) | 8,533 |
| Award | 2,223 (20.6%) | 2,223 |
| Subaward | 16 | 16 |
| Institutional Proposal | 3 | 3 |

Note: **every** Negotiation has a non-empty `associated_document_id`,
including all 8,533 typed `None`. The column is populated regardless of
whether an association exists.

## 3. ⚠️ The critical finding: `associated_document_id` is ambiguous

`associated_document_id` is a free `TEXT` column. Matching it against record
identifiers *without* gating on the association type produces large-scale
false positives:

| Association type | matches `award_number` | matches Award **workflow doc number** | matches `proposal_number` | matches nothing |
|---|---|---|---|---|
| **None** (8,533) | 0 | **7,246** | **6,980** | 365 |
| Award (2,223) | **2,223** | 0 | 0 | 0 |
| Subaward (16) | 0 | 16 | 15 | 0 |
| Institutional Proposal (3) | 0 | 0 | 3 | 0 |

**6,073 of 10,775 ids match more than one record kind.**

Those 7,246 and 6,980 hits on type `None` are **not associations**. A
Negotiation typed `None` is unassociated by definition; its
`associated_document_id` is its own KC document number, which shares a
numeric space with Award workflow document numbers and collides with
proposal numbers.

> **Any rule that resolves `associated_document_id` by string match without
> gating on `negotiation_association_type_description` will grant access to
> thousands of unassociated Negotiations through coincidence.**

Safe resolution is type-gated:

- `'Award'` → `award_number` — **2,223 / 2,223, unambiguous**
- `'Institutional Proposal'` → `proposal_number` — 3 / 3
- `'Subaward'` → **no clean key**; its 16 rows match an Award doc number (16)
  and a proposal number (15). Unresolved.

Also: one association id is shared by up to **18** Negotiations; no id
contains a delimiter, so an id never names more than one record directly.

## 4. Lead unit — far better coverage, and it agrees with the Awards

`archive.negotiation_search_attribute` holds exactly one row per Negotiation
(PK), denormalised at ETL time, with `attribute_source` recording where it
came from.

| `attribute_source` | rows | with `lead_unit_number` |
|---|---|---|
| `UNASSOCIATED_DETAIL` | 8,533 | 8,495 |
| `AWARD` | 2,223 | 2,223 |
| `NONE` | 19 | 0 |

| | |
|---|---|
| Negotiations with a search-attribute row | **10,775 (100%)** |
| with a `lead_unit_number` | **10,718 (99.47%)** |
| whose lead unit **matches `archive.unit`** | **10,718 — 100% of those present** |
| unmatched lead units | **0** |

**And the two approaches agree where both apply:** for all **2,223**
Award-associated Negotiations, the Negotiation's lead unit equals its
Award's lead unit — **2,223 agree, 0 differ, 0 null**. Department access and
Award inheritance are not competing answers for that population; they are the
same answer.

`archive.negotiation_unassociated_detail`: 8,554 rows covering 8,554
Negotiations — 8,516 with `lead_unit`, 8,529 with `pi_person_id`, but only
**693 with `contact_admin_person_id`** (8%).

## 5. Negotiator is not a usable authorization key

All 10,775 Negotiations carry a `negotiator_person_id`, but there are only
**16 distinct negotiators**. Each would convey roughly 673 records, and the
value says nothing about department. Useful as an attribute; useless as a
scope.

## 6. Coverage under each candidate rule

Measured, not estimated. "Safe" means type-gated resolution per §3.

| Basis | Negotiations covered | % |
|---|---|---|
| Award inheritance only | 2,223 | **20.6%** |
| Award + Proposal inheritance | 2,226 | **20.7%** |
| Lead unit only | 10,718 | **99.47%** |
| **Association (safe) OR lead unit** | **10,721** | **99.50%** |
| **No basis at all** | **54** | **0.50%** |

**Inheritance alone would make 79% of Negotiations invisible to every
non-Central user** — the same shape of failure that per-version Subaward
authorization produced (4.06%), and the reason this was profiled before
designing.

## 7. Metadata exposed by Negotiation responses

Fields in Negotiation DTOs that describe *other* records or people:
`associatedDocumentId`, `principalInvestigatorName`, `piName`, `piPersonId`,
`piRolodexId`, plus sponsor and lead-unit names on the search attribute.

Two surfaces to settle alongside the rule:

- **Attachments.** Negotiation attachments already carry a `RESTRICTED` flag
  from earlier work, enforced independently of record authorization. Whether
  the record rule stacks on top of it, and in which order, is undecided.
- **Cross-module document/attachment search** is already **fail-closed** for
  any scoped caller (`requireUnrestrictedForCrossModuleSearch`), so it leaks
  nothing today, but it also returns nothing — a Negotiation rule is what
  would let it be re-opened.

---

## 8. Decision matrix

| Option | Coverage | Security | Edge cases | Assessment |
|---|---|---|---|---|
| **A. Inherit from associated Award** | 2,223 (20.6%) | Strong. Reuses the proven Award engine; nothing new to get wrong. Unambiguous once type-gated | 8,533 unassociated Negotiations reachable by nobody but Central | Sound but, alone, far too narrow |
| **B. Inherit from associated Proposal** | 3 | Same | Negligible population | Worth including for completeness; decides almost nothing |
| **C. Department access via `negotiation_search_attribute.lead_unit_number`** | 10,718 (99.47%) | Good, with caveats below. Reuses the existing unit/descendant machinery and `archive.unit` matches 100% | 57 Negotiations have no lead unit | Highest coverage by a wide margin |
| **D. Central-only for records with no reliable basis** | 54 (0.50%) | Strongest; nothing is guessed | Those records invisible to departments | Natural complement to C |
| **A+B+C+D combined** | 10,721 (99.50%) | Inheritance where an association is real, department otherwise, Central for the remainder | 54 Central-only | **The only combination with workable coverage** |

### What makes C different from the Award/Subaward rules

Award and Subaward authorization derive the unit from the **record's own
authoritative row**. Option C derives it from `negotiation_search_attribute`,
an **ETL-denormalised** table. Three things to weigh:

1. It is not the system of record; it is a convenience projection. If the ETL
   recomputes it differently, scope changes silently.
2. For 8,533 rows the source is `UNASSOCIATED_DETAIL`, i.e. the lead unit the
   negotiator typed on an unassociated Negotiation — self-asserted, not
   derived from an Award.
3. In its favour: coverage is 99.47%, matching against `archive.unit` is
   100%, and for every case where an Award exists to check against, the two
   agree exactly.

### Why "inherit only" fails here, concretely

If only A+B were adopted, 8,549 Negotiations (79.3%) would have no
authorization basis and would be visible to Central only. That is the
Subaward per-version mistake repeated — and it would be found after
implementation rather than before, if this profile had not been run first.

---

## 9. Questions requiring your decision

**None of these should be answered by engineering.**

1. **Is `negotiation_search_attribute.lead_unit_number` acceptable as an
   authorization key**, given it is an ETL-denormalised projection and, for
   the 8,533 unassociated rows, self-asserted by the negotiator? This is the
   single decision that determines whether coverage is 20.7% or 99.5%.
2. **Where an association exists, does it take precedence over the lead
   unit, or are they ORed?** They agree in all 2,223 observed cases, so this
   only matters for future data — but it must be decided, not defaulted.
3. **The 16 `Subaward`-type Negotiations** have no clean resolution key.
   Central-only, lead-unit, or defer?
4. **The 54 with no basis** — Central-only, or is that unacceptable?
5. **Attachment interaction.** Does the Negotiation record rule stack on the
   existing `RESTRICTED` attachment flag, and which is evaluated first?
6. **Should cross-module document search be re-opened** to scoped callers
   once Negotiation has a rule, or stay fail-closed until every module has
   one? (IRB would still have none.)

## 10. Next engineering tasks, in order

1. **Await the decisions above.** No Negotiation authorization code until
   rules are approved.
2. Investigate the **270 unresolved Subaward families** — test whether the
   759 dangling `award_id` references are an ETL gap (does
   `subaward_funding.award_number` resolve where `award_id` does not?) before
   anyone proposes a fallback rule.
3. Close the **Proposal Dashboard count-scoping gap** (D2) — `proposals`,
   `proposal_history_records` and the `PROPOSAL` document tally are still
   archive-wide.
4. Review and merge **PR #68** (tunnel `TargetNotConnected` retry).
5. **SEC-008 audit scope** remains open and must not be marked complete.
6. **ARCHIVE_ADMIN management interface** — still only schema and service;
   no administrative UI exists.
