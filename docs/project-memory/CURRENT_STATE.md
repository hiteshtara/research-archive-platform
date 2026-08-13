# Current project state

Repository-owned, hand-maintained milestone/state record for
`research-archive-platform`, read verbatim by
`scripts/restore-project-context.sh`. Update this file as milestones
change — do not let `restore-project-context.sh` or any other script
regenerate it automatically; it is meant to hold judgment calls a script
can't infer on its own (what's actually done vs. in progress, why a
commit exists, what to do next).

Keep entries short. Each commit entry should be verifiable independently
(`git log <hash>`, `git branch --contains <hash>`) — this file records
*why* a commit exists and what state it left things in, not a
substitute for `git log`.

## Local commits (not yet pushed, as of 2026-08-13)

| Commit | Summary | Status |
|---|---|---|
| `bb22466` | Award Custom Data API/UI feature (repository, service, controller, DTO, frontend client/type/component, nav entry, tests) | Code complete, tests pass, **not deployed**. Dev RDS already has the underlying data (see `4bc9adb` below) — deploying this commit is what's actually needed next, not another data load. |
| `876e023` | `CLAUDE.md` + `docs/runbooks/LOCAL_SETUP.md`: documents AWS RDS as the authoritative dev database, ECS Fargate as the supported access path, and the missing-bastion-blocks-only-the-local-tunnel distinction | Documentation only, no code behavior change |
| `f7c8028` | Removed `scripts/start-db-tunnel.sh` + `api/scripts/dev.sh` (unsupported local SSM tunnel to dev RDS — no EC2 bastion exists) and updated every active reference to point at the ECS route instead | Documentation/script removal only |
| `4bc9adb` | `docs/architecture/AWARD_CUSTOM_DATA_DESIGN.md`: records verified Oracle staging vs. dev RDS Award Custom Data counts | Documentation only |

To check whether a commit above is contained in the current branch or
exists on the configured remote:

```bash
git branch --contains <hash>          # local branch containment
git log origin/main..<hash> 2>/dev/null && echo "not yet on origin/main"
```

## Verified Award Custom Data facts (as of 2026-08-13)

**These are point-in-time verified facts, not live values.** Re-verify
via `scripts/restore-project-context.sh --aws`/`--oracle` (or the
underlying ECS/Oracle-runner commands directly) before relying on them
for anything beyond historical context — both Oracle staging and dev RDS
can change independently of this file.

- Oracle staging (`stg.db.kualitest.research.bu.edu`, schema `KCOEUS`):
  `AWARD` = 267,386 rows / 40,926 distinct Award numbers.
  `AWARD_CUSTOM_DATA` = 6,328,084 rows.
- Dev RDS (`archive` schema): `award_version` = 267,386 rows / 40,926
  distinct Award numbers (matches staging exactly — repopulated from
  staging 2026-08-12, see `docs/runbooks/UNATTENDED_FARGATE_ETL_LOADS.md`).
  `award_custom_data` = 6,328,064 rows.
- The 20-row Custom Data gap between staging and dev RDS is exactly the
  20 Oracle rows whose `AWARD_ID` has no matching `AWARD` row — excluded
  by dev RDS's foreign-key constraint, not a bug or an incomplete load.
- Award `204713-00117` (the running example used throughout this
  investigation): 7 versions (`award_id` 2673287, 2750879, 2805868,
  2900501, 2932424, 3108429, 3160098), 260 `award_custom_data` rows,
  already present in dev RDS.
- An ECS Fargate one-off task (`--load-award-id 3160098 --dry-run`,
  cluster `research-archive-platform-dev-etl`, task definition
  `research-archive-platform-dev-loader:192`) proved all 48 Award child
  datasets are already synchronized between Oracle staging and dev RDS
  for this family: every table reported `inserted=0 updated=0`, and
  pre/post `archive.award_custom_data` row counts on dev RDS were
  byte-for-byte identical (rollback proof).
- **Conclusion: no Award Custom Data (or broader Award) load is required
  for this family.** What's missing is deploying `bb22466`'s API/UI code.

## Open items

- `bb22466` has not been pushed or deployed.
- The reconciliation above covers Award `204713-00117` specifically, not
  a full-population diff — the underlying counts (267,386/40,926/
  6,328,084) suggest full correspondence, but no full key-level diff
  across every Award has been run.
