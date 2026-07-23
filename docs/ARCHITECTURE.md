# Research Archive Platform Architecture

## Technology

- Spring Boot
- React
- PostgreSQL
- Oracle (source)
- Custom SQL migration runner (`public.schema_migration`)
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
Protocol
Negotiation
Investigator
Subaward

Proposal is the central research object.
