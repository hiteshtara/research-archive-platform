# Migrations held back pending approval

**Files here are NOT part of any release and cannot be applied by any
automated process.**

The ETL migration runner (`etl/archive_etl/upload/migrations.py`) discovers
migrations by globbing **`database/migrations/`** and applies **every** pending
version it finds. It has no target-version parameter. That means:

- `--migrate-only` applies everything, not a chosen version;
- `load_subawards_from_csv.py` — the **nightly Subaward sync** — calls
  `apply_migrations` unconditionally on every run;
- so does every other loader.

A migration sitting in `database/migrations/` is therefore an instruction to
apply it the next time any loader runs from an image containing it. There is no
"apply V088 but not V089" in that design.

Holding a file here keeps it out of that glob. It is the only mechanism
available that does not require changing the runner.

## What is here

| File | Held for | Released when |
|---|---|---|
| `V089__archive_activity_audit.sql` | SEC-008 activity audit | retention period, failure policies, privacy defaults and the `GRANT INSERT` are all approved |

## Moving one back

`git mv` it into `database/migrations/`, in its own commit, in its own PR, with
the approval recorded in the commit message. Then confirm the loader image used
to apply it is a descendant of that commit:

```bash
git merge-base --is-ancestor <migration-commit> <loader-image-commit>
```

**Do not copy the file.** Two copies with the same version number is worse than
either placement.

## This is enforced, not just described

`MigrationReleaseScopeTest` asserts that `database/migrations/` contains no
version above the release ceiling, and that every file here is absent from it.
Moving a file back without raising that ceiling fails the build.
