# Holdoff — end of day, 2026-10-26

Resume point for the Anthony security work. Every fact below was **verified
against live AWS, git and the dev database on 2026-10-26**, not carried over
from conversation notes.

> **IRB is out of scope.** It is not in tomorrow's roadmap and no IRB work
> should be started.

---

## 1. Current state

| | |
|---|---|
| `origin/main` | **`3559fd5`** (merge of PR #66) |
| ECS API | task definition **revision 81**, 1/1 running, 1 deployment — **unchanged all day** |
| Amplify | app `d288p9gmoteftb`, job **144** for `3559fd5` **SUCCEED**; 143 (`7d6e982`) and 142 (`bdf7c36`) also SUCCEED |
| Terraform (dev) | **no outstanding changes** — `terraform plan` reports "No changes" with `manage_db_tunnel_access=true` |
| Authorization enforcement | **OFF.** No `AUTHORIZATION*` env var on task definition 81; `app.authorization.enforcement-enabled` unset, Java default `false` |
| Migrations | highest committed is **V084**. **None applied today.** No migration in any PR merged today |
| Main checkout branch | `qa/version-table-and-hierarchy-link-semantics` (not main) — untracked scratch only |

### Merged today

| PR | SHA | What |
|---|---|---|
| #62 | `b77f03f` | Award record authorization (Phase 2A), off by default |
| #63 | `98d201c` | QA status: nine SEC requirements, seven states |
| #64 | `f1b8115` | Proposal record authorization, off by default |
| #65 | `bdf7c36` | QA status after the authorization merges |
| #67 | `7d6e982` | SSM database tunnel (ephemeral Fargate, no bastion) |
| **#66** | **`3559fd5`** | **Subaward family-level authorization, off by default** |

### Open PRs

- **#68** (draft) `fix/ssm-tunnel-target-not-connected` — tunnel retry fix + SSM agent finding. **Not merged.**
- #57, #46, #45, #44, #32, #16, #15, #14, #13, #12 — older, unrelated to today.

### Worktrees

`rap-sub2` (#66, merged), `rap-tunnel` (#68, open), `rap-holdoff` (this doc).
Many older worktrees exist; `git worktree list` is authoritative.

---

## 2. Anthony security — what is actually true

**Merged ≠ deployed ≠ enforced.** Everything below is merged to main and
**none of it is running**: the deployed API is revision 81, which predates
all of it, and enforcement is off in code regardless.

| Work | Merged | In the deployed API | Tested | Enforcement on |
|---|---|---|---|---|
| BU Shibboleth auth + logout | yes | **yes** — this one *is* live | yes, browser-verified | n/a |
| Phase 1 authorization engine | yes (PR #56) | no | yes | no |
| Award authorization | yes (#62) | **no** | yes — 31 integration tests | **no** |
| Proposal authorization | yes (#64) | **no** | yes — 18 integration tests | **no** |
| Subaward family authorization | yes (#66) | **no** | yes — 59 integration tests | **no** |
| ARCHIVE_ADMIN foundation | partial — schema + service exist | no | yes | no |
| QA Status dashboard | yes (#63, #65) | yes (Amplify) | UI unit tests | n/a |

Test totals on `3559fd5`: **API unit 1380**, **PostgreSQL integration 240**,
**UI 694** — all passing, all three CI checks green.

Enforcement is off three independent ways: the property is unset (Java
default `false`), each service short-circuits when unenforced, and every
port defaults to `PERMIT_ALL`. A startup guard refuses to boot if
enforcement is ever enabled while a permissive fallback is wired.

---

## 3. Subaward findings — verified real data

Profiled against dev RDS over the SSM tunnel, read-only, 2026-10-26.

| Measure | Value |
|---|---|
| Subaward version rows | **93,735** |
| Subaward families (`subaward_code`) | **3,511** |
| Versions **structurally reachable** under family inheritance | **91,945** (98.09%) |
| Families with no resolvable funding relationship | **270** (7.69%) |
| ├ only broken funding references | **224** |
| └ no funding references at all | **46** |
| Inconsistent denormalized funding-family codes | **8** |
| SQL vs Java authorization disagreements | **0** (compared across all 93,735 rows) |

### Structural reachability is NOT permission

**91,945 does not mean 91,945 rows any user can see.** It means those
versions belong to a family where *at least one funding Award resolves to a
real archived Award*. Whether a given user may open them still depends
entirely on whether **that user** holds a grant on **that Award** — by
department unit, descendant unit, or Research Staff role. A user with one
department grant sees a small fraction of the 91,945. The figure measures
the *ceiling* the data permits, not anyone's actual access.

The 270 unreachable families are the inverse: no user except Central can
ever reach them, because nothing in the family resolves to an Award at all.

### Approved rules

- **D6a — family-level ANY-match** (supersedes D6's per-version form):
  a user may view **all versions of a Subaward family** when authorized for
  at least one valid funding Award linked to **any version** of that family.
  Per version it was 4.06% reachable; the archive's historical record would
  have been invisible.
- **D7 — restricted funding Awards leave no trace.** Only authorized funding
  Awards are returned. No counts, no flags, no placeholders, no existence
  indicators. The Funding heading is the constant `"Associated Awards"` and
  `resolveAssociatedAwardsSectionLabel()` takes **no arguments** so there is
  nothing to count. **Do not reintroduce a count.**

### Two data caveats that shaped the code

1. `(subaward_code, sequence_number)` is **not unique** — 2,577 pairs repeat,
   up to 5 deep. Family key only; `subaward_id` is the version key.
2. `subaward_funding.subaward_code` disagrees with its parent for **8 rows**
   (3046 vs 1970, 3203 vs 4094). The family is derived by joining
   `archive.subaward` on `subaward_id`; reading the denormalized column would
   grant the **wrong** family.

Performance: the family-wide `EXISTS` runs in **6.4 ms** over all 93,735 rows
on the existing `ix_subaward_code_sequence`. **No migration needed.**

**PR #66 status: MERGED, `3559fd5`.** Not deployed, not enforced.

---

## 4. SSM database tunnel

**PR #67 merged (`7d6e982`) and applied to dev.** Nine resources: a
single-AZ `ssmmessages` interface endpoint, a dedicated ECS cluster, a
minimal task role (four `ssmmessages` actions only), a zero-ingress security
group, one SG-to-SG RDS ingress rule, a task definition, a log group and the
operator IAM policy.

```bash
scripts/start-db-tunnel.sh     # opens localhost:15432; Ctrl-C closes and stops the task
scripts/stop-db-tunnel.sh      # sweeps any stray task
```

Connect on **port 15432** as **`archive_analyst`** (read-only at the database
level), database `research_archive`, `sslmode=require`. Credential comes from
`scripts/mac-show-analyst-password.sh`, which copies to the clipboard and
never prints. **No credential is recorded in this document, and none should
be.**

> **Port 15432, never 5432.** 5432 is local Homebrew PostgreSQL — a separate,
> stale copy. Keep the existing DBeaver connection pointed there; use a
> **duplicate** for the tunnel.

### State at end of day

- **Tunnel tasks running: 0.** Nothing is left running overnight. Verified.
- **Operator policy attachments: 0 roles, 0 users, 0 groups** — created but
  deliberately unattached.
- `Shibboleth-InfraMgt` was **not** broadened.

### Open items

- **PR #68 is open and unmerged** — retries `TargetNotConnected` (the exec
  agent reports RUNNING before SSM registers the channel; the first live run
  failed on a good tunnel), plus the live verification record.
- **SSM Agent patch status is INDETERMINATE.** `--version` reports `3.3.0.0`,
  below the `3.3.4851.0` that fixes CVE-2026-89049; but the binaries are
  dated **2026-10-13**, over a month after the 2026-09-10 fix, so the string
  is probably a placeholder in the Fargate build. No VERSION file, no log
  banner. An earlier note called this "confirmed unpatched" — **that was an
  overstatement and is corrected.** A definitive answer needs AWS Support or
  a Fargate platform release note.
  Containment, which is what the design rests on: the task role is worthless
  if stolen, and `ssm:StartSession` is scoped to one document and one cluster
  (AWS's own published workaround).

---

## 5. Tomorrow's priorities, in order

1. **PR #66** — merged as `3559fd5`; confirm nothing further is outstanding.
2. **Negotiation authorization investigation** — read-only real-database
   queries first, same discipline that caught the Subaward 4.06% problem.
3. **Profile associated vs unassociated Negotiations.**
4. **Propose Negotiation rules and get them approved *before* implementing.**
5. **Investigate the 270 unresolved Subaward families** (224 broken refs, 46
   none) — see the plan below.
6. **Close the Proposal Dashboard count-scoping gap.**
7. **Continue planning the ARCHIVE_ADMIN management interface.**
8. **Keep /qa-status aligned with actual deployment status.**

**Do not start IRB work.**

### Plan for the 270 unresolved Subaward families

Investigation, not a code change. The question is whether 759 dangling
`award_id` references are an ETL gap or genuine historical orphans.

1. Sample the 224 all-broken families; check whether their `award_id` values
   exist in Oracle staging but not in `archive.award_version` (ETL gap) or
   nowhere at all (orphans).
2. Check whether `subaward_funding.award_number` resolves where `award_id`
   does not — V067 backfills that column, and the ETL may simply not have
   re-run.
3. If it is an ETL gap, the fix is a loader re-run, not an authorization
   change. If they are genuine orphans, the business decision is whether
   Central-only access is acceptable for 7.69% of families.
4. Do **not** invent a fallback rule. `requisitioner_unit` matches
   `archive.unit` 100% and is still not approved for authorization.

---

## 6. Outstanding security decisions

| # | Question | Owner | Blocks |
|---|---|---|---|
| 1 | **Negotiation authorization rules** — no approved rule exists. Cross-module document/attachment search is fail-closed for scoped callers until one does | Anthony / BU | Negotiation authorization; full enforcement |
| 2 | **SEC-008 audit scope** — what must be audited, retained how long, reviewed by whom. Not fully resolved; must not be marked complete | Anthony / BU security | Enforcement sign-off |
| 3 | **BU identity → KRIM mapping** — how a Cognito/Shibboleth identity resolves to a KRIM person id, and what happens when it is ambiguous, revoked or unlinked. Engine fails closed today | Anthony / BU IAM | Enabling enforcement anywhere |
| 4 | **Proposal Dashboard counts unscoped** — `proposals`, `proposal_history_records` and the `PROPOSAL` document tally are archive-wide even though Proposal endpoints are scoped. **Recorded as an outstanding blocker before enforcement** (D2) | engineering | Enabling enforcement |
| 5 | **270 Subaward families Central-only** — acceptable, or fix the data? | Anthony / BU | Subaward enforcement |
| 6 | **Tunnel operator IAM** — no project-specific human role exists; the only candidates are shared BU SSO roles. Policy stays unattached pending this | BU IAM / Anthony | Tunnel use by anyone else |
| 7 | **SSM Agent patch status** — indeterminate; needs AWS Support or a platform release note | engineering / AWS | Normal tunnel use judgement |
| 8 | **Explicit Subaward grant type** — D6 mentions one for the no-funding case; none exists in the `authz` schema | Anthony / BU | The 46 unfunded families |

---

## Hard safety rules still in force

- Do not enable authorization enforcement anywhere.
- Do not deploy a new ECS API image.
- Do not apply database migrations.
- Do not grant permissions or bootstrap administrators.
- Do not modify Cognito, Shibboleth, production, RDS data or DNS.
- Do not leave the SSM tunnel running.
