# ETL Runbook

Environment

PYTHONPATH=etl

-------------------------------------------------------------------------------

Run

uv run --project etl ...

-------------------------------------------------------------------------------

Workflow

Oracle

↓

CSV

↓

Validation

↓

Bulk Copy

↓

PostgreSQL

-------------------------------------------------------------------------------

Order

Migration

↓

CSV

↓

ETL

↓

Validation

