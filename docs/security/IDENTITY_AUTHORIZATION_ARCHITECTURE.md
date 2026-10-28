# Identity and authorization architecture — approved correction

Approved 2026-10-27. Design only; nothing enabled, applied or deployed.
This document is the **authority** on how identity relates to access in
the Research Archive, and it supersedes the status-synchronization
proposals in [`KRIM_LOCAL_IMPORT_PLAN.md`](KRIM_LOCAL_IMPORT_PLAN.md) §4
and [`KRIM_EXTRACTION_DESIGN.md`](KRIM_EXTRACTION_DESIGN.md) §4.

---

## 1. What was wrong with the earlier proposal

The earlier design treated the Research Archive as if it had to stay in
step with Kuali's live authorization state: synchronize `KRIM_PRNCPL_T.ACTV_IND`
on a schedule, deny access when a principal went inactive, and deny
again when the status snapshot aged past a limit.

That is the wrong model, for a reason that is about the product rather
than the code: **the Research Archive is a read-only historical archive
of a retired system. It is not a continuously synchronized Kuali
authorization system, and it has no live relationship with Kuali at
all.** Reconstructing one would have built a permanent operational
dependency on a system that no longer runs, to answer a question — "may
this person read the archive?" — that BU Research Archive administrators
answer directly.

Three concrete harms avoided by the correction:

1. **Mass denial of legitimate users.** 61% of referenced principals are
   already inactive. A departed PI's successor, a records officer, an
   auditor — none would necessarily hold an active KRIM principal, and
   all may have legitimate archive access.
2. **A freshness cliff.** Making snapshot age a denial condition means a
   failed overnight ETL run locks every user out the next morning. An
   archive's availability should not depend on last night's job.
3. **An unnecessary PII refresh treadmill.** Continuous synchronization
   of BU personnel status is a standing data-protection obligation
   acquired for no access-control benefit.

---

## 2. The approved model

### 2.1 Two independent axes, never conflated

| | **Historical identity** | **Current access** |
|---|---|---|
| Question | who *was* this person on this record? | who may read the archive *now*? |
| Source | Kuali KRIM (one-time/periodic historical extract) | `authz` schema, written by administrators |
| Tables | `archive.person`, `authz.kim_principal` | `authz.identity_link`, `access_grant`, `person_status`, `admin_role_member` |
| `ACTV_IND` | recorded as historical fact | **never consulted** |
| Lifecycle | append/upsert, never deleted | granted and revoked by administrators |

**KRIM is the source of historical principal and contact identity data,
and nothing more.** It names the 2003 PI. It does not decide who signs
in today.

### 2.2 The eight approved rules, and where each lives

| # | Rule | Enforced by | Status |
|---|---|---|---|
| 1 | KRIM is the source of historical identity data | ETL extraction | design |
| 2 | Historical `ACTV_IND` must not automatically determine current access | `IdentityResolver` never reads `kim_principal` | ✅ already true — see §2.3 |
| 3 | Inactive principals stay visible in archived records | `archive.person` is display-only and never deleted | ✅ already true |
| 4 | Access = identity link + explicit grant + not suspended + not revoked | `IdentityResolver` + `AccessScopeResolver` | ✅ implemented |
| 5 | ARCHIVE_ADMIN holders manage grants and revocations | `ArchiveAdminService` | service ✅, **no API/UI** |
| 6 | Revocation takes effect on the next authorized request | per-request resolution, no memoisation, no Cognito group claim | ✅ implemented |
| 7 | Shibboleth authentication alone grants no record access | `IdentityResolution.NotProvisioned` | ✅ implemented |
| 8 | ARCHIVE_ADMIN alone grants no data access | `admin_role_member` is a **separate table**, not a `grant_type` | ✅ structural, in `V084` |

Rules 2, 3, 6, 7 and 8 are already satisfied by the code as written —
the correction mainly **stops** work rather than starting it. Rule 8 is
structural rather than conventional: `V084` keeps ARCHIVE_ADMIN out of
`authz.access_grant` precisely so that "does this user hold a grant"
can never accidentally answer yes for an administrator.

### 2.3 The one place `ACTV_IND` appears in code today

`IdentityEnrollmentService` refuses **automatic** enrollment when the
candidate principal is inactive (`REFUSED_INACTIVE_PRINCIPAL`,
[`IdentityEnrollmentService.java:322`](../../api/src/main/java/edu/bu/archive/application/authorization/IdentityEnrollmentService.java)).

This is consistent with the corrected architecture, but only narrowly,
and the distinction is worth stating because it is easy to lose:

- It refuses to **auto-create** a link. It never revokes or denies a
  link an administrator created deliberately.
- Automatic enrollment is **disabled** and stays disabled (§5).
- It is fail-closed: the effect is "an administrator must decide", which
  is exactly the approved model.

**It must not be extended into `IdentityResolver.resolve(...)`.** Doing
so would reintroduce rule 2's violation through the back door. Flagged
as decision D-ID-1 (§6) rather than changed unilaterally: if you prefer
`ACTV_IND` gone from the enrollment path entirely, the deletion is small
and I'll make it.

### 2.4 Fail-closed denial conditions — final list

Five, all implemented, none derived from KRIM status:

| Condition | `IdentityResolution` state |
|---|---|
| authenticated but no link | `NotProvisioned` |
| link revoked | `Revoked` |
| identity suspended | `Suspended` |
| multiple active links, or one identity → several people | `Ambiguous` |
| mapped, but holding no grant | `Mapped` with an empty `AccessScope` — sees nothing |

Every one yields **404, never 403**: the archive never confirms a record
exists to someone not entitled to it.

**Removed from the design:** `PrincipalInactive`, `PrincipalUnknown`,
`StatusSnapshotStale`, and the `max-status-age` property. None was
implemented; all are withdrawn.

---

## 3. KRIM extraction, re-scoped as a data-completeness task

The 660-id finding and the load-ordering gap are **preserved as
historical data-quality issues** — they are real, and they are no longer
on the authorization critical path.

### 3.1 The defect, unchanged

`archive.person` is a point-in-time snapshot loaded **2026-08-03** and
never re-run, while `award_unit_contact` kept loading until
**2026-08-12**. All 660 unresolved ids arrived after the person
snapshot; 0 before it. All 660 are valid KRIM principals (660/660
verified). The extraction SQL is correct — the dependency between
loaders is missing.

Mechanically, in code:
[`reference_data.py:1012`](../../etl/archive_etl/reference_data.py)
states the dependency in its own docstring ("person is loaded last
because it depends on … `award_unit_contact`, loaded independently by
the Award loader"), while
[`load_awards_from_csv.py:11204`](../../etl/load_awards_from_csv.py)
makes the two **mutually exclusive** — `--load-unit-reference-data` is
an early-`return` branch, so a run either loads Awards or refreshes
`person`, never both.

### 3.2 Impact, correctly scoped

| | |
|---|---|
| Authorization impact | **none** — `archive.person` is not an authorization source |
| Real impact | **display**: a contact on an archived Award may render without a name |
| Scale | 5,450 of 5,917 referenced principals absent (**7.89% coverage**) |
| Severity | data completeness, not security |

### 3.3 What to build

A **completeness check**, not a synchronization service — the difference
being that it reports and is run on demand, rather than gating anything:

```sql
WITH driving AS (            -- the ten person_id-bearing columns
    SELECT DISTINCT person_id FROM archive.award_person  WHERE person_id IS NOT NULL
    UNION SELECT DISTINCT person_id FROM archive.proposal_person WHERE person_id IS NOT NULL
    -- … eight more; archive.subaward_contact deliberately excluded …
)
SELECT COUNT(*) AS contacts_without_identity_data
  FROM driving d
  LEFT JOIN archive.person p ON p.person_id = d.person_id
 WHERE p.person_id IS NULL;        -- today: 5,450
```

`archive.subaward_contact` is excluded because it carries `rolodex_id`
and `requisitioner_id`, **not** `person_id` ([`V018`](../../database/migrations/V018__create_subaward_archive_tables.sql)) —
Rolodex entries are not KRIM principals.

Extraction stays **targeted**: the existing
`read_filtered(column="PERSON_ID", values=…)` bind-list path, never a
full KRIM scan. Columns `PRNCPL_NM`, `ENTITY_ID` and `ACTV_IND` are
added as historical fact, with `extracted_at` recording when KRIM said
it.

Running the backfill is a separate approval — it moves ~5,900 BU staff
records into dev RDS (decision D-ID-4, §6). **Not local**: the local
import remains withdrawn.

### 3.4 Proposed tests (ETL, `etl/tests/test_identity_completeness.py`)

| # | Test | Proves |
|---|---|---|
| E1 | `test_new_contact_is_reported_as_missing_identity_data` | the literal 660 scenario is detected |
| E2 | `test_check_is_clean_when_every_contact_resolves` | no false alarms |
| E3 | `test_rolodex_ids_are_never_treated_as_principals` | `subaward_contact` stays out |
| E4 | `test_extraction_is_bind_filtered_not_full_scan` | `read_filtered` used, `read()` never called |
| E5 | `test_inactive_principal_is_retained_not_deleted` | historical identities preserved |
| E6 | `test_completeness_check_does_not_affect_authorization` | the check writes nothing to `authz.*` |

E6 is the one that encodes the architectural correction in a test.

---

## 4. Next priority: ARCHIVE_ADMIN management

Specified separately in
[`ARCHIVE_ADMIN_MANAGEMENT_DESIGN.md`](ARCHIVE_ADMIN_MANAGEMENT_DESIGN.md).

---

## 5. Automatic enrollment stays disabled

Unchanged. It remains blocked on BU IAM confirming an authoritative
`eppn → PRNCPL_ID` mapping. The corrected architecture reduces its
urgency considerably: administrators link identities explicitly, so
automatic enrollment is a convenience, not a prerequisite.

**The deactivation-to-revocation SLA question is withdrawn** — it only
existed to size a synchronization cadence that no longer exists.
Revocation is administrator-driven and takes effect on the next request.

The six open questions in [`KRIM_EXTRACTION_DESIGN.md`](KRIM_EXTRACTION_DESIGN.md) §5
stand, and `eppn → PRNCPL_ID` (question 3) is the one that blocks.
Email must never be treated as an identity key; `PRNCPL_NM` must never
be string-matched against an eppn local part.

---

## 6. Open approval decisions

| # | Decision | Blocks |
|---|---|---|
| **D-ID-1** | Keep or remove the `ACTV_IND` refusal in the *automatic enrollment* path (§2.3). My recommendation: **keep** — it is fail-closed and auto-enrollment is off | nothing today |
| **D-ID-2** | Who are the first ARCHIVE_ADMIN holders, and who runs the bootstrap? | enabling authorization |
| **D-ID-3** | Which BU attribute is the institutional identifier (BU IAM) | enabling authorization |
| **D-ID-4** | Approve the historical KRIM backfill into **dev RDS** (~5,900 staff records) | fixing the display gap |
| **D-ID-5** | Local personnel data | ⛔ withdrawn, unchanged |

Decisions withdrawn by this correction: the deactivation-to-revocation
SLA, the nightly `KRIM_IDENTITY` refresh schedule, and the
`max-status-age` freshness limit.
