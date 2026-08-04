# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Boston University Research Archive Platform: a **read-only** historical archive of
Kuali Research Administration data, preserved after the legacy Kuali system's
retirement. It is not a system of record and never writes back to source data.

Monorepo: `api/` (Spring Boot backend), `ui/` (React frontend), `etl/` (Python
extraction/load pipeline), `database/migrations/` (schema), `terraform/` (AWS
infra), `ops/` (deployment scripts).

## Commands

**Backend** (`api/`, Java 21 / Spring Boot 3.5, Maven):
```
mvn compile                          # compile
mvn test                             # run all tests
mvn test -Dtest=ClassName            # run a single test class
mvn test -Dtest=ClassName#methodName # run a single test method
mvn spring-boot:run                  # run the API (see "Local dev" below re: profile)
```

**Frontend** (`ui/`, React + TypeScript + Vite):
```
npm run dev      # Vite dev server
npm run build    # tsc -b && vite build
npm run lint      # oxlint
npm run test      # runs node --test against src/features/ai/*.test.mjs
```
There is no component-render test setup (no `@testing-library/react`) — existing
`*.test.mjs` files are plain `node:test` unit tests of presentation-helper
functions, not rendered-component tests.

**ETL** (`etl/`, Python, managed with `uv`):
```
uv sync
uv run pytest
uv run pytest tests/test_x.py::test_name   # single test
```

## Local development

Two supported ways to run the API locally — they're not interchangeable:
- `scripts/run-local.sh`: starts a local Homebrew Postgres and runs API+UI against it. Sets `SPRING_PROFILES_ACTIVE=local` itself.
- `api/scripts/dev.sh` + `scripts/start-db-tunnel.sh`: opens an SSM tunnel to the real dev RDS instance (needs `.envrc`/direnv with AWS credentials), then runs the API against that.

Either way, the `local` Spring profile must be active for `application-local.yml`
to take effect (it isn't picked up automatically — there's no `.idea`/`.vscode`
run config committed to this repo). `application-local.yml` is what makes AI
features work locally with the deterministic stub provider and disables Cognito
auth in favor of permit-all (`app.security.enabled=false`).

## Architecture

### Data flow and the read-only boundary
Oracle (legacy Kuali, BU VPN-only) → Python ETL, run from a BU VPN-connected
machine, streams directly into Postgres (`archive` schema) → Spring Boot API
(JdbcClient, no writes back to Oracle) → React UI. Oracle is the **only**
supported source of structured data for the Award/Negotiation/Subaward/
Proposal loaders — CSV ingestion for structured data has been retired
entirely (no `SOURCE_MODE`, no `--csv`/`--csv-dir` flags on any loader; see
`docs/DECISIONS.md`). Award's unit contacts and Proposal's people had no
verified Oracle extraction query and have been removed entirely (API, UI,
ETL, and schema — see `docs/DECISIONS.md`); don't reintroduce them without a
verified extraction query. S3 is retained only for document/attachment
binary storage and for the
legacy IRB Excel/Parquet export pipeline, unaffected by this change. The API
and UI never talk to Oracle directly; only the ETL does, and only for
reading. See [`etl/README.md`](etl/README.md) for the unified CLI and the
connectivity check, and `docs/runbooks/` for the day-to-day operator
workflow.

### Migrations are not run by Spring Boot
SQL files in `database/migrations/` use Flyway's `V###__description.sql`
naming convention, but `spring.flyway.enabled: false` in `application.yml` —
Spring's Flyway integration is intentionally disabled. Migrations are instead
applied by the **Python ETL** (`etl/archive_etl/upload/migrations.py`), which
tracks applied versions in `public.schema_migration` and is invoked from the
`load_*` scripts. If you add a migration, it takes effect via the ETL loaders,
not via `mvn spring-boot:run`.

### Hexagonal layout is only fully implemented for IRB
The package layout (`adapter/in/web`, `adapter/out/*`, `application/*`,
`domain/model/*`) suggests ports-and-adapters throughout, but in practice only
the IRB domain has a formal ports/use-case layer
(`application/port/in/IrbQueryUseCase`, `application/port/out/IrbQueryPort`,
`application/service/IrbQueryService`). Every other domain — Award,
Negotiation, Proposal, Subaward — has its controller call a concrete
`*ArchiveService` that calls a `*ArchiveRepository` directly, with no port
interface in between. When adding a new domain, mirror the concrete-service
pattern (the four non-IRB domains), not the IRB ports pattern, unless you're
deliberately extending that pattern everywhere.

A separate "Protocol Archive" domain (a second, independent human-subjects
archive alongside IRB, with its own `archive.protocol_*` tables) was removed
in full — API, UI, ETL loaders/Oracle SQL, and a forward-only schema-removal
migration (`V032__drop_protocol_archive.sql`). IRB was kept as the sole
surviving domain for that data. See [`docs/DECISIONS.md`](docs/DECISIONS.md)
for the history and rationale; don't resurrect the concrete-service pattern
above as "add a Protocol domain back" without reading that first.

Not-found handling is also split: IRB throws a custom
`edu.bu.archive.exception.RecordNotFoundException`; every other domain throws
plain `java.util.NoSuchElementException` from the service layer. Both end up
as 404s via `GlobalExceptionHandler` (an unscoped `@RestControllerAdvice` —
despite living in the same file history as Award, it handles every
controller in the app, not just Award's).

### Research object model and business grain
Proposal is the backbone of the archive — every major object should
eventually connect to it. Conceptual chain: Proposal → Award → Funding →
Negotiation → Investigator (Subaward hangs off Award/Proposal). IRB is a
separate, self-contained domain, not part of this chain.

**Never treat a raw archive row count as a business-object count.** Identify
the business grain (the real-world entity count) and the historical grain
(every archived version/row) separately, and preserve both when they serve
different purposes — do not silently deduplicate valid historical rows. Don't
infer meaning from a table name or a bare `COUNT(*)`; inspect migrations,
schema, and source mappings before deciding the grain. The Award
implementation is the reference/mirror point for new domains (Proposal was
built to mirror it) — when in doubt about how to structure a new domain,
read Award's repository/service/controller first. Before implementing
scoping/aggregation/history behavior for any Award-adjacent feature,
check [`docs/kuali-business-rules/`](docs/kuali-business-rules/README.md)
for a real, source-verified Kuali behavioral rule that already applies —
several plausible-looking scoping assumptions (family-wide vs.
version-scoped queries, which occurrence survives a history collapse)
have been implemented backwards at least once in this project before
being caught against live data.

- **Award**: business grain is `COUNT(DISTINCT award_number)`; `Historical
  Award Records` = every row in `archive.award_version`. Multiple rows may
  legitimately share `award_number` and `sequence_number` when `award_id`
  differs — never delete/merge rows just to make counts align.
- **Proposal**: don't assume `archive.proposal` exists — inspect
  `information_schema`/migrations for the real table or view. Don't use
  `archive.award_funding_proposal` as the Proposal count; use the stable
  institutional Proposal business identifier instead. Don't assign meaning to
  ad-hoc profiling numbers unless the exact SQL and column order are known.
- Dashboard count labels must match their grain exactly: `Awards` = distinct
  `award_number`; `Historical Award Records` = version-row `COUNT(*)`;
  `Funding Relationships`/`Submissions`/`Timeline Events` = IRB-sourced
  relationship/event-row counts (`archive.irb_funding_source`,
  `archive.irb_submission`, `archive.irb_timeline_event`).
- If you ever extract Oracle `PROTOCOL_ID`-linked data again (including for
  IRB), don't assume a child row's `PROTOCOL_ID` identifies its business
  version: the now-removed Protocol Archive investigation found this
  disagreed at material scale (~15% mismatch for Personnel) and required
  resolving the parent from `PROTOCOL_NUMBER` + `SEQUENCE_NUMBER` instead,
  retaining the original `PROTOCOL_ID` only as audit metadata. See
  `docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md` (retained, deprecated) and
  `docs/DECISIONS.md`.

### AI features (Award Summary / Award Questions)
Everything AI-related lives behind `app.ai.*` feature flags that default OFF,
and is architected so a misbehaving or prompt-injected model **cannot**
fabricate facts that reach the user:
- `AiProvider` is an interface with `StubAiProvider` (deterministic, no
  network) and `OpenAiProvider` (real OpenAI Responses API, structured JSON
  output, `store=false`); `AiModelRouter` selects one by
  `app.ai.provider` name.
- `AwardContextBuilder` redacts sensitive-looking patterns
  (`SensitiveFieldRedactor`) and truncates context to
  `app.ai.max-records`/`max-serialized-context-chars` before anything is sent
  to a provider. Fields like `accountNumber`/`sponsorAwardNumber` are simply
  never included in the AI context at all.
- Every citation the model returns is checked against the citations legally
  derivable from the archive context (`AwardCitationValidator`) — a citation
  or support ID the model invents is rejected and the whole request fails
  closed (503), it does not get silently dropped.
- For Award Questions specifically, the model **never writes the user-facing
  answer text**. `AwardQuestionRouter` classifies the question into a fixed
  intent; deterministic intents (current status/sponsor/PI/etc.) are answered
  straight from the database with no model call at all
  (`AwardDeterministicFactResolver`); the remaining intents
  (comparison/history/likely-administrative-changes) have the model pick
  which of a pre-built, citation-backed set of diff sentences
  (`AwardSequenceDiffBuilder`) are relevant — the model selects IDs, it never
  generates prose.
- Providers never get direct database access — they only ever receive the
  already-built, already-redacted `AwardAiContext`. Don't bypass
  `AwardArchiveService`/`AwardContextBuilder` to hand a provider raw
  repository data.

### Auth
Cognito JWT resource server (`SecurityConfiguration`) in real deployments;
`LocalSecurityConfiguration` (permit-all) when `app.security.enabled=false`,
which is how local dev and most controller-level `@WebMvcTest`s run.

## Coding conventions specific to this repo

- Development order for a new feature/domain: DB migration → Oracle
  extraction SQL → ETL (reads directly from Oracle; no CSV export step) →
  Repository → Service → Controller → React UI. Don't skip ahead (e.g. don't
  write a Service against a table that doesn't exist in a migration yet).
- Mirror the Award implementation when building out a new domain rather than
  inventing a new shape.
- Never invent Oracle table/column names — verify against
  `information_schema` or existing extraction SQL first.
- Use `JdbcClient` for repository-layer queries (not Spring Data JPA query
  derivation), consistent with the rest of the codebase.
