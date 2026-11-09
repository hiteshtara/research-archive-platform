# SEC-005 — Parent Award access, and why Fund Center is not a grant type

Status: **implemented in source, not deployed, not enabled.** No migration has
been applied to any shared database, no grant of this type exists anywhere, and
record-level enforcement remains OFF. Two behaviours below are marked **pending
business sign-off** and must not be read as approved.

## 1. Fund Center — investigated, and deliberately not built

The requirement names "Parent Award, IO and Fund Center access". Fund Center
was investigated first, because inventing a mapping for it would have been the
easiest way to build the wrong thing.

**Finding: at BU, Fund Center *is* the Award lead unit.** Verified in BU's
Kuali source (`~/kuali-project/kuali-research` at `17f70777d1c`, 2026-11-04):

```java
// coeus-impl/.../award/sapintegration/SapIntegrationServiceImpl.java:688
grantData.setZZFUNDCENTER(StringUtils.rightPad(award.getLeadUnitNumber(), 10, "0"));
```

Corroborated by `SI_KCRM_PROCESS_OUTBOUND_v9.wsdl:1825` (the `ZZFUND_CENTER`
element) and by `awardTransmissionDetail.tag`, which renders
"Fund Center : `${node.award.leadUnit.unitNumber}`" and "Responsible Fund
Center: `${node.award.leadUnit.unitNumber}`". The term appears in **no other
file** in the Kuali source — it is not part of Kuali's award, unit or hierarchy
model. It exists only on the outbound SAP interface.

**Consequence:** a `FUND_CENTER` grant type would duplicate `UNIT` exactly,
differing only by zero-padding. It has not been built. **Existing UNIT
authorization is the mechanism for Fund Center scope today.**

> **Open question for the SAP/business owners.** The above is BU's *outbound*
> value. If the business means something else by "Fund Center" — an SAP-side
> hierarchy node that can differ from the Award's lead unit — that definition
> lives in SAP and cannot be confirmed from this repository or from Kuali.
> Ask before changing the model. Fund Center enforcement stays **disabled**
> until that answer exists.

## 2. Parent Award — what was built

A `PARENT_AWARD` grant names **one Award number** and reaches that Award plus
its hierarchy descendants, bounded by `app.authorization.parent-award-depth`.

**Default depth is 1** — the named Award and its direct children. Chosen
conservatively: access that is too narrow produces a support request, access
that is too wide produces a disclosure.

### Data model

`archive.award_hierarchy` (V049): `root_award_number`, `award_number`,
`parent_award_number`, `originating_award_number`, `active`.

### V088 changes three things together

Leaving any one out is a silent failure:

1. **`ck_access_grant_type`** — a CHECK over a fixed list. Until `PARENT_AWARD`
   is in it, the grant cannot be inserted at all.
2. **A new `award_number` column** — `unit_number` and `io_value` are the wrong
   columns. Overloading `unit_number` to hold an Award number would make
   `ck_access_grant_shape` unenforceable and leave every later reader guessing
   which meaning applied.
3. **`ux_access_grant_active_scope` is rebuilt**, not added to. V087 keys on
   `(identifier, grant_type, COALESCE(unit_number,''), COALESCE(io_value,''))`.
   Without `award_number` in the key, a grantee's **second** PARENT_AWARD grant
   for a **different** Award collides with the first and is rejected as a
   duplicate — the invariant would silently have meant "one parent-award grant
   per person", which is not what V087 decided.

V088 keeps V087's refusal-first discipline: if unrevoked duplicates exist under
the wider key it raises a named exception rather than failing on an opaque
index error, and it says to revoke rather than delete, because the audit trail
depends on the rows surviving.

### The three traversal guarantees

Expansion happens **once, at scope resolution**, not per record — so
`RecordAccessEvaluator` and `RecordScopeSqlBuilder` cannot disagree about which
Awards a grant reaches. (`WITH RECURSIVE` is statement-level in PostgreSQL and
cannot be rendered into a `WHERE` fragment at all.)

| Guarantee | Enforced by | Proved by |
|---|---|---|
| **Bounded** | `walk.depth < :maxDepth`, plus a clamp to `MAX_SUPPORTED_DEPTH = 10` before the query runs | depth 1 does not reach a grandchild; `Integer.MAX_VALUE` is clamped, not honoured |
| **Cycle-safe** | an explicit `path` array; a child already on the path is not re-entered | a fixture writes a real two-row cycle (`C-A` ↔ `C-B`); the walk terminates |
| **Family-bounded** | `child.root_award_number = walk.root_award_number` on **every** hop | fixture `Q-900` names a parent in family `R-000` but belongs to root `Q-000`, and is never reached |

The family check is the one that matters most. Walking parent links alone does
**not** imply staying in one family: a malformed or re-parented archive row
could point at an Award whose root differs, and following it would hand the
grantee an unrelated Award family.

Soft-deleted rows (`active = 'N'`) are not followed. NULL and unexpected values
are treated as live — a hierarchy edge that merely looks odd should not quietly
change access.

An Award with **no** hierarchy row still reaches itself, so a grant naming it
is never silently worthless.

### Tests

- `AwardHierarchyExpansionIntegrationTest` — 10 tests, Testcontainers, every
  migration applied, against the real schema.
- `ParentAwardAccessTest` — 19 tests: resolution at policy depth, evaluator
  decisions, list SQL, and grant-shape validation mirroring the V088 CHECKs.

## 3. Pending business sign-off

**3.1 — Subaward and Negotiation inheritance.** A PARENT_AWARD grant currently
**does** reach Subawards funded by a reachable Award, and Negotiations
associated with one.

This is a *consequence* of the approved 2026-10-26 rule ("a user may access a
Subaward when they are authorized to view at least ONE of its funding Awards")
rather than a new decision: `subawardRow` reuses the Award predicate verbatim,
and `RecordAuthorizationService` resolves the same question through
`canSeeAward`. Both layers therefore agree, and a test asserts they do.

But it has **not** been separately approved for this grant type, and there is a
precedent for the opposite: design 4.6 specifies that an **IO** grant reaches
no Proposal, Subaward or Negotiation. If the business wants PARENT_AWARD to
behave like IO — Award-only, no travel — that is a small, contained change to
`awardRow` plus the matching evaluator path. **It should be decided explicitly,
not inherited by accident.**

**3.2 — Depth.** Default 1 is an engineering choice made conservatively, not an
approved policy. If BU's Award hierarchies are routinely deeper and a grant is
expected to cover a whole tree, the business should say so and the default
should change deliberately.

## 4. What this does not do

No migration applied anywhere. No grant of this type exists. Enforcement
remains OFF, so none of this is reachable in DEV today. The admin API accepts
the new type and the operator CLI does not yet offer it — adding it there is a
follow-up, deliberately out of scope until 3.1 is answered.
