# Research Archive Platform

This file provides instructions for AI coding agents (Codex, ChatGPT, GitHub Copilot, Claude Code, Gemini CLI, etc.) working on this repository.

Always read `PROJECT_MEMORY.md` before making changes.

---

# Project

Boston University Research Archive Platform

Purpose:

Provide a historical archive of Kuali research administration data after the retirement of the Kuali system.

---

# Architecture

Follow the existing architecture.

Hexagonal Architecture

Packages:

- adapter/in
- adapter/out
- application
- domain

Do not introduce a new architecture.

Always mirror existing implementations.

---

# Technology

- Java 21
- Spring Boot
- JdbcClient
- React
- PostgreSQL
- Oracle (source)
- Custom SQL migration runner (`public.schema_migration`)
- Python ETL
- AWS ECS
- AWS RDS
- Terraform

---

# Core Research Objects

Proposal

Award

Protocol

Negotiation

Investigator

Subaward

Proposal is the central research object.

---

# Business grain and historical grain

- Never treat raw archive row counts as business-object counts.
- Identify the business grain before writing dashboard, API, ETL, or
  reconciliation logic.
- Identify the historical grain separately. Preserve both counts when they
  serve different purposes.
- Do not silently deduplicate valid historical source rows.
- Do not infer meaning from a table name or `COUNT(*)` alone.
- Inspect migrations, schema, source mappings, and existing queries before
  deciding the grain.

Award:

- Awards business grain is `COUNT(DISTINCT award_number)`.
- Historical Award Records includes every row in `archive.award_version`.
- Validated values: 281,591 raw rows; 43,057 distinct `award_number`;
  281,455 distinct (`award_number`, `sequence_number`); 0 duplicate
  `award_id` rows.
- Multiple rows may legitimately share `award_number` and `sequence_number`
  when `award_id` differs. Never delete or merge those rows merely to make
  counts align.

Proposal:

- Do not assume `archive.proposal` exists. Inspect `information_schema` and
  migrations to find the real Proposal table or view.
- Do not use `archive.award_funding_proposal` as the main Proposal count.
- Proposal dashboard counts must use the stable institutional Proposal
  business identifier.
- Do not assign meaning to profiling numbers unless the exact SQL and column
  order are known.

Protocol:

- Historical Protocol child records must use the validated parent-resolution
  method documented in `PROJECT_MEMORY.md`.
- Resolve Protocol amendment/renewal parents by `protocol_number` plus
  `sequence_number`.
- Preserve Oracle `PROTOCOL_ID` only as audit metadata where it does not match
  the historical archive parent.

---

# Dashboard counts

- Labels must match the count grain.
- `Awards` = distinct `award_number`.
- `Historical Award Records` = `COUNT(*)` from `archive.award_version`.
- `Funding Relationships` = relationship-row count.
- `Protocol Families` = distinct protocol base.
- `Historical Versions` = version-row count.

---

# Development Order

Always implement features in this order.

1. Database migration
2. Oracle extraction SQL
3. ETL
4. Repository
5. Service
6. Controller
7. React UI

---

# Coding Rules

- Mirror the Award implementation whenever possible.
- Never invent package names.
- Never invent Oracle table or column names.
- Verify Oracle metadata before writing SQL.
- Use JdbcClient.
- Follow the repository migration conventions and use
  `public.schema_migration`.
- Keep one logical change per commit.
- Compile and test before every commit.
- Push only after successful compilation.
- Prefer cat <<'EOF' examples for generated files.
- Do not deploy until the feature is complete.

---

# Current Sprint

Proposal Archive

Completed

- Proposal migration (V015)
- proposal_version
- proposal_person
- proposal_award tables
- Proposal DTOs
- Proposal extraction SQL
- Oracle discovery SQL

In Progress

- Oracle proposal export
- proposal_versions.csv
- proposal_people.csv
- Proposal ETL

Next

- ProposalArchiveRepository
- ProposalService
- ProposalController
- Proposal Workspace
- Proposal Search
- Award → Proposal navigation

---

# Validation

Backend

cd api
mvn test

Frontend

cd ui
npm run build

Before committing:

- Run the relevant tests.
- Run `git diff --check`.
- Show changed files.
- Show exact SQL/count logic.
- Show test results.
- Do not commit or push unless explicitly requested.
