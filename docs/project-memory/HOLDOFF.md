# Holdoff — 2026-10-27

Resume point for the Anthony security work. Supersedes the 2026-10-26
holdoff. Every fact verified against live AWS, git and the dev database on
2026-10-27.

> **IRB is out of scope.** No IRB authorization engine. The only IRB work
> done — and the only kind permitted — is isolating it from *shared*
> surfaces.

---

## 1. Current state

| | |
|---|---|
| `origin/main` | **`f94617b`** (merge of PR #71) |
| ECS API | task definition **revision 81**, 1/1 running, 1 deployment — **unchanged** |
| Amplify | job **146** for `f94617b` **SUCCEED**; 145 (`6fb1611`) SUCCEED |
| Terraform (dev) | no outstanding changes |
| Authorization enforcement | **OFF.** No `AUTHORIZATION*` env var on task def 81; property unset, Java default `false`; `application.yml` untouched by any merge |
| Migrations | highest committed **V084**; **none applied**, none in any PR merged today |
| SSM tunnel | stopped — 0 tasks, port 15432 closed |
| Tunnel operator IAM policy | **0 attachments** (roles/users/groups) |

### Merged 2026-10-27

| PR | SHA | What |
|---|---|---|
| **#71** | **`f94617b`** | **Negotiation hybrid authorization + IRB shared-search isolation, off by default** |

### Open PRs

- **#70** — Negotiation data profile and rule options (docs; superseded in
  substance by #71, still open)
- **#68** (draft) — SSM tunnel `TargetNotConnected` retry + agent finding
- #57, #46, #45, #44, #32, #16, #15, #14, #13, #12 — older, unrelated

---

## 2. Authorization status — merged ≠ deployed ≠ enforced

**None of this is running.** The deployed API is revision 81, which predates
all four modules, and enforcement is off in code regardless. Only Shibboleth
auth/logout is live.

| Module | Merged | Deployed | Tested | Enforced |
|---|---|---|---|---|
| Shibboleth auth + logout | yes | **yes** | yes | n/a |
| Phase 1 engine (#56) | yes | no | yes | no |
| Award (#62) | yes | no | 31 tests | no |
| Proposal (#64) | yes | no | 18 tests | no |
| Subaward family (#66) | yes | no | 59 tests | no |
| **Negotiation hybrid (#71)** | **yes** | **no** | **32 tests** | **no** |
| ARCHIVE_ADMIN | schema + service only | no | yes | no |
| QA Status dashboard | yes | yes (Amplify) | yes | n/a |

Totals on `f94617b`: **API 1417 · PostgreSQL integration 272 · UI 694**, all
green, all three CI checks passing.

---

## 3. Approved rules now in code

- **D6a — Subaward**: family-level ANY-match funding inheritance.
- **D7** — restricted funding Awards omitted entirely; no counts, flags or
  placeholders. Funding heading is the constant "Associated Awards".
- **D8 — Negotiation**: HYBRID — recorded lead unit **OR** explicitly
  `Award`-typed association **OR** explicitly `Institutional Proposal`-typed
  association. Alternatives; any one suffices.
- **D8 — RESTRICTED attachment flag**: informational, **never** an access
  control. Downloads require authorized record access **and**
  `ArchiveAttachmentViewer`. **20,406 of 28,923 (70.55%)** Negotiation
  attachments carry `Y` — gating on it would remove seven in ten.
- **IRB shared-search isolation**: when enforcement is ON, IRB leaves shared
  Global Search for **all** users **including Central** — keyed on the
  enforcement flag, not on the caller. Keyword leg never runs; semantic rows
  dropped. Enforcement OFF is unchanged.

### Real-data findings (dev, read-only)

**Subaward**: 93,735 version rows · 3,511 families · 91,945 structurally
reachable (98.09%) · 270 families unresolved (224 broken refs, 46 none) · 8
inconsistent denormalized codes · **0 SQL/Java disagreements**.

**Negotiation**: 10,775 rows, flat (no versions) · lead unit covers 10,718
(99.47%), matches `archive.unit` 100% · Award-typed 2,223, Proposal-typed 3
· **10,721 reachable (99.50%)**, 54 Central-only · **0 SQL/Java
disagreements across all 10,775 rows** · predicate 19.9 ms, no new index.

> **Structural reachability is NOT permission.** These are ceilings the data
> permits, not any user's access. A user with one department grant sees a
> small fraction.

### Why both rules changed shape after profiling

Per-version Subaward reached **4.06%** of version rows; family-level reaches
98.09%. Negotiation inheritance alone reached **20.7%**; hybrid reaches
99.50%. Neither would have been caught by fixtures. **Profile real data
before designing a rule.**

### The Negotiation collision trap

`associated_document_id` is free TEXT, populated on every row including all
8,533 typed `None`. Ungated it collides with an Award workflow document
number **7,246** times and a `proposal_number` **6,980** times; **6,073 of
10,775** ids match more than one record kind. Both decision paths therefore
resolve associations **only** inside an association-type gate, and `'Award'`
resolves against `award_number`, never `workflow_document_number`. Three
regression tests, each verified to fail with the gate removed.

---

## 4. SSM database tunnel

PR #67 merged and applied. `scripts/start-db-tunnel.sh` opens
**localhost:15432** → dev RDS; Ctrl-C closes and stops the task.
`scripts/stop-db-tunnel.sh` sweeps strays. Connect as **`archive_analyst`**
(read-only at the database level), `sslmode=require`. Credential via
`scripts/mac-show-analyst-password.sh` — clipboard, never printed. **No
credential appears in this document.**

> **Port 15432, never 5432.** 5432 is local Homebrew PostgreSQL — a separate
> copy, last seen at migration V080 against the repo's V084.

- **PR #68 open**: retries `TargetNotConnected` (the exec agent reports
  RUNNING before SSM registers the channel). Worth merging.
- **SSM Agent patch status INDETERMINATE**: `--version` reports `3.3.0.0`,
  below the `3.3.4851.0` that fixes CVE-2026-89049, but the binaries are
  dated 2026-10-13, over a month after the fix — so the string is likely a
  placeholder. Containment is what the design rests on: the task role holds
  only four `ssmmessages` actions.
- Operator policy deliberately **unattached**; `Shibboleth-InfraMgt` not
  broadened.

---

## 5. Next security work, in order

1. **Investigate the 270 unresolved Subaward families.** First question:
   does `subaward_funding.award_number` resolve where `award_id` does not?
   That separates an ETL gap from genuine orphans. Do **not** reach for a
   fallback rule — `requisitioner_unit` matches `archive.unit` 100% and is
   still not approved for authorization.
2. **Close the Proposal Dashboard count-scoping gap** (D2). `proposals`,
   `proposal_history_records` and the `PROPOSAL` document tally are still
   archive-wide while Proposal endpoints are scoped. **Blocker before
   enforcement.**
3. **Review and merge PR #68.**
4. **ARCHIVE_ADMIN management interface** — still schema + service only; no
   administrative UI exists.
5. **Keep /qa-status aligned** with actual deployment state.
6. Decide the **16 Subaward-typed Negotiations** (currently lead-unit only)
   and whether the **54 no-basis Negotiations** staying Central-only is
   acceptable long-term.

**Do not start IRB work** beyond shared-surface isolation.

---

## 6. Outstanding security decisions

| # | Question | Owner | Blocks |
|---|---|---|---|
| 1 | **SEC-008 audit scope** — what is audited, retained how long, reviewed by whom. Must not be marked complete | Anthony / BU security | Enforcement sign-off |
| 2 | **BU identity → KRIM mapping** — how a Cognito/Shibboleth identity resolves to a KRIM person id, and behaviour when ambiguous, revoked or unlinked. Fails closed today | Anthony / BU IAM | Enabling enforcement anywhere |
| 3 | **Proposal Dashboard counts unscoped** (D2) | engineering | Enabling enforcement |
| 4 | **270 Subaward families** Central-only — acceptable, or fix the data? | Anthony / BU | Subaward enforcement |
| 5 | **Tunnel operator IAM** — no project-specific human role exists; only shared BU SSO roles. Policy stays unattached | BU IAM / Anthony | Tunnel use by others |
| 6 | **SSM Agent patch status** indeterminate | engineering / AWS | Tunnel risk judgement |
| 7 | **Explicit Subaward grant type** — D6 mentions one for the no-funding case; none exists in the `authz` schema | Anthony / BU | The 46 unfunded families |
| 8 | **16 Subaward-typed Negotiations** — no reliable association key | Anthony / BU | Completeness |

---

## 7. Lessons worth not relearning

- **Profile real data before designing an authorization rule.** Two rules
  were redesigned after profiling; neither failure mode was visible in
  fixtures.
- **`mvn -q test-compile` can report BUILD SUCCESS against stale test
  classes.** After a constructor signature change it hid two broken call
  sites; only `rm -rf target/classes target/test-classes` surfaced them.
  Incremental test-compile is not proof that test sources compile.
- **A bare `git push` goes to the `bu` remote** unless `remote.pushDefault`
  is unset (it now is). Prefer `git push origin <branch>`.
- **Verify a denial test actually fails without the fix.** Every regression
  test added for a leak this week was checked that way.

---

## Hard safety rules still in force

- Do not enable authorization enforcement anywhere.
- Do not deploy a new ECS API image.
- Do not apply database migrations.
- Do not grant permissions or bootstrap administrators.
- Do not modify Cognito, Shibboleth, Terraform, production, RDS data or DNS.
- Do not leave the SSM tunnel running.
