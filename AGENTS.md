# Research Archive Platform

This file provides instructions for AI coding agents (Codex, ChatGPT, GitHub Copilot, Claude Code, Gemini CLI, etc.) working on this repository.

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
- Compile after every commit.
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

Always leave the working tree clean before finishing work.
