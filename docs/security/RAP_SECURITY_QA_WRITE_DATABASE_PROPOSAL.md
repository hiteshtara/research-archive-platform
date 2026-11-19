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

## 7. Implementation and cost plan (for review, 2026-11-18)

Not approved and not executed. Facts read 2026-11-18:
- **Source:** `research-archive-platform-dev-postgres`, db.t4g.micro, PostgreSQL 17.9, 20 GB gp3, encrypted, not public.
- **Snapshots:** daily automated at about 05:25Z, kept 7 days.
- **Network:** subnet group `research-archive-platform-dev-db-subnet-group`; security group `sg-0ded3637b04384f60`, which is NOT reused.

### 7.1 Resources per session (all deleted at teardown)

| # | Resource | Name |
|---|---|---|
| 1 | RDS instance restored from the latest automated snapshot | `rap-dev-secqa-<yyyymmdd>` (db.t4g.micro, single-AZ, same subnet group, `--no-publicly-accessible`, deletion protection off, backup retention 0) |
| 2 | Security group on the instance: inbound 5432 only from #3 | `rap-dev-secqa-db-<yyyymmdd>` |
| 3 | Security group for the `-a8w` task; egress to #2 and HTTPS | `rap-dev-secqa-task-<yyyymmdd>` |
| 4 | Secret with the restored instance's credentials (master password reset after restore) | `rap/dev/secqa/<yyyymmdd>` |
| 5 | Task definition `research-archive-platform-dev-api-a8w:N` = `-a8:N` with the datasource secrets pointing at #4 and only the flags under test changed | kept inert like `-a8`, or deregistered |
| 6 | TG, task and listener rule | as the Security QA environment |

### 7.2 Commands (outline; full scripted versions are reviewed before any session)

```
aws rds restore-db-instance-from-db-snapshot --db-instance-identifier rap-dev-secqa-<d> \
  --db-snapshot-identifier <latest rds:research-archive-platform-dev-postgres-...> \
  --db-instance-class db.t4g.micro --db-subnet-group-name research-archive-platform-dev-db-subnet-group \
  --vpc-security-group-ids <#2> --no-publicly-accessible --no-multi-az --tags Key=purpose,Value=secqa-temporary
aws rds wait db-instance-available --db-instance-identifier rap-dev-secqa-<d>
aws rds modify-db-instance --db-instance-identifier rap-dev-secqa-<d> --master-user-password <generated> \
  --backup-retention-period 0 --apply-immediately          # password goes straight into secret #4, never printed
# ... session ...
aws rds delete-db-instance --db-instance-identifier rap-dev-secqa-<d> --skip-final-snapshot --delete-automated-backups
aws secretsmanager delete-secret --secret-id rap/dev/secqa/<d> --force-delete-without-recovery   # owner's choice
aws ec2 delete-security-group --group-id <#3>; aws ec2 delete-security-group --group-id <#2>
```

**Pre-use guards:**
- the restored endpoint differs from the shared endpoint;
- the `-a8w` task's resolved datasource host equals the restored endpoint;
- a read-only snapshot of shared DEV `authz` write counters before and after the session must be unchanged.

### 7.3 Cost (approximate us-east-1 on-demand list prices; verify before approval)

| Item | Rate (approx.) | 3 h session | Notes |
|---|---|---|---|
| db.t4g.micro single-AZ | ≈ $0.016/h | ≈ $0.05 | billed per second while the instance exists, including the 15–30 min restore |
| 20 GB gp3 storage | ≈ $0.115/GB-month | ≈ $0.01 | prorated hourly |
| Snapshot restore | no charge | $0 | |
| Session secret | ≈ $0.40/month | ≈ $0.01 | deleted at teardown |
| Fargate task (0.5 vCPU / 1 GB) | ≈ $0.025/h | ≈ $0.08 | as the Security QA environment |
| **Total** | | **≈ $0.15 per session** | a forgotten instance costs ≈ $12/month: hence the teardown verification and the daily `rap-dev-secqa-*` check |

### 7.4 First-session candidates (owner picks one per session)

1. **V089/V090 migration rehearsal:** loader `--migrate-only` pointed at the restored secret; verify the schema, then run the admin-write suites against it.
2. **Activity audit:** enable audit on `-a8w` only; prove view and download rows for S1/S3 (US-SEC-004 #1/#2).
3. **Admin writes, revocation and approvals:** link, grant, revoke through the admin API; prove access is gone on the next request (US-SEC-002 #3); rehearse second-administrator bootstrap and the approval workflow (prerequisite for Central).
