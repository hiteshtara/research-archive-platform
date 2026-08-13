# Troubleshooting

## Connection refused localhost:15432

**Removed 2026-08-13.** This referred to `scripts/start-db-tunnel.sh`, a
local SSM tunnel to dev RDS - deleted because this project has no EC2
bastion, so the tunnel could never actually be opened. Do not try to
restart it. For dev RDS, use an ECS Fargate one-off task instead (see
`CLAUDE.md`'s "Authoritative data location" section). For local
development, use `scripts/run-local.sh`.

-------------------------------------------------------------------------------

## DuplicateTable

Migration already applied manually.

Update schema_migration.

-------------------------------------------------------------------------------

## Oracle metadata

Never guess.

Run proposal_columns.sql first.

-------------------------------------------------------------------------------

## Maven

Backend module is api/

Run

cd api

mvn test

