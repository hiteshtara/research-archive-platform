# Local Development Setup

## 1. Connect to BU VPN

Oracle is accessible only through the BU VPN/private network.

Never connect AWS directly to Oracle.

-------------------------------------------------------------------------------

## 2. Start AWS SSM Tunnel

export AWS_PROFILE=bu-nprd
scripts/start-db-tunnel.sh

This resolves the RDS endpoint from Terraform/Secrets Manager and
discovers an SSM-managed bastion host in the project's own VPC itself -
it requires account 770203350335 and refuses to run against anything
else. Use `--check-only` first to validate everything without opening a
tunnel: `scripts/start-db-tunnel.sh --check-only`. See the script's own
`--help` for details and env var overrides.

As of this writing, this project's ECS service runs on Fargate (no EC2
instances) and there is no dedicated bastion host yet - the script will
say so clearly rather than connect to anything else. Do not substitute
a personal EC2 instance ID or a personal RDS endpoint here; if you see
either of those, the tunnel is not targeting BU's environment.

Leave this terminal running once the tunnel is up.

**If no bastion exists, this tunnel is simply unavailable - that does
NOT mean dev RDS is unreachable.** ECS Fargate tasks (cluster
`research-archive-platform-dev-etl`, task family
`research-archive-platform-dev-loader`) already run inside the same VPC
as RDS with a direct security-group rule, and reach it via
`POSTGRES_SECRET_ID` without any tunnel at all - see
`docs/runbooks/UNATTENDED_FARGATE_ETL_LOADS.md` and `CLAUDE.md`'s
"Authoritative data location" section for the ECS route (`scripts/run-award-loader.sh`,
etc.), which is the correct fallback for database investigation/one-off
ETL work when this tunnel can't be opened. Never provision a bastion or
substitute personal infrastructure to work around this.

Local Homebrew Postgres (started by `scripts/run-local.sh`) is **not**
the authoritative dev database and can be silently stale relative to RDS
- do not use it to validate deployed data, reconciliation counts, or
ETL completeness. It's for local unit/integration testing only, and
results from it must be labeled as local test data.

-------------------------------------------------------------------------------

## 3. Environment

export POSTGRES_HOST=localhost
export POSTGRES_PORT=15432
export POSTGRES_DB=research_archive

Verify

env | grep POSTGRES

-------------------------------------------------------------------------------

## 4. Verify Tunnel

lsof -nP -iTCP:15432 -sTCP:LISTEN

-------------------------------------------------------------------------------

## 5. Backend

cd api

mvn test

-------------------------------------------------------------------------------

## 6. Frontend

cd ui

npm install

npm run dev

-------------------------------------------------------------------------------

## 7. ETL

PYTHONPATH=etl

uv run --project etl python ...

-------------------------------------------------------------------------------

## 8. Finish

git status

Working tree should be clean.

