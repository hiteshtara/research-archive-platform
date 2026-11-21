# RDS deletion protection — recommendation (not applied)

**Nothing changed.** `DeletionProtection` is currently **`false`** on
`research-archive-platform-dev-postgres`. This is a recommendation
awaiting a decision.

Found incidentally while gathering the backup posture for the RDS upgrade
assessment, not as part of any requested scope — recorded because it is
a small, cheap gap.

## 1. Current state

| Property | Value |
|---|---|
| Instance | `research-archive-platform-dev-postgres` |
| `DeletionProtection` | **`false`** |
| `BackupRetentionPeriod` | 7 days |
| Automated snapshots retained | 10, most recent `available` |
| Point-in-time recovery | current to within minutes |
| Multi-AZ | no |

## 2. What it does, and what it does not

Deletion protection blocks `DeleteDBInstance` outright — the API call
fails until the flag is cleared. It is a **guard against an accidental
delete**, nothing more.

It does **not** protect against: dropping tables or data inside the
database, a destructive migration, a bad ETL load, or a Terraform
`destroy` that first clears the flag. It is one specific class of
accident: someone deletes the wrong instance.

## 3. Why it is worth enabling even on DEV

- This DEV database is **not** disposable in the way "dev" suggests.
  `docs/project-memory` and CLAUDE.md are explicit that it is the
  authoritative dev data of record, repopulated from Oracle staging, and
  that the local Homebrew copy must never be used to validate deployed
  data. Losing it would mean re-running the Oracle ETL, not restoring a
  throwaway.
- The instance identifier is a **near-miss for other names** in the same
  account — `research-archive-platform-dev-postgres` alongside the
  `-dev-api`, `-dev-etl` and `-dev-dbtunnel` clusters. Delete-the-wrong-
  thing is the exact accident this prevents.
- It is **free** and has no performance effect.
- Backups mitigate but do not eliminate the risk: restoring from a
  snapshot creates a **new instance with a new endpoint**, so recovery
  also requires repointing the `POSTGRES_HOST` secret and redeploying.
  That is a real outage, not a quick undo.

## 4. The cost of enabling it

One genuine friction: **while the flag is set, the instance cannot be
deleted without clearing it first.** That matters in exactly two places:

- **Terraform.** If RDS is Terraform-managed and a future plan wants to
  replace the instance, the apply will fail until the flag is cleared.
  `deletion_protection` should therefore be set **in the Terraform
  module**, not by a console or CLI change, or the next plan will show
  drift and may try to set it back to `false`.
- **Intentional teardown.** A deliberate decommission becomes a
  two-step operation. That is the point.

**This is the reason not to just do it:** setting it out-of-band would
create exactly the kind of console-versus-code drift this project has
been bitten by before (see the `terraform/README.md` drift and the
untracked-`tfvars` findings in `AWS_TROUBLESHOOTING_RUNBOOK.md`).

## 5. Recommendation

**Enable it — but through Terraform, not the CLI.**

1. **First establish whether RDS is Terraform-managed here.** The module
   reference `module "rds" { vpc_id = module.vpc.vpc_id }` in
   `terraform/environments/dev/main.tf` suggests it is. Confirm against
   the actual `.tf` files and `git ls-files` rather than the README —
   this repo has a documented history of READMEs drifting from the real
   configuration.
2. If Terraform-managed: add `deletion_protection = true` to the RDS
   module, run a **read-only plan from `main`** (per
   `reference_terraform_dev_standing_drift`, planning from a stale
   branch reads as many destroys), confirm the plan shows **1 change, 0
   destroys**, and apply.
3. If **not** Terraform-managed: a single CLI call is acceptable, and
   should be recorded in the deployment-architecture notes so it is not
   later mistaken for drift:
   ```
   aws rds modify-db-instance --region us-east-1 \
     --db-instance-identifier research-archive-platform-dev-postgres \
     --deletion-protection --apply-immediately
   ```
4. **No downtime either way.** `DeletionProtection` is a metadata flag;
   it requires no reboot and does not interrupt connections. It is safe
   to apply at any time, including while the fan-out experiment is
   pending — it cannot affect measurements.

## 6. Scope note

Consider the same question for **Production** RDS, which is outside
everything in this engagement and must not be touched without its own
approval. If Production has `DeletionProtection: false`, that is a
materially larger finding than this one — but I have not looked, and
should not without being asked.
