# Archive Explorer

## Status

**Phase 1 (this document): implemented, live-verified against dev
data.** A read-only, command-line-only tool. Phase 2 (authenticated
Spring Boot endpoints + a React `/explorer` page) is deliberately NOT
built yet - see "Phase 2 (proposed, not built)" below. Do not build it
without a separate go-ahead.

## Purpose

Gives immediate visibility into the already-archived PostgreSQL data -
particularly Unit/UnitAdministrator/Person/Rolodex and the Award
Contacts derivation - without needing direct database access (no
bastion/VPN path to dev Postgres exists yet; see the BU-VPN-routing
investigation referenced in project history). Built on the exact same
path already proven throughout this project: Mac -> AWS CLI -> ECS
one-off loader task -> private PostgreSQL -> CloudWatch output.

## Usage

```
scripts/run-archive-explorer.sh <resource> [resource flags...] [--output table|json]
```

Examples:

```
scripts/run-archive-explorer.sh award --award-number 100012-00002
scripts/run-archive-explorer.sh unit --unit-number 1203250000
scripts/run-archive-explorer.sh unit --unit-number 1203250000 --output json
scripts/run-archive-explorer.sh workflow --document-number 328797
scripts/run-archive-explorer.sh award-contacts --award-id 1135067
```

Runs through the existing `research-archive-platform-dev-loader` ECS
task family - same image, same task role, same PostgreSQL secret, same
VPC networking `scripts/run-award-loader.sh` already uses. Never touches
Oracle - the explorer only queries already-archived PostgreSQL data.

Locally (no ECS), the same commands work directly against
`POSTGRES_*`/`ORACLE_*` environment variables exactly like every other
ETL entry point:

```
uv run python -m archive_etl explore unit --unit-number 1203250000
```

## Resources

| Resource | Identifier flag | Returns |
|---|---|---|
| `award` | `--award-number` | Current-version summary (award_id, sequence, workflow document number, lead unit, status) |
| `award-version` | `--award-id` | One specific archived version by its surrogate key |
| `workflow` | `--document-number` | Every archived version with this workflow document number (across all Awards, not just current) |
| `unit` | `--unit-number` | Unit Details (name, parent unit, organization) + its Unit Administrators |
| `unit-administrators` | `--unit-number` | Just the administrators list |
| `award-contacts` | `--award-id` | Key Personnel / Unit Contacts / Sponsor Contacts / Central Administration Contacts, all four sections |
| `person` | `--person-id` | Name/email/phone for one archived person |
| `rolodex` | `--rolodex-id` | One external Rolodex contact card |
| `sponsor` | `--award-id` | Just the Sponsor Contacts section |
| `attachments` | `--award-id` | Attachment metadata for an Award |

Every resource is a **fixed, predefined SQL query with bound
parameters** (`etl/archive_etl/explorer.py`) - there is no arbitrary-SQL
code path anywhere in this tool, by design. Identifiers are validated
against a strict allow-list pattern before ever being bound as a
parameter (rejects anything that isn't a plausible award
number/unit number/person ID/etc., independent of the SQL layer's own
parameterization). List-shaped results are capped at 50 rows.

### Central Administration Contacts - the proven Kuali rule

`award-contacts`' Central Administration Contacts section reproduces
`Award.initCentralAdminContacts()` exactly (see
`docs/architecture/AWARD_CONTACTS_DESIGN.md` for the full Java trace):
the Award's `lead_unit_number` joined to `unit_administrator` joined to
`unit_administrator_type`, filtered to `default_group_flag = 'C'`. Never
a guessed or approximated rule, and never every unit associated with an
Award - only the single lead unit, matching the real derivation exactly.

## Safety rules (enforced)

- Read-only PostgreSQL connection only - the ECS task never receives
  `ORACLE_SECRET_ID` for `explore` commands.
- No arbitrary SQL - every resource is a fixed query in
  `etl/archive_etl/explorer.py`.
- AWS account is verified (`770203350335`) before anything runs, both
  in the shell script and inside the Python entry point.
- Every command logs the resource name and the identifier value only
  (structured JSON logging, matching every other ETL entry point) -
  never a secret, credential, or storage key.
- Table output and JSON output are both supported (`--output
  table|json`).

## Phase 2 (proposed, not built)

Once the Phase 1 queries are proven stable against live dev data (this
phase's own verification - see the project's rollout notes), the same
fixed-query logic can be reused, unmodified in spirit, by:

- Authenticated, dev-only Spring Boot endpoints, e.g.
  `GET /api/v1/explorer/awards/{awardNumber}`,
  `GET /api/v1/explorer/units/{unitNumber}`,
  `GET /api/v1/explorer/workflows/{documentNumber}`,
  `GET /api/v1/explorer/awards/{awardId}/contacts` - gated behind a
  configuration flag (e.g. `APP_EXPLORER_ENABLED=true`, disabled
  outside dev at first) and kept separate from the public archive
  contracts.
- A React `/explorer` route: a simple resource-type + identifier search
  screen, with the result page showing related objects as links (Award
  -> version history / workflow document / lead unit / unit contacts /
  sponsor contacts / central administration contacts / attachments).

This is a proposal only - do not build it until it is separately
authorized.
