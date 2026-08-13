# Local Development Setup

## 1. Connect to BU VPN

Oracle is accessible only through the BU VPN/private network.

Never connect AWS directly to Oracle.

-------------------------------------------------------------------------------

## 2. Dev RDS access: ECS Fargate, not a local tunnel

**DEPRECATED/REMOVED (2026-08-13):** `scripts/start-db-tunnel.sh` and
`api/scripts/dev.sh` (an SSM port-forwarding tunnel from
`localhost:15432` to dev RDS) have been deleted. This project has no EC2
bastion host, so a local Mac-to-RDS tunnel was never actually usable -
keeping the script around, even though it correctly failed closed every
time, kept sending sessions down the wrong path instead of straight to
the route that actually works. **There is no supported direct
Mac-to-dev-RDS connection**, and none should be provisioned (a personal
EC2 instance, a personal RDS endpoint, networking/security-group
changes) without explicit approval.

**The supported path for dev RDS database investigation and one-off ETL
execution is an ECS Fargate task.** Cluster
`research-archive-platform-dev-etl`, task family
`research-archive-platform-dev-loader` - it already runs inside the same
VPC as RDS with a direct security-group rule, and reaches it via
`POSTGRES_SECRET_ID` (Secrets Manager) with no tunnel at all. See
`docs/runbooks/UNATTENDED_FARGATE_ETL_LOADS.md` and `CLAUDE.md`'s
"Authoritative data location" section for the full mechanism
(`scripts/run-award-loader.sh`, `etl/scripts/build_award_ecs_overrides.py`,
one-off `aws ecs run-task` diagnostic commands).

Local Homebrew Postgres (started by `scripts/run-local.sh`) is **not**
the authoritative dev database and can be silently stale relative to RDS
- do not use it to validate deployed data, reconciliation counts, or
ETL completeness. It's for local unit/integration testing only, and
results from it must be labeled as local test data.

-------------------------------------------------------------------------------

## 3. Backend

cd api

mvn test

-------------------------------------------------------------------------------

## 4. Frontend

cd ui

npm install

npm run dev

-------------------------------------------------------------------------------

## 5. ETL

PYTHONPATH=etl

uv run --project etl python ...

-------------------------------------------------------------------------------

## 6. Finish

git status

Working tree should be clean.

