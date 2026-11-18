# Isolated database for migration, audit and administrator-write testing (proposal)

Status: **proposal only, 2026-11-18.** Nothing has been created. The owner
decided not to create a separate database yet; this document is the requested
proposal. It needs its own approval, and each session needs its own approval.

## 1. Why the Security QA environment cannot do this on shared DEV

The Security QA environment (`RAP_SECURITY_QA_ENVIRONMENT.md`) is safe on shared
DEV only because it never writes. Live on 2026-11-18, every `authz` and `archive`
write counter was unchanged across about 900 requests. The remaining test
categories all write:

| Test | What it writes | Why not on shared DEV |
|---|---|---|
| Administrator writes (link, grant, modify, revoke, suspend, assign or revoke an administrator; privilege and relink approvals) | `authz.*` rows | Persist in the database the shared API reads. Harmless while shared enforcement is OFF, but they would take effect the day it is enabled |
| Second-administrator bootstrap and the independent approval workflow (the prerequisite for Central testing) | `authz.admin_role_member`, `authz.privilege_approval` | The same, and they change who can administer DEV |
| Activity auditing (US-SEC-004 views and downloads) | One audit row per view or download | Fills the shared database with test traffic; retention procedures would act on it |
| Migrations V089/V090 and any later migration | Schema | Change the schema under every DEV user, the nightly Subaward sync and the loaders |

## 2. Options considered

| Option | Real DEV data | Isolation | Cost / effort | Verdict |
|---|---|---|---|---|
| **A. Restore the latest automated snapshot to a temporary instance** | yes (as of the last 05:25Z snapshot) | complete: separate instance, separate secret, no route from shared DEV | db.t4g.micro ≈ $0.02/h + 20 GB gp3 prorated (cents per session); ≈ 15–30 min to restore | **Recommended** |
| B. Separate schema or database on the same RDS instance | partial (needs a copy) | weak: same instance, same superuser, migrations target `archive`/`authz` by name | low | Rejected: a migration rehearsal on the shared instance is exactly the risk |
| C. Testcontainers (already in CI, 50 suites) | no (synthetic fixtures) | complete | free | **Keep for logic.** It already proves write paths; it cannot prove behaviour on DEV-shaped data |
| D. Local Homebrew Postgres (`scripts/run-local.sh`) | stale snapshot (CLAUDE.md) | complete | free | Rejected for evidence: known to lag DEV by weeks |
| E. Clone the instance (Aurora clone) | — | — | — | Not available: DEV is RDS PostgreSQL, not Aurora |

## 3. Recommended design (option A)

```
latest automated snapshot ──restore──► rap-dev-secqa-<date> (db.t4g.micro, same VPC/subnet group, NEW security group)
                                              ▲ 5432 only from the a8w task's security group
private task (-a8w family: enforcement ON + the writes/audit under test) ──┘   reads secret rap/dev/secqa/<date>
operator Mac ──(same IP-restricted ALB rule as the Security QA environment)──► private task
```

**Separation:**
- **Network:** a new security group on the restored instance that admits 5432 only from a new task-level security group used by the `-a8w` task. The shared API's security group gets no rule to the restored instance, and the restored instance gets none to the shared one.
- **Credentials:** a session-only secret `rap/dev/secqa/<date>`, created from the restored instance's master password, which is reset at restore time. It is deleted at teardown. The shared `postgres-api` secret is never reused for the restored instance.
- **Task definition:** a separate family `research-archive-platform-dev-api-a8w`. Only the datasource secret references and the specific write or audit flags under test differ from `-a8:N`, diff-verified before each session.
- **Identity:** Cognito is shared and read-only from the API's side. The synthetic users and links are re-enabled exactly as in the Security QA environment, but links and grants are written **into the restored database**. Shared DEV's `authz` is never touched.

**Session (each one separately approved):**
1. Restore the snapshot. Run a read-only check: schema version, row counts against shared DEV, and `authz` contents.
2. For a migration rehearsal: apply V089/V090 through the loader's `--migrate-only`, pointed at the **restored** secret, then verify the schema.
3. Start the `-a8w` task, then the isolation proof as in the Security QA environment, plus proof that the task's datasource host is the restored endpoint.
4. Run the write, audit or approval tests (matrix plus targeted suites), with evidence captured as before.
5. Teardown: delete the rule, stop the task, delete the restored instance (`--skip-final-snapshot` unless evidence needs one), the session secret and the session security groups. Verify none remain.

## 4. What it would let BU criteria move on

| Criterion | Blocked today by | With option A |
|---|---|---|
| US-SEC-004 #1/#2 (views and downloads logged) | auditing OFF on shared DEV | audit enabled on the restored database only; live view/download rows verified |
| Central scenarios (US-CEN-*) | independent approval workflow not operational; second administrator not seated | rehearse second-admin bootstrap and approval on the restored database |
| US-SEC-002 #3 (revocation removes access) | not exercised live | revoke through the admin API with writes enabled, then confirm access is gone on the next request |
| V089/V090 release readiness | unapplied | a full rehearsal on DEV-shaped data before any shared or production application |

## 5. Risks

| Risk | Control |
|---|---|
| The restored database holds a full copy of DEV archive data | Same classification as DEV; encrypted at rest (snapshot encryption carries over); not publicly accessible; deleted at teardown; life capped at the session |
| The restored instance is accidentally reachable from shared DEV | Its own security group with a single inbound source; verified before use |
| The wrong secret is used, so writes go to shared DEV | `-a8w` taskdef diff check; at start, a check that the datasource host does not equal the shared endpoint; shared `authz` write counters snapshotted before and after |
| Cost overrun from a forgotten instance | Teardown on every exit path; daily `describe-db-instances` check for `rap-dev-secqa-*` |
| Terraform drift | All resources are outside Terraform state and short-lived; DEV plan unchanged after teardown |

## 6. Decisions requested (later, separately)

1. Approve option A in principle.
2. Approve the first session's scope: (a) V089/V090 rehearsal, (b) audit, (c) admin writes and approvals. One at a time is recommended.
3. Approve per session: restore, network and secret creation, and the teardown list above.
