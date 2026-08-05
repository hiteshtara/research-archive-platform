# Repository scripts

This directory contains operator-facing wrappers for local development, AWS
access and deployment, and Award ETL jobs. Start with the least destructive
command that proves your configuration: `--help`, `--check-only`, `--dry-run`,
or a read-only reporting mode where one exists.

## Choose a workflow

| Goal | Start here | Effect |
|---|---|---|
| Run the API and UI locally | [`run-local.sh`](../docs/scripts/getting-started.md#run-the-application-locally) | Starts local processes |
| Seed local attachment examples | [`setup-local.sh`](../docs/scripts/getting-started.md#add-the-local-attachment-demo) | Writes synthetic local files and rows |
| Get a dev Cognito token | [`get-access-token.sh`](../docs/scripts/operations.md#get-a-cognito-access-token) | Authenticates; prints a token |
| Check/open an RDS tunnel | [`start-db-tunnel.sh`](../docs/scripts/operations.md#open-a-dev-database-tunnel) | Read-only check or long-lived SSM session |
| Deploy the API or the full dev stack | [`deploy-api.sh`](../docs/scripts/operations.md#deploy-to-dev) / [`dev-deploy.sh`](../docs/scripts/operations.md#deploy-to-dev) | Builds, pushes, and updates AWS resources |
| Run Award ETL or diagnostics | [`run-award-loader.sh`](../docs/scripts/operations.md#run-award-etl-in-ecs) | May write PostgreSQL and register/run ECS tasks |
| Run attachment ETL or uploads | [`run-award-attachment-loader.sh`](../docs/scripts/operations.md#run-attachment-etl-in-ecs) | May write PostgreSQL/S3 and register/run ECS tasks |
| Query archived data | [`run-archive-explorer.sh`](../docs/scripts/operations.md#query-the-archive-in-ecs) | Read-only data query; still registers/runs an ECS task |
| Repair an exported child CSV | [`filter_award_child_export.py`](../docs/scripts/operations.md#filter-an-award-child-export) | Backs up and replaces the child CSV |

## Documentation

- [Getting started](../docs/scripts/getting-started.md) — local application and fixture tutorial
- [Operations](../docs/scripts/operations.md) — task-oriented commands and safety checks
- [Script reference](../docs/scripts/reference.md) — complete inventory, inputs, dependencies, and side effects
- [Architecture and safety](../docs/scripts/architecture-safety.md) — boundaries, credentials, AWS execution, and recovery

The ETL implementation itself is documented in [`etl/README.md`](../etl/README.md).
Production AWS procedures remain authoritative in [`ops/`](../ops/); these
pages explain how the wrappers in this directory connect to those systems.
