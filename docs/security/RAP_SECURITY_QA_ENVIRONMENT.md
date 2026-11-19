# RAP Security QA environment (proposal)

Status: **proposed 2026-11-18, not approved.** It turns the isolated A8 setup into a
repeatable, on-demand QA capability. Between sessions nothing runs and nothing
routes. Only what is listed under "Retained between sessions" stays in AWS.

Basis: the isolated A8 run on 2026-11-18 (`A6_A8_EXECUTION_PACKAGE.md` §8 and §9).
That run proved the pattern works and leaves shared DEV untouched.

## 1. What it is

An enforcement-ON copy of the DEV API that only the operator's IP can reach. It
runs only during a test session, behind the existing DEV ALB. It reads the shared
DEV database through the same read paths as the shared API, with every write
path switched off.

```
operator Mac (home IP /32) ──HTTPS── DEV ALB ──rule p10: host=api-dev.* AND source-ip=/32──► private task (-a8 family, enforcement ON)
everyone else ─────────────HTTPS── DEV ALB ──default──────────────────────────────────────► shared service :106 (enforcement OFF)
                                                     both read ──► shared DEV RDS
```

## 2. Retained between sessions (inert)

| Resource | Why kept | Can it do anything on its own? |
|---|---|---|
| Task definition family `research-archive-platform-dev-api-a8` (rev 1 = `:106` image `c85be89a…` + the six settings), source `ops/security-qa/api-a8-taskdef.json` | requirement 1 | No. A task definition only runs when someone runs it |
| `scripts/a8_authz_matrix.py`, `scripts/a8_enforcement_probe.py`, `ops/security-qa/isolation_negative_probe.py`, this document, `A8_OWNER_MANUAL_UAT.md` | requirement 2 | No |
| Synthetic Cognito users S1/S3/S4, **disabled** | requirement 5. Re-enabled per session | No. A disabled user cannot sign in, and global sign-out revoked its refresh tokens |
| Identity links 5/6/7 (ADMIN_VERIFIED, no Kuali person) | avoid re-linking every session | No. A link grants nothing without a grant, and its user is disabled |
| Secrets `rap/dev/synthetic/a6/{dept,oav,none}` | needed to sign the users in | Only the passwords of disabled users |

**Not retained:** the listener rule, the target group, the running task, grants and
`ArchiveAttachmentViewer` membership. They are created per session and removed
at its end.

## 3. Session procedure (repeatable)

To be scripted as `ops/security-qa/session.sh {start,verify,stop}` after approval.
Every step is the one run live on 2026-11-18.

**Start:**
1. **Pre-checks:**
   - AWS account 770203350335;
   - `checkip` equals the approved operator IP;
   - Cisco VPN `Disconnected`;
   - shared service `:106` 1/1, one deployment;
   - listener has only the default rule;
   - taskdef `-a8:N` re-diffed against the current shared revision (expect the six settings only; re-derive N if the shared revision has moved).
2. **Accounts:** enable S1/S3/S4, then create grants (dry run, then `--confirm`, then read-only scope query), each expiring session end + 24 h. Add the group only if attachment tests are in scope.
3. **Baseline:** run the matrix baseline against **shared** DEV (before any rule exists).
4. **Private task:**
   - create the TG (health check copied from `rap-dev-api-tg`);
   - start the task with `run-task`, register its IP, and check the image digest;
   - create the rule **inert** (host `a8-inert.invalid` + IP), wait for healthy, and check enforcement via the inert host.
5. **Activate:** switch the rule to the real host, then verify both directions:
   - AWS-source probe: 40 on shared, 0 on private;
   - S4 dashboard: 403 on private, 40,926 on shared via the ALB name;
   - read-only DB snapshot.

**Test:** matrix and cross-check, then the browser-automated manual checks. Stop on any LEAK, then investigate.

**Stop** (always, including abort, cancellation or a missed deadline):
1. Delete the rule.
2. Stop the task.
3. Delete the TG.
4. Revoke the grants.
5. Remove the group membership and verify it equals the pre-session list.
6. Disable the users and run `admin-user-global-sign-out`.
7. Read-only verification: 0 unrevoked synthetic grants, 0 a8 tasks, no a8 TG, listener default only, shared `:106` healthy.

**Hard limits:**
- a 3 h maximum session, with the stop sequence by the deadline whatever has finished;
- an operator present throughout.

## 4. Why it cannot interfere with shared DEV

- **Routing:** the rule matches only `Host = api-dev…` **and** the operator `/32`.
  - Nothing sits in front of the ALB, so the ALB sees each client's real IP (no CloudFront or WAF; verified).
  - If the IP changes or the VPN is on, the operator falls back to shared, unenforced DEV, and the capability check detects it. That fallback discloses nothing.
- **Compute:** a one-off `run-task`, not a service.
  - The shared service's deployments, scaling and target group are never touched.
  - The family name differs, so `update-service` on the shared service cannot pick it up by accident.
- **Database:** no scheduled or async work; operator CLIs are profile-gated.
  - Admin writes, activity audit and enrollment are unset, and every write path is gated on them (code-verified).
  - Live: `authz` and `archive` write counters were unchanged across about 900 requests.
  - Cost to the database: one extra Hikari pool.
- **Identity:** synthetic users only, plus the owner's own unchanged account. No real BU identity is created, changed or impersonated.

## 5. Will a separate database be needed?

**Yes, for anything that writes.** No, for read-only authorization testing (today's scope).

| Test type | Shared DEV RDS OK? | Why |
|---|---|---|
| Record-level enforcement, search scoping, isolation | **Yes** (as run today) | Read-only; no write path enabled |
| Administrator writes (grant/link/suspend through the admin API, approvals) | **No** | They write `authz.*` rows that the shared API also reads. They are harmless while shared enforcement is OFF, but they persist, and they would take effect the day shared enforcement is enabled |
| Activity auditing (SEC-004 view/download logging) | **No** | It writes audit rows for every view into the shared database |
| Migrations V089/V090, or any future migration | **No** | They change the shared schema for every DEV user and loader |

**Recommended for write and migration sessions: restore a snapshot on demand.**
1. Restore the latest automated snapshot of the DEV RDS to a temporary instance in the same VPC and subnets.
2. Point a second private taskdef (`-a8w`, writes or audit enabled) at a session-only secret for that instance.
3. Run the session.
4. Delete the instance, with no final snapshot unless the evidence requires one.

- **Cost:** roughly the hourly price of the same instance class for 1–3 h, plus 15–30 min to restore.
- **Alternative:** Testcontainers (already in CI) covers write logic, but not DEV data.

**This needs its own approval.** A new database instance and secret are new
infrastructure.

## 6. Ongoing cost

| Item | Between sessions | Per 3 h session |
|---|---|---|
| Task definition, TG, rule | $0 | $0 (no charge for TG or rules; ALB already exists) |
| Fargate 0.5 vCPU / 1 GB | $0 (stopped) | ≈ $0.08 |
| Probe and verification tasks (loader image) | $0 | < $0.05 |
| 3 Secrets Manager secrets | ≈ $1.20 / month | — |
| Cognito synthetic users (disabled) | $0 (free tier) | — |
| CloudWatch logs | negligible | negligible |
| Snapshot-restored RDS (write sessions only, §5) | $0 | instance-class hourly price × hours |

## 7. Security risks and controls

| Risk | Control |
|---|---|
| The operator IP is shared by other devices or people on the home network | Session only while the operator is present; rule removed at stop. A household device could reach the private API only with its own valid RAP sign-in |
| The operator IP changes mid-session | Fail-safe: traffic falls back to the unenforced shared API, which is not a disclosure. Re-check the IP before activating and before each test block |
| Someone deploys `-a8` to the shared service | Separate family; runbook pre-check that the shared service is on its own family; tag `purpose=A8-isolated-enforcement-test-temporary` |
| A forgotten rule or task after a session | Stop sequence on every exit path; read-only verification step; 3 h cap. A future `verify` command can run from CloudWatch on a schedule, alerting on any rule at priority 10 or any `A8-isolated` task |
| Retained synthetic secrets | The users are disabled; secrets are readable only by bu-nprd operators; rotate on re-enable |
| Unmanaged (non-Terraform) resources | Only the taskdef persists, and it is outside Terraform state, so the DEV plan stays "No changes". Documented here |
| Matrix false positives or negatives | Self-tests (34 cases) plus field-level reproduction before any verdict; echo fields excluded only at top level (a self-test proves a real leak inside `results` is still caught) |
| Test results mistaken for business acceptance | Business criteria move to DONE only on the owner's direction |

## 8. Cleanup if the QA environment is retired

1. Run the stop sequence (§3).
2. Unlink links 5/6/7 (`--unlink-link-id` only).
3. Delete the three Cognito users.
4. Delete the three secrets: `--recovery-window-in-days 7`, or immediately on the owner's word.
5. Deregister `-a8:*`, then `delete-task-definitions`.
6. Remove `ops/security-qa/` and the matrix scripts, or archive them.

## 9. Decisions requested

1. Approve retaining the inert set in §2 (taskdef, disabled users, links, secrets).
2. Approve scripting §3 as `ops/security-qa/session.sh` (code only; nothing runs without per-session approval).
3. Decide separately on the snapshot-restored database (§5) for admin-write, audit and V089/V090 testing.
4. Decide whether each session needs its own approval or a standing one with the §3 limits.
