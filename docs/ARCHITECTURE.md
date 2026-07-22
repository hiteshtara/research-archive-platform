# Research Archive Platform Architecture

## Technology

- Spring Boot
- React
- PostgreSQL
- Oracle (source)
- Flyway
- JdbcClient
- AWS ECS
- AWS RDS
- Terraform

## Architecture

Hexagonal Architecture

adapter/
application/
domain/

## Data Flow

Oracle
    ↓
CSV Export
    ↓
ETL
    ↓
PostgreSQL Archive
    ↓
Spring Boot API
    ↓
React UI

## Core Objects

Proposal
Award
IRB
Negotiation
Investigator
Subaward

Proposal is the central research object.

