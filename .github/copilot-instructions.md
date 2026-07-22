# GitHub Copilot Instructions

This repository follows Hexagonal Architecture.

Always mirror existing implementations.

Never introduce new architecture.

Use JdbcClient.

Use Flyway migrations.

Proposal is the central object.

Development order:

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

Never guess Oracle schema.

Always verify Oracle columns before writing SQL.

Compile after every change.

Prefer small commits.

