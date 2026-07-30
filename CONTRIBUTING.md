# Contributing

This document covers the development workflow and conventions for the
Research Archive Platform. Read [`CLAUDE.md`](CLAUDE.md) first for
architecture and commands — this file covers *how* to work in this repo, not
*what* it looks like.

## Before you start

- Read [`CLAUDE.md`](CLAUDE.md) for architecture, and
  [`PROJECT_MEMORY.md`](PROJECT_MEMORY.md) for validated data-grain rules and
  past incident lessons. A surprising number of past mistakes in this repo
  came from skipping this step.
- Inspect the existing implementation for the domain closest to what you're
  building (Award is the reference implementation most other domains were
  built to mirror) before writing new code. Never invent a package name,
  Oracle table/column name, or architectural pattern — verify against
  `information_schema`, existing extraction SQL, or the existing package
  layout first.

## Development order

Features in this codebase span layers, and they must be built **in this
order**, not skipped or built out of sequence:

1. **Database migration** (`database/migrations/V###__description.sql`)
2. **Oracle extraction SQL** (verify columns exist before writing it — never
   guess). Oracle is the only supported source of structured data — there
   is no CSV export step; see `docs/DECISIONS.md`.
3. **ETL** (`etl/`) — reads directly from Oracle, loads into PostgreSQL,
   and applies pending migrations via `public.schema_migration`
4. **Repository** (`api/.../adapter/out/persistence`)
5. **Service** (`api/.../application/<domain>`)
6. **Controller** (`api/.../adapter/in/web`)
7. **React UI** (`ui/`)

A Service written against a table that doesn't exist yet in a migration, or
a Controller built before its Service, is a sign the order was skipped.

## Coding conventions

- **Mirror the Award implementation** when building a new domain rather than
  inventing a new shape — controller → concrete `*ArchiveService` →
  `*ArchiveRepository` directly, with `JdbcClient` (not Spring Data JPA query
  derivation). IRB is the one domain with a formal ports/use-case layer; that
  is a historical exception, not the pattern to copy.
- **Never invent Oracle table or column names.** Inspect
  `information_schema`, migrations, or existing extraction SQL first.
- **Business grain vs. historical grain**: never treat a raw archive row
  count as a business-object count without checking which one you actually
  need. See `CLAUDE.md`'s grain rules for Award/Proposal before writing any
  dashboard, API, or reconciliation logic that counts rows.
- **AI features** (`app.ai.*`): never give a provider direct database
  access, never let a provider's output reach the user without citation
  validation, and default every new AI feature flag to `false`. See
  `CLAUDE.md`'s AI architecture section.

## Before committing

- Run the relevant test suite(s) — see [`README.md`](README.md#testing) for
  the full command list. At minimum: `cd api && mvn test` for backend
  changes, `cd ui && npm run build` for frontend changes.
- Compile/build cleanly before committing; don't leave a broken build for
  the next commit to fix.
- Keep one logical change per commit.
- For anything touching counts, grain, or reconciliation logic, be prepared
  to show the exact SQL/logic and test results alongside the change.
- Don't push or open a PR until the change compiles and its tests pass
  locally.

## Tests

New behavior should come with tests at the layer it's introduced —
`*ServiceTest`/`*RepositoryTest`/`*ControllerTest` for backend logic,
`*.test.mjs` for frontend presentation helpers. There is no
component-render test setup on the frontend today (no
`@testing-library/react`); don't assume one exists when writing UI tests.
