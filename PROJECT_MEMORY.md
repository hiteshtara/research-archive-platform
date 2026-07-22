# Research Archive Platform
## Engineering Memory

This document is the long-term memory of the project.

Every AI assistant (ChatGPT, Codex, GitHub Copilot, Claude Code, Gemini, etc.) should read this file before making changes.

-------------------------------------------------------------------------------
PROJECT
-------------------------------------------------------------------------------

Boston University Research Archive Platform

Purpose

Preserve historical Kuali Research Administration data after retirement of the
legacy Kuali system.

The archive is read-only.

-------------------------------------------------------------------------------
CURRENT STATUS
-------------------------------------------------------------------------------

Completed

✓ IRB Archive

✓ Award Archive

✓ Global Search

✓ Award Workspace

✓ Award History

✓ Award Funding

✓ Award Amounts

✓ Award People

✓ Award Unit Contacts

Proposal

Completed

✓ Proposal database design

✓ Proposal migration V015

✓ proposal_version table

✓ proposal_person table

✓ proposal_award table

✓ Proposal DTOs

✓ Proposal extraction SQL

Current Work

Oracle Proposal export

proposal_versions.csv

proposal_people.csv

Proposal ETL

Next

ProposalArchiveRepository

ProposalService

ProposalController

Proposal Workspace

Proposal Search

Award → Proposal navigation

-------------------------------------------------------------------------------
ARCHITECTURE
-------------------------------------------------------------------------------

Hexagonal Architecture

adapter/

application/

domain/

Spring Boot

React

JdbcClient

Flyway

PostgreSQL

Oracle

Terraform

AWS ECS

AWS RDS

-------------------------------------------------------------------------------
RESEARCH OBJECT MODEL
-------------------------------------------------------------------------------

Proposal

↓

Award

↓

Funding

↓

IRB

↓

Negotiation

↓

Investigator

Proposal is the backbone of the archive.

Every major object should eventually connect to Proposal.

-------------------------------------------------------------------------------
DATABASE
-------------------------------------------------------------------------------

Current Archive Tables

IRB

Award

Proposal

Future

Negotiation

Subaward

Agreement

Investigator

-------------------------------------------------------------------------------
DEVELOPMENT ORDER
-------------------------------------------------------------------------------

Always implement features in this order.

1 Database migration

2 Oracle extraction SQL

3 CSV export

4 ETL

5 Repository

6 Service

7 Controller

8 React UI

Never skip steps.

-------------------------------------------------------------------------------
CODING RULES
-------------------------------------------------------------------------------

Mirror Award implementation.

Never invent Oracle columns.

Never invent package names.

Inspect existing implementation before writing new code.

Use JdbcClient.

Compile after every commit.

Push after successful compile.

Keep commits small.

Prefer cat <<'EOF' examples.

-------------------------------------------------------------------------------
DEPLOYMENT
-------------------------------------------------------------------------------

Development

Local Oracle

↓

CSV

↓

PostgreSQL

↓

Spring Boot

↓

React

↓

AWS ECS

Deploy only after feature completion.

-------------------------------------------------------------------------------
COMMON COMMANDS
-------------------------------------------------------------------------------

Backend

cd api

mvn test

Frontend

cd ui

npm run build

Git

git status

git add

git commit

git push

-------------------------------------------------------------------------------
KNOWN LESSONS
-------------------------------------------------------------------------------

Award implementation is the reference.

Proposal should mirror Award.

Proposal is the central research object.

Never guess Oracle metadata.

Always verify Oracle schema first.

ETL should be completed before Repository.

Repository before Service.

Service before Controller.

Controller before React.

-------------------------------------------------------------------------------
FUTURE MODULES
-------------------------------------------------------------------------------

Negotiation Archive

Subaward Archive

Agreement Archive

Investigator Workspace

Analytics

Timeline

Cross-object relationships

-------------------------------------------------------------------------------
LONG TERM GOAL
-------------------------------------------------------------------------------

Create a complete historical archive of Boston University research
administration data.

The application should eventually replace the legacy Kuali portal for historical
research records.

