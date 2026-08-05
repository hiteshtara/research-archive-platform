# Getting started with repository scripts

This tutorial brings up the local application and, optionally, adds synthetic
Subaward attachments. It does not use AWS or Oracle.

## Prerequisites

On the currently supported macOS development setup, install PostgreSQL 17,
Java/Maven, Node/npm, Python 3, `curl`, and `lsof`. Configure the local database
and `ui/.env.local` first; see [`docs/runbooks/LOCAL_SETUP.md`](../runbooks/LOCAL_SETUP.md).

Run commands from the repository root unless a command says otherwise.

## Run the application locally

Check that PostgreSQL is available under the values hard-coded by the local
runner: database `research_archive`, user `mukadder`, host `127.0.0.1`, and port
`5432`. Then run:

```bash
scripts/run-local.sh
```

The script:

1. starts the Homebrew `postgresql@17` service;
2. verifies the database and `ui/.env.local`;
3. rejects occupied ports `8080` and `5173`;
4. runs the API with the `local` Spring profile;
5. waits for `/actuator/health`; and
6. starts the Vite UI.

Open <http://localhost:5173>. Press Ctrl-C to stop both child processes.
`run-local.sh` deliberately clears AI-related environment variables, so it is
not the right entry point for testing an external AI provider.

## Add the local attachment demo

With the local database running, execute:

```bash
scripts/setup-local.sh
```

Override connection routing if needed:

```bash
POSTGRES_HOST=127.0.0.1 \
POSTGRES_PORT=5432 \
POSTGRES_USER="$USER" \
POSTGRES_DB=research_archive \
scripts/setup-local.sh
```

This generates placeholder files through
`tools/generate-local-attachment-fixtures.py`, applies
`scripts/seed-local-subaward-attachments.sql`, and verifies four synthetic rows.
The seed is idempotent, but it is strictly local-development data and must never
be applied to test or production.

Open <http://localhost:5173/subawards/94204> to inspect the demo states,
including an archived file, an unarchived record, and a deliberately missing
file.

## Next steps

- Use the [operations guide](operations.md) for AWS, authentication, ETL, and export tasks.
- Consult the [reference](reference.md) before automating a script.
- Read [architecture and safety](architecture-safety.md) before any cloud-mutating workflow.
