# Mac PostgreSQL access over SSM (DBeaver, DataGrip, psql)

Read-only access to the **dev** Research Archive database from a Mac GUI
client, without a bastion and without making RDS public.

```
Mac :15432  ->  SSM Session Manager  ->  ephemeral Fargate task  ->  RDS :5432
```

**Status: proposed, not deployed.** Everything is behind
`manage_db_tunnel_access`, which defaults to `false`. Until that is applied,
none of these resources exist and the scripts fail closed.

For ad hoc SQL with no GUI, [CloudShell](CLOUDSHELL_DATABASE_ACCESS.md) is
already approved, free, and has a smaller attack surface. Use this path when
you specifically need DBeaver/DataGrip, local files, or your own editor.

---

## First: your existing DBeaver connection is not a tunnel

A saved connection on **127.0.0.1:5432** with username **mukadder** is the
**local Homebrew PostgreSQL**, not dev. Verified on this Mac:

- `postgresql@17` is running as `mukadder`, listening on 127.0.0.1:5432
- it holds a local `research_archive` database at **migration V080**
- the repo is at **V084**, so the local copy is four migrations behind and
  has none of the `authz` schema

That matters beyond tidiness: `CLAUDE.md` records a case where local was a
stale V053 snapshot while dev RDS had been repopulated. Anything you conclude
about "the data" from localhost:5432 is a conclusion about a disposable local
copy.

**So this tunnel uses 15432, never 5432.** The original August 2026 script
chose 15432 for exactly this reason. Nothing here touches your existing
connection, its saved password, or local Postgres.

### DBeaver setup — duplicate, don't edit

1. Right-click the existing **research_archive** connection → **Duplicate**.
2. Rename the copy to something unmistakable, e.g. **research_archive (dev
   via SSM tunnel)**.
3. On the copy only, change:

   | Setting | Value |
   |---|---|
   | Host | `127.0.0.1` |
   | Port | **`15432`** |
   | Database | `research_archive` |
   | Username | **`archive_analyst`** |
   | Password | from `scripts/mac-show-analyst-password.sh` — tick *Save password* only if you accept it sitting in DBeaver's credentials store |
   | SSL | Enable; mode `require` |

4. Leave the original untouched. If the tunnel misbehaves, the original
   still opens local Postgres exactly as before — that is the rollback.

DataGrip is the same: duplicate the data source, change port and user.

> Do not point the duplicate at port 5432. It will connect — to local
> Postgres — and look like it worked.

---

## One-time setup

1. **Infrastructure** (needs approval; see the PR):
   `terraform apply -var manage_db_tunnel_access=true`
2. **Your IAM**: have the `research-archive-platform-dev-dbtunnel-operator`
   policy attached to your role. It is created but attached to nobody by
   default.
3. **Session Manager plugin**:
   `brew install --cask session-manager-plugin`

Check without starting anything:

```bash
scripts/start-db-tunnel.sh --check
```

## Daily use

```bash
buaws                                   # fresh BU credentials
scripts/start-db-tunnel.sh              # opens the tunnel, holds the shell
```

Leave that terminal open. In another terminal, or in DBeaver:

```bash
scripts/mac-show-analyst-password.sh    # copies to clipboard, never prints
psql "host=localhost port=15432 dbname=research_archive \
      user=archive_analyst sslmode=require" --password
```

**Ctrl-C** in the tunnel terminal closes the session and stops the task.

## Stopping, and what happens if you don't

Three independent mechanisms, because any one can fail:

| | Mechanism | Covers |
|---|---|---|
| 1 | `trap cleanup EXIT INT TERM` in the script | Ctrl-C, normal exit, errors, SIGTERM |
| 2 | The container's own `sleep 3600` | Mac sleeps, loses network, or is powered off mid-session |
| 3 | `scripts/stop-db-tunnel.sh` | Sweeping strays by hand |

Mechanism 2 is the one that matters for cost: a forgotten task cannot run up
a bill overnight, because it exits by itself within the hour. Tune with
`db_tunnel_max_session_seconds`.

## Verifying you are where you think you are

Always worth doing, given the localhost:5432 trap above:

```sql
SELECT current_user, current_database();   -- archive_analyst, research_archive
SHOW transaction_read_only;                -- on
SELECT MAX(version) FROM public.schema_migration;  -- dev, not the local V080
```

If `current_user` comes back as `mukadder`, you are on local Postgres.

## What this costs

| | |
|---|---|
| `ssmmessages` interface endpoint, 1 AZ | **$0.01/hour ≈ $7.30/month**, always on |
| Fargate task, 0.25 vCPU / 0.5 GB | **≈ $0.013/hour**, only while a tunnel is open |
| ECS cluster, IAM, security group, log group | $0 |
| Endpoint data processing | $0.01/GB — pennies for query results |

An hour of tunnelling a day is about **$7.70/month**. Prices are us-east-1
list, read from the AWS Price List API on 2026-10-26.

## Removing it

```bash
terraform apply -var manage_db_tunnel_access=false
```

That destroys the endpoint (the only recurring charge), the cluster, the
role, the policy, the security group and the RDS ingress rule. There is no
instance to decommission and nothing to patch, which is the main operational
argument for this shape over a bastion.

## Pre-approval review (2026-10-26)

Plan run against live dev state with the live `terraform.tfvars`:
**9 to add, 0 to change, 0 to destroy** — 126 resources tracked, 117 no-op.
Cognito, Route 53, Amplify, the API ECS service, the RDS instance and all
three CloudShell-analyst resources are **no-op**. No delete or replace
anywhere in the plan.

Three defects were found and fixed during that review:

| Found | Why it mattered |
|---|---|
| Operator policy used `${aws:username}` for session ARNs | AWS states this form "doesn't work for accounts that grant access using federated IDs" — every principal here is SAML-federated, so `TerminateSession`/`ResumeSession` would silently never match. Now `${aws:userid}`. |
| Task image resolved to the `:latest` tag | **`latest` does not exist** in the loader ECR repo (only `20260923T163946Z-443e507`). The plan would have succeeded and the first `run-task` would have failed to pull. Now read from the live loader task definition. |
| Operator policy was `(known after apply)` | The most security-sensitive document in the change could not be read during review. Cluster and role ARNs are now constructed locals, so the full policy renders in the plan. |

Cleanup was tested, not just read — with a stubbed AWS CLI:

| Path | Result |
|---|---|
| Ctrl-C mid-tunnel | task stopped, 1 × `stop-task` |
| Session fails after the task exists | task stopped, exit 254 |
| Exec agent never reaches RUNNING | task stopped, exit 1 |
| `run-task` itself fails | **no** spurious `stop-task`, exit 1 |

## Security notes

- **RDS stays private.** `PubliclyAccessible` is untouched. The ingress rule
  added to the database security group references a *security group*, never
  a CIDR; port 5432 is never open to the internet.
- **The task accepts nothing inbound.** Its security group declares
  `ingress = []`. SSM reaches it through a channel the task itself opens
  outbound.
- **The task holds no database credential.** You authenticate from your Mac.
  The task role grants four `ssmmessages` actions and nothing else — no
  Secrets Manager, no S3, no Bedrock.
- **CVE-2026-89049** (AWS bulletin 2026-107-AWS, 2026-09-10) is a
  server-side request forgery in SSM Agent's remote-host port forwarding —
  the exact feature used here. Someone able to start a forwarding session
  can reach link-local addresses and read the credentials of the role the
  agent runs as. On Fargate that is the task role, via `169.254.170.2`.
  Fixed in agent 3.3.4851.0, which on Fargate is AWS's to ship, not ours.
  Our mitigations: a task role that is worthless if stolen, and the
  operator policy restricting `ssm:StartSession` to this document and this
  cluster — AWS's own recommended workaround. **CloudShell does not carry
  this risk**; weigh that if GUI access is a convenience rather than a need.
- **`archive_analyst` is unchanged.** Read-only at the database level
  (`default_transaction_read_only=on`), so GUI clients get the same
  protection psql does. No new database or database user is created.
- **The tunnel's protection is the credential.** Anyone with the operator
  policy can forward to RDS; what they can *do* depends on which database
  user they authenticate as. Hand out `archive_analyst`, not the master
  credential.
- **Sessions are logged.** CloudTrail records every `StartSession` with the
  caller's identity. Note that Session Manager's regional preference streams
  to a CloudWatch group named `kuali-db-jumpbox-stg-jumpbox` (another BU
  project's; it is a regional setting, not ours) and that port-forwarding
  sessions log *metadata*, not the SQL you run. You get who connected and
  when, not what they queried.
