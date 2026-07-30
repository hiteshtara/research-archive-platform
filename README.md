# Research Archive Platform

Read-only historical archive for Boston University research administration
data, preserved after the retirement of the legacy Kuali Research
Administration system. The platform never writes back to source data — it
exists to make decades of Award, Proposal, Negotiation, Subaward, and IRB
history permanently searchable after the system of record goes away.

## Contents

- [About](#about)
- [Architecture](#architecture)
- [Repository layout](#repository-layout)
- [Getting started](#getting-started)
- [Testing](#testing)
- [Documentation map](#documentation-map)
- [Security](#security)
- [Contributing](#contributing)
- [Status](#status)

## About

**Data domains**: Awards, Institutional Proposals, Negotiations, Subawards,
IRB, Documents.

**Core model**: Proposal is the backbone of the archive — Award, Negotiation,
and Subaward each connect back to it. IRB is a separate, self-contained
domain. A prior "Protocol Archive" module (a second, independent
human-subjects archive alongside IRB) was removed after IRB was chosen as
the sole surviving domain for that data — see
[`docs/DECISIONS.md`](docs/DECISIONS.md) for the history. An optional,
read-only AI layer (Award summaries and Q&A, disabled by default) sits on
top of the archived data; see [Documentation map](#documentation-map) for
its design.

## Architecture

```text
Oracle (BU VPN-only, source of truth until retirement)
    │  Python ETL, run from a BU VPN-connected machine
    │  streams directly into Postgres - the only supported path
    ▼
PostgreSQL (archive schema, Amazon RDS)
    │  JdbcClient, no writes back to Oracle
    ▼
Spring Boot API
    │  Cognito-authenticated REST
    ▼
React UI
```

Oracle is the only supported source of structured data for the
Award/Negotiation/Subaward/Proposal loaders — CSV ingestion has been
retired entirely (no `SOURCE_MODE`, no `--csv`/`--csv-dir` flags; see
[`docs/DECISIONS.md`](docs/DECISIONS.md)). Amazon S3 is retained only for
document/attachment binary storage and for the legacy IRB Excel/Parquet
export pipeline, which is unaffected by this change. See
[`etl/README.md`](etl/README.md) and [`docs/runbooks/`](docs/runbooks/)
for the supported Oracle-direct workflow.

The API and UI never talk to Oracle directly — only the ETL does, and only
to read. Database migrations use Flyway's file-naming convention
(`database/migrations/V###__description.sql`) but are applied by the Python
ETL, not Spring Boot; `spring.flyway.enabled` is intentionally `false`. See
[`CLAUDE.md`](CLAUDE.md) for the full architectural detail, including where
the package layout departs from strict hexagonal architecture.

## Repository layout

| Path | Contents |
|---|---|
| `api/` | Spring Boot backend (Java 21) — REST API, business logic, persistence |
| `ui/` | React + TypeScript + Vite frontend |
| `etl/` | Python extraction/validation/load pipeline (Oracle → S3 → PostgreSQL) |
| `database/migrations/` | Versioned SQL schema migrations |
| `terraform/` | AWS infrastructure as code (VPC, RDS, ECS, ECR, S3, Secrets Manager) |
| `ops/` | Operational scripts and the AWS operations manual |
| `docs/` | Architecture, AI feature design, and per-domain ETL data-contract/reconciliation docs |
| `scripts/` | Local development helper scripts |

## Getting started

Two supported local setups (see
[`docs/runbooks/LOCAL_SETUP.md`](docs/runbooks/LOCAL_SETUP.md) for the full
walkthrough, including the BU VPN and AWS SSM tunnel steps):

```bash
# Local Postgres, no AWS dependency
./scripts/run-local.sh

# Or: tunnel to the real dev RDS instance via SSM (needs direnv + AWS creds)
./api/scripts/dev.sh   # in one terminal
cd ui && npm run dev   # in another
```

Backend and frontend commands:

```bash
cd api && mvn compile && mvn test      # backend: build + test
cd ui && npm run dev                    # frontend: dev server
cd ui && npm run build                  # frontend: type-check + build
cd etl && uv sync && uv run pytest      # ETL: install + test
```

Full command reference (single-test invocations, linting, etc.) is in
[`CLAUDE.md`](CLAUDE.md).

## Testing

| Layer | Command |
|---|---|
| API | `cd api && mvn test` |
| UI | `cd ui && npm run test` (presentation-helper unit tests) |
| UI types | `cd ui && npx tsc -b` |
| ETL | `cd etl && uv run pytest` |

Run the relevant suite before every commit; see
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the full workflow.

## Documentation map

| Document | Purpose |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Architecture, commands, and coding conventions for anyone (human or AI agent) working in this repo |
| [`PROJECT_MEMORY.md`](PROJECT_MEMORY.md) | Long-term memory: status history, validated data-grain numbers, incident lessons |
| [`docs/development/MASTER_ROADMAP.md`](docs/development/MASTER_ROADMAP.md) | Current status and planned phases |
| [`docs/AI_ARCHITECTURE.md`](docs/AI_ARCHITECTURE.md), [`docs/AI_OPENAI_PROVIDER.md`](docs/AI_OPENAI_PROVIDER.md) | AI feature design and provider integration |
| [`docs/AI_TROUBLESHOOTING.md`](docs/AI_TROUBLESHOOTING.md), [`docs/runbooks/ecs-ai-deployment.md`](docs/runbooks/ecs-ai-deployment.md) | AI feature deployment and incident postmortems |
| [`docs/runbooks/`](docs/runbooks/) | Local setup, ETL, Oracle, and troubleshooting quick references |
| [`ops/AWS_OPERATIONS.md`](ops/AWS_OPERATIONS.md) | AWS account, Amplify, ECR, ECS operations manual |
| `docs/*_CSV_CONTRACT.md`, `docs/*_RECONCILIATION.md` | Per-domain (Negotiation/Subaward) ETL data contracts and validation |

## Security

The archive's core security property is that it is **read-only** end to end.
See [`SECURITY.md`](SECURITY.md) for the authentication model, data-handling
guarantees (including the AI feature's redaction and citation-validation
design), and how to report a security concern.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the required development order,
coding conventions, and commit expectations before making a change.

## Status

Award, Proposal, Negotiation, and Subaward archives are complete. Legacy IRB
is preserved and is the sole human-subjects/protocol domain in this
application; a separate "Protocol Archive" module that was under
development as a possible replacement for IRB has been removed in full
(API, UI, ETL, and a forward-only schema-removal migration — see
[`docs/DECISIONS.md`](docs/DECISIONS.md)). See
[`docs/development/MASTER_ROADMAP.md`](docs/development/MASTER_ROADMAP.md)
and [`docs/CURRENT_SPRINT.md`](docs/CURRENT_SPRINT.md) for details.
