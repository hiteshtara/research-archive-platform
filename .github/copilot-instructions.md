# GitHub Copilot Instructions

For architecture, commands, and coding conventions, see `CLAUDE.md` at the
repository root — that is the source of truth for this repo, not this file.

One correction to note: migrations are **not** run via Spring Boot Flyway
(`spring.flyway.enabled: false`). SQL files in `database/migrations/` use
Flyway's naming convention but are applied by the Python ETL
(`etl/archive_etl/upload/migrations.py`), tracked in `public.schema_migration`.
