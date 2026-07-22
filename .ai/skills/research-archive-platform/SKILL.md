# Research Archive Platform Skill

## Project

Boston University Historical Research Archive Platform

Purpose:
Preserve Kuali historical research administration data after Kuali retirement.

---

## Architecture

Hexagonal Architecture

adapter
application
domain

Spring Boot

React

PostgreSQL

Oracle source

Flyway

JdbcClient

Terraform

AWS ECS

---

## Core Objects

Proposal (center)

Award

IRB

Negotiation

Investigator

Subaward

Proposal is the backbone of the system.

---

## Development Order

Database

↓

ETL

↓

Repository

↓

Service

↓

Controller

↓

React

---

## Coding Rules

Always use existing Award implementation as the template.

Never invent package names.

Never invent Oracle column names.

Mirror existing architecture.

Compile after every commit.

Push after successful compile.

Prefer cat <<'EOF' examples.

Keep commits small.

---

## Current Sprint

Proposal Archive

Completed

- Proposal migration
- Proposal tables
- Proposal extraction SQL

Next

- Proposal ETL
- Proposal Repository
- Proposal API
- Proposal Workspace

