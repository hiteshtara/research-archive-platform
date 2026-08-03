# Archive Explorer

## Status

**Phase 1 and Phase 2 are both implemented and live-verified against dev
data.**

- **Phase 1**: a read-only, command-line-only tool
  (`scripts/run-archive-explorer.sh` / `python -m archive_etl explore`).
  Runs as a one-off ECS task against already-archived PostgreSQL, no
  Oracle access. Remains the maintenance/admin tool of record.
- **Phase 2**: authenticated `GET /api/v1/explorer/**` endpoints in the
  existing Spring Boot API, plus a dev-only React `/explorer` page.
  Reuses the same repository/service queries Phase 1 proved out (no
  duplicated SQL), runs in-process against the same PostgreSQL
  connection as every other API request - **never** a per-request
  Fargate task. Gated behind `app.explorer.enabled`
  (`APP_EXPLORER_ENABLED`), default `false` everywhere; enabled in dev
  only via a direct ECS task-definition environment variable (see
  "Enabling in dev" below - this flag is not currently threaded through
  `additional_api_environment_variables` in Terraform, matching how
  `APP_AI_*` is also managed out-of-band for this environment).

Do not enable `APP_EXPLORER_ENABLED`/`VITE_EXPLORER_ENABLED` in test or
prod without a separate go-ahead.

## Phase 1: CLI

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

## Phase 2: web API

### Architecture

```
React (/explorer) -> Spring Boot REST (ExplorerController)
                   -> ExplorerService
                   -> AwardArchiveRepository / AwardArchiveService / AwardContactService
                   -> PostgreSQL
```

`ExplorerController` is a normal `@RestController`, conditionally
registered with `@ConditionalOnProperty(name = "app.explorer.enabled",
havingValue = "true")` - when the flag is off, the whole bean (every
route) never registers, so unmatched requests 404 rather than returning
a "disabled" body. It sits behind the same
`.requestMatchers("/api/**").authenticated()` rule every other `/api/**`
route already uses (`SecurityConfiguration`) - no bespoke auth wiring.

`ExplorerService` deliberately reuses already-proven methods wherever an
equivalent exists (`AwardArchiveService.findPeople`/`findAttachments`,
`AwardContactService`'s four contact-section methods) rather than
duplicating their SQL - it only adds new repository methods for lookups
that don't yet exist standalone (Unit/Person/Rolodex/Award by their own
identifier).

### Endpoints

| Endpoint | Query param | Returns |
|---|---|---|
| `GET /api/v1/explorer/awards` | `awardNumber` | Current-version Award summary |
| `GET /api/v1/explorer/award-versions` | `awardId` | One specific archived version by surrogate key |
| `GET /api/v1/explorer/workflows` | `documentNumber` | The archived version carrying this workflow document number |
| `GET /api/v1/explorer/units` | `unitNumber` | Unit Details + its Unit Administrators (all of them, not filtered to any group) |
| `GET /api/v1/explorer/unit-administrators` | `unitNumber` | Just the administrators list |
| `GET /api/v1/explorer/award-contacts` | `awardId` | Key Personnel / Unit Details / Unit Contacts / Sponsor Contacts / Central Administration Contacts |
| `GET /api/v1/explorer/persons` | `personId` | Name/email/phone for one archived person |
| `GET /api/v1/explorer/rolodex` | `rolodexId` | One external Rolodex contact card |
| `GET /api/v1/explorer/sponsors` | `sponsorCode` | Current-version Awards carrying this sponsor_code |
| `GET /api/v1/explorer/attachments` | `awardId` | Attachment metadata (capped at 50 rows) |

All identifiers are validated (`@NotBlank`/`@Positive`) before any query
runs; a missing record is a plain 404 (`NoSuchElementException`, handled
by the existing global exception handler), never a 200 with an empty
body. There is no arbitrary-SQL code path anywhere in
`ExplorerController`/`ExplorerService`/the Explorer-specific repository
methods - every one is a fixed, predefined query.

**`/sponsors` is Award-backed, not Rolodex-backed.** `sponsor_code` has
no corresponding column on `archive.rolodex` (confirmed against
`information_schema` after an initial, wrong assumption threw `column
"sponsor_code" does not exist` against the real dev database) -
`sponsor_code`/`sponsor_name` are denormalized directly onto
`archive.award_version` instead, so this endpoint queries Awards by
sponsor code, distinct from an Award's own Sponsor Contacts (already
covered by `/award-contacts`' `sponsorContacts` field and the public
`/api/v1/awards/{awardId}/sponsor-contacts` endpoint).

### DTOs

A dedicated package, `edu.bu.archive.adapter.in.web.dto.explorer`,
independent of the public v1 Award API's own DTOs so either can evolve
without the other - except `ExplorerAwardContactsResponse`, which
reuses the exact same `AwardPersonDetailResponse`/
`AwardUnitDetailsResponse`/`AwardUnitContactResponse`/
`AwardSponsorContactResponse`/`AwardCentralAdministrationContactResponse`
records the public Award Contacts endpoints already use, rather than
duplicating their shape.

`ExplorerPersonResponse`/`ExplorerRolodexResponse` are read-only
reference views - no credentials, no internal database IDs beyond the
identifier used to look the record up, no sensitive metadata.

### Enabling in dev

The API's environment variable is currently set directly on the ECS
task definition (the same out-of-band mechanism `APP_AI_*` uses in this
environment, not via Terraform's `additional_api_environment_variables`,
which is unused here):

```bash
aws ecs describe-task-definition \
  --task-definition research-archive-platform-dev-api \
  --region us-east-1 --query taskDefinition > taskdef.json
# add {"name": "APP_EXPLORER_ENABLED", "value": "true"} to
# containerDefinitions[0].environment, then:
aws ecs register-task-definition --cli-input-json file://taskdef-updated.json --region us-east-1
aws ecs update-service --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api \
  --region us-east-1
aws ecs wait services-stable --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api --region us-east-1
```

The UI's matching flag **is** Terraform-managed (`module.amplify.ui`'s
`environment_variables` in `terraform/environments/dev/main.tf`):

```
VITE_EXPLORER_ENABLED = "true"
```

Changing it requires a new Amplify build to take effect (Vite bakes
`import.meta.env.VITE_*` values in at build time) - either push a new
commit to the connected branch, or trigger a manual Amplify build.

## Phase 2: web UI

`/explorer` (hidden from navigation, and the route itself redirects
home, unless `VITE_EXPLORER_ENABLED === "true"`):

- A resource dropdown (the same 10 resources as the table above) and an
  identifier field whose label changes per resource
  (`ui/src/features/explorer/explorerPresentation.mjs`'s
  `RESOURCE_DEFINITIONS` is the single source of truth for this).
- Results render as either a **Structured** view (summary key/value
  grid for single-object resources, a table for list-shaped resources -
  Unit Administrators/Sponsor/Attachments - and both for resources that
  nest a list inside a single object, like Unit's administrators or
  Award Contacts' four sections) or a **JSON** view (the raw response,
  pretty-printed).
- **Copy identifier** copies the current identifier to the clipboard.
- **Download CSV** appears next to every table and downloads exactly
  what's rendered.
- **Related** chips cross-link to another Explorer lookup: Award/Award
  Version -> Workflow / Unit / Award Contacts / Attachments; Unit ->
  Unit Administrators (and each administrator -> Person); Award
  Contacts -> Unit and every contact row -> Person; Sponsor (a list of
  Awards) -> the same links as Award, prefixed per row; Attachments ->
  the owning Award. Clicking a chip re-runs the search for that
  resource/identifier (state lives in the URL's `?resource=&identifier=`
  query params, so a result is shareable/back-button-friendly).

The presentation logic (`RESOURCE_DEFINITIONS`, `toCsv`,
`buildCrossLinks`) is plain, dependency-free JS
(`explorerPresentation.mjs`) covered by `node:test` unit tests, matching
this project's existing convention for presentation-helper logic (no
component-render test setup exists).

A "Development" chip and a "Dev"-badged nav item ("Archive Explorer",
`TravelExploreOutlined` icon) mark it as a developer tool distinct from
the rest of the archive's read-only research-facing pages.

## Verification fixtures

Live-verified against dev data:

- Award `100012-00002` / `awardId` `985585`
- Unit `1203250000`
- Workflow `328797`
- Award `100068-00001` / `awardId` `1833767` (attachments)
- Rolodex `1`

## Bugs found and fixed during Phase 2 live verification

Both were invisible to the existing Mockito-based repository tests
(which stub the query result and never execute real SQL) - only a real
round-trip against dev Postgres surfaced them:

1. **`unit-administrators`**: the SQL selected
   `ua.unit_administrator_type_code` with no alias, but the DTO field is
   `administratorTypeCode` (no `unit_` prefix) - `SimplePropertyRowMapper`
   couldn't bind it, throwing `column "administrator_type_code" was not
   found`. Fixed by aliasing the column.
2. **`/sponsors`**: originally queried `archive.rolodex.sponsor_code`,
   which doesn't exist - see "`/sponsors` is Award-backed" above.

## Safety rules (enforced, both phases)

- Read-only PostgreSQL connection only.
- No arbitrary SQL anywhere in either phase.
- AWS account is verified before any Phase 1 CLI run; Phase 2 relies on
  the API's existing Cognito-backed authentication, no bespoke rule.
- Row limits (attachments capped at 50) and input validation
  (`@NotBlank`/`@Positive`) on every Phase 2 endpoint.
- Never returns secrets, database credentials, S3 keys, raw attachment
  content, or sensitive internal metadata.
