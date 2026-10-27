# KRIM identity import — gap explanation, extraction design, local plan

Investigation and planning, 2026-10-27. **No personnel data has been
transferred.** Read-only throughout; the only Oracle queries run were
aggregate `COUNT(*)`s against **staging**, returning numbers and nothing
else. No names, emails or identifiers appear here or were written to disk.

---

## 1. Connectivity verified — three distinct databases

| | Verified |
|---|---|
| **Kuali Oracle (source)** | `stg.db.kualitest.research.bu.edu`, DB `KUALI`, schema `KCOEUS`, session user `KCOEUS`, **Mode: READ ONLY** — reported by the runner itself. **STAGING, not production.** |
| | Runner `~/projects/bu-huron-data-exchange/scripts/kc_staging_query.py`, host hard-coded, password from macOS Keychain, **only SELECT/WITH accepted** |
| | BU VPN up (`utun0–utun6`) — required, and confirmed working by a live query |
| **Local PostgreSQL (proposed target)** | `127.0.0.1:5432`, Homebrew `postgresql@17`, PID 4293, database `research_archive`, superuser is the OS account |
| **Dev RDS (NOT the target)** | reachable only via the SSM tunnel on `127.0.0.1:**15432**`, currently **closed** |

**5432 and 15432 are different databases and were treated as such
throughout.** Everything in §2 was measured over 15432 (dev RDS); the local
database on 5432 was only inspected for its schema version.

### Schema compatibility

| | |
|---|---|
| local `research_archive` migration level | **V080** |
| repository | **V084** |
| gap | **V082, V083, V084** — there is no V081; **all three are the authorization migrations** |

So the local database **cannot host `authz.*` staging tables today**. It is
three migrations behind, and the missing ones are precisely the identity and
grant schema.

---

## 2. Priority 1 — the 660 unresolved ids, explained

The answer is none of the assumed failure modes.

### Step 1: are they KRIM principals?

Tested all 660 against Oracle staging `KRIM_PRNCPL_T`:

| | |
|---|---|
| present in `KRIM_PRNCPL_T` | **660 / 660 — 100%** |
| with a default name (`DFLT_IND='Y'`) | **660** |
| with a default email | **659** |
| Rolodex or other non-principal identities | **0** |
| absent from KRIM | **0** |

So the existing extraction query **would have returned all 660**. Its
`LEFT JOIN`s on name and email would have matched too.

### Step 2: by source table

| Source | driving ids | resolved | unresolved |
|---|---|---|---|
| `unit_administrator` | 128 | **128 (100%)** | 0 |
| `award_unit_contact` | 1,101 | 441 | **660** |

Both resolved and unresolved ids share the same shape — 9 characters, not
all digits — so nothing structural distinguishes them.

### Step 3: the cause — a stale point-in-time load

| Table | first loaded | last loaded | rows |
|---|---|---|---|
| `archive.person` | **2026-08-03** | **2026-08-03** | 467 |
| `archive.award_unit_contact` | 2026-08-01 | **2026-08-12** | 365,378 |
| `archive.unit_administrator` | 2026-08-03 | 2026-08-03 | 998 |

> **All 660 unresolved contacts were loaded AFTER the person load: 660
> after, 0 before.**

`archive.person` is a **one-off snapshot taken on 2026-08-03 and never
re-run**, while `award_unit_contact` kept loading until 2026-08-12. Every
missing id is a contact that arrived after the person snapshot. That is
also why `unit_administrator` is at 100% — it was loaded the same day and
has not changed since.

**Reconciliation: the source and the archive do reconcile.** The extraction
is correct and complete for the set it was given. The defect is that
`load_person_reference_data` has **no dependency on, or refresh triggered
by, its driving tables** — nothing re-runs it when contacts grow.

### ⚠️ Step 4: an unplanned finding — most of these principals are INACTIVE

| Set | active | inactive | % inactive |
|---|---|---|---|
| already in `archive.person` (467) | 159 | **308** | **66%** |
| unresolved (660) | 283 | **377** | 57% |
| **whole driving set (1,127)** | **442** | **685** | **61%** |

**`archive.person` already holds 308 principals who are inactive in KRIM,
and the table has no status column at all.** If identity links were created
from it today, two-thirds would point at deactivated principals and nothing
in the archive would say so. This is the strongest available argument for
Priority 3, and it is measured rather than hypothesised.

---

## 3. Priority 2 — revised extraction design

This extends [`KRIM_EXTRACTION_DESIGN.md`](KRIM_EXTRACTION_DESIGN.md)
rather than replacing it. That document holds the precedent analysis (§1),
the per-source counts (§2), the `05_person.sql` column diff (§3b) and the
resolver gap analysis (§4). Repeated below only what the Priority 1
evidence actually changes.

### Columns (minimum necessary)

| Table | Columns | Why |
|---|---|---|
| `KRIM_PRNCPL_T` | `PRNCPL_ID`, `PRNCPL_NM`, `ENTITY_ID`, `ACTV_IND` | identity, candidate eppn counterpart, person grouping, **status** |
| `KRIM_ENTITY_NM_T` | `FIRST_NM`, `MIDDLE_NM`, `LAST_NM` (`DFLT_IND='Y'`) | already extracted |
| `KRIM_ENTITY_EMAIL_T` | `EMAIL_ADDR` (`DFLT_IND='Y'`) | already extracted; **never an identity key** |
| `KRIM_ROLE_T` | `ROLE_ID`, `ROLE_NM`, `NMSPC_CD`, `ACTV_IND` | role names for Central/department mapping |
| `KRIM_ROLE_MBR_T` | `ROLE_MBR_ID`, `ROLE_ID`, `MBR_ID`, `MBR_TYP_CD`, `ACTV_FRM_DT`, `ACTV_TO_DT` | memberships, **date-bounded** |
| `KRIM_ROLE_MBR_ATTR_DATA_T` | `ROLE_MBR_ID`, `KIM_ATTR_DEFN_ID`, `ATTR_VAL` | the qualifiers — `unitNumber`, `subunits` |
| `KRIM_ATTR_DEFN_T` | `KIM_ATTR_DEFN_ID`, `NM` | qualifier names |
| `KRIM_GRP_T` | `GRP_ID`, `GRP_NM`, `NMSPC_CD`, `ACTV_IND` | group names |
| `KRIM_GRP_MBR_T` | `GRP_ID`, `MBR_ID`, `MBR_TYP_CD`, `ACTV_FRM_DT`, `ACTV_TO_DT` | **roles nest; resolution is recursive** |

`ACTV_FRM_DT`/`ACTV_TO_DT` must be carried, not flattened: a membership can
be expired without being deleted.

### Driving set — 10 columns, bind-variable, never a full scan

```
award_person.person_id · proposal_person.person_id
award_unit_contact.person_id · proposal_unit_contact.person_id
unit_administrator.person_id · award_budget_person.person_id
negotiation.negotiator_person_id
negotiation_search_attribute.principal_investigator_person_id
negotiation_unassociated_detail.pi_person_id
negotiation_unassociated_detail.contact_admin_person_id
```

**Excluded, deliberately:** `subaward_contact` — it carries `rolodex_id`
and `requisitioner_id`, **not** `person_id`. Subaward contacts are Rolodex
entries, not KRIM principals, and conflating the two id spaces is exactly
the distinction Priority 2 asks to preserve. `protocol_*` excluded: **IRB,
out of scope**.

`OracleDataSource.read_filtered(column="PERSON_ID", values=…)` already
chunks bind lists; 5,917 values is bounded and targeted.

### Expected coverage

| | |
|---|---|
| driving ids (grand union) | **5,917** |
| expected to resolve in `KRIM_PRNCPL_T` | **~100%** — the 660 sample resolved at 100%, and `unit_administrator` at 100% |
| expected inactive | **~60%**, extrapolating the measured 61% |

The 100% hit rate is now evidence-based rather than assumed, which was the
open question blocking this estimate.

---

## 4. Priority 3 — inactive-principal enforcement

### Design

1. **Load status, don't infer it.** `authz.kim_principal.actv_ind` already
   exists in V083 and is loaded by nothing. Populate it from the extraction
   above, with `loaded_at` and `load_ref` per row.
2. **Check it in `IdentityResolver`.** After resolving a link, require
   `kim_principal.actv_ind = 'Y'` for the `prncpl_id`; otherwise deny. Add
   an `IdentityResolution.Inactive` state alongside `Revoked`/`Suspended`.
3. **Fail closed on unknown, not just on inactive.** If there is **no**
   `kim_principal` row for the principal, deny. A principal the archive has
   never seen must not be treated as active — this is the case the 660
   would have fallen into.
4. **Staleness is itself a denial condition.** The snapshot has a
   `loaded_at`; if it is older than a configured maximum age, deny rather
   than trust it. Without this, a snapshot frozen on 2026-08-03 would
   authorize deactivated staff indefinitely — which is precisely the state
   `archive.person` is in today.

### How status arrives, and how fast deactivation takes effect

**It arrives by ETL refresh, never by a live Kuali call** — a live call in
the request path was ruled out, and Kuali is not an API dependency of the
archive.

So deactivation latency = the refresh interval. That is a **business
decision**, not an engineering one, and the honest framing is:

| Refresh cadence | Worst-case window in which a departed user retains access |
|---|---|
| Nightly | up to 24h |
| Hourly | up to 1h |
| On demand only | **unbounded** — today's situation |

The existing Subaward nightly sync shows a nightly ECS schedule is already
an established pattern here. **A manual revocation path must exist
regardless**, because no cadence covers an urgent departure: revoking the
`identity_link` takes effect on the very next request, since resolution is
per request and nothing is memoised.

> **A historical KRIM snapshot is not evidence of current employment.** It
> is evidence of status *as at `loaded_at`*. The staleness check in (4) is
> what keeps that distinction honest in code.

---

## 5. Priority 5 — local import plan, and the approval gate

### ⛔ Not executed, and I recommend against it as specified

The plan is below, but transferring real BU personnel data to this laptop
should be approved explicitly first, and I do not think it should proceed
as scoped:

1. **Approval is required and has not been given.** The brief says "Before
   transferring personnel data, confirm the local destination and handling
   are approved." It is not — this is a developer laptop, the Postgres
   instance has no encryption at rest, trust-based local auth, no audit
   logging, and no retention control. The data would be ~5,900 BU staff
   names, emails, principal ids and role memberships.
2. **The local database cannot host the tables anyway** — it is at V080 and
   the `authz` schema arrives in V082–V084.
3. **Real personnel data is not needed to build any of this.** Every
   component is testable with synthetic fixtures on Testcontainers, which
   is how all four authorization modules were built and verified. The real
   data questions that *do* matter were answerable, and have been answered,
   with aggregate counts over the approved read-only connections.

**Recommendation:** build and validate against synthetic data; keep real
KRIM identity data in dev RDS, behind the VPC, loaded by the ETL that
already runs there — not on a laptop. If local real data is genuinely
needed later, that is a BU data-handling decision, not a technical one.

### If approved, the plan

1. Apply V082, V083, V084 locally (local only; dev RDS untouched).
2. Create a **staging schema** `krim_stage`, never writing
   `archive.person` or `authz.*`:
   ```
   krim_stage.principal(prncpl_id PK, prncpl_nm, entity_id, actv_ind,
                        extracted_at, source_ref)
   krim_stage.role, krim_stage.role_member, krim_stage.role_member_attr,
   krim_stage.group, krim_stage.group_member, krim_stage.attr_defn
   ```
   Every table carries `extracted_at` and `source_ref`.
3. Import idempotently — upsert on primary key, mirroring
   `_upsert_rows` in `reference_data.py`; re-running changes nothing.
4. **Reconcile, never overwrite.** Compare `krim_stage.principal` with
   `archive.person` and **report** differences. No write to
   `archive.person`, no row in `authz.identity_link`, no grant.
5. Credentials stay in Keychain; no identifier is logged — counts only.

### Repeatable commands (for review; not run)

```bash
# 1. local schema (LOCAL ONLY — 5432, never 15432)
psql -h 127.0.0.1 -p 5432 -d research_archive -f database/migrations/V082__*.sql   # then V083, V084
psql -h 127.0.0.1 -p 5432 -d research_archive -f database/staging/krim_stage.sql  # NEW FILE — does not exist yet

# 2. extract (read-only staging Oracle, aggregate verification first)
python3 ~/projects/bu-huron-data-exchange/scripts/kc_staging_query.py \
        --file sql/extract/reference/05_person.sql --output /secure/tmp/krim.csv

# 3. import + reconcile (writes krim_stage only)
uv run python -m archive_etl --import-krim-stage --reconcile-only   # NEW FLAGS — neither exists yet
```

Step 2 is the one requiring approval: it is the first command that moves
personnel data.

---

## 6. Priority 4 — BU IAM mapping stays open

Unchanged and still blocking automatic enrollment:

1. What stable institutional identifier is available from BU Shibboleth?
2. Is `eppn` stable across username and email changes, and never reassigned?
3. **Is there an authoritative `eppn` → `PRNCPL_ID` source?**
4. How are inactive, renamed and merged identities represented — can one
   person hold several `PRNCPL_ID`s under one `ENTITY_ID`?
5. Can the identifier be released to our Cognito service provider?
6. What is the approved verification process for ambiguous cases?

**New, from this investigation:** given 61% of referenced principals are
inactive, **(7) what is BU's expected deactivation-to-revocation SLA for a
read-only archive?** That number sets the ETL cadence.

`PRNCPL_NM` must still **not** be string-matched against the eppn local
part, and email must **not** be treated as an identity key. Automatic
enrollment stays disabled until (3) is answered.
