# Research Archive Platform Skill

## Project

Boston University Research Archive Platform

Purpose

Provide a historical archive for Kuali research administration data after Kuali retirement.

---

## Architecture

Hexagonal Architecture

adapter/
application/
domain/

Spring Boot

React

JdbcClient

PostgreSQL

Oracle

Flyway

Terraform

AWS ECS

AWS RDS

---

## Core Objects

Proposal

Award

IRB

Negotiation

Investigator

Subaward

Proposal is the central research object.

---

## Development Workflow

1 Database

2 ETL

3 Repository

4 Service

5 Controller

6 React

---

## Coding Rules

Mirror Award implementation.

Never invent Oracle column names.

Always inspect existing implementation first.

Compile after every commit.

Push after successful compile.

Use cat <<'EOF' for generated files.

Keep commits small.

---

## Current Sprint

Proposal Archive

Completed

- Proposal migration V015
- Proposal tables
- Proposal extraction SQL

Next

- Proposal ETL
- Proposal Repository
- Proposal API
- Proposal Workspace

