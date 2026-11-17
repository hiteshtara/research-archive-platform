# IRB API release exclusion (`app.irb.api.enabled`)

## What and why

IRB is out of release scope (owner decision). The IRB-only HTTP endpoints have
no record-level authorization, so with record enforcement on they would
expose archive-wide IRB data to any signed-in user. The release configuration
therefore does not register them at all.

| Property | Env var | Default |
|---|---|---|
| `app.irb.api.enabled` | `APP_IRB_API_ENABLED` | `true` (and `matchIfMissing = true`) |

With the property `false`, these controllers are not created, so their routes
have no handler mapping and answer 404:

| Controller | Routes |
|---|---|
| `IrbController` | `GET /api/irb`, `/api/irb/record/{recordId}`, `/api/irb/{studyId}` |
| `IrbArchiveController` | `GET /api/irb/families`, `/api/irb/history`, `/api/irb/history/{protocolId}` |
| `IrbWorkspaceController` | `GET /api/irb/record/{recordId}/workspace` |
| `InvestigatorController` | `GET /api/investigators?email=` |

### Why `/api/investigators` is included

It was included only after checking that it is IRB-only and has no consumer:

- **What it reads.** `InvestigatorRepository` reads `archive.irb_protocol_version`
  and `archive.v_global_search`. `v_global_search` (V009/V010, never redefined)
  is built only from IRB tables, and every row carries the constant
  `module = 'IRB'`. It returns no Award, Proposal, Negotiation or Subaward rows.
- **Callers.** Nothing in `ui/`, `api/`, `etl/`, `scripts/` or `ops/` calls it.
  The UI route `/investigators/:email` and its API client call were removed in
  `dc431b97` (2026-08-12, "remove IRB from current product scope"), and
  `dashboardPresentation.test.mjs` asserts the route stays removed. The
  endpoint was added in `8b18d1d7` (2026-07-17) for the IRB investigator page.
  It is mentioned only in docs (`docs/api/reference.md`,
  `docs/kuali-business-rules/HISTORICAL_PERSON_PARTICIPATION.md`, where it is
  described as an IRB-only precedent with a known bug).
- **Runtime evidence.** The DEV API log group keeps 14 days and holds no
  request access log lines (no `/api/` path appears at all), so logs cannot
  confirm or rule out external callers. The code evidence above is what the
  decision rests on.

## Status, activation and residual risk

1. **Default preserves DEV.** With the flag absent or `true` (the default),
   every IRB route stays registered and DEV behaves exactly as it does today.
   That remains the case until activation is separately approved.
2. **Activation needs separate approval.** Activation means setting
   `APP_IRB_API_ENABLED=false` (equivalently `app.irb.api.enabled: false`) in
   the release configuration. That change is **not** made here and needs its
   own approval. This PR does not modify any deployed configuration, task
   definition, environment variable or Terraform.
3. **Residual risk: external `/api/investigators` callers.** Such callers
   cannot be ruled out. DEV keeps no historical request-path logs: the API log
   group has 14-day retention and no access logging, so no `/api/` path appears
   in it. The inclusion rests on code evidence only (above).
4. **IRB is a release-scope exclusion, not a passed authorization test.** IRB
   still has no record-level authorization. Removing its routes from the release
   takes it out of scope; it does not show that IRB access is authorized
   correctly. Security traceability should record IRB as "excluded from release
   scope", never as "authorization verified".

## What is not affected

- **Global Search** reads IRB through `GlobalSearchRepository`, not the IRB
  controllers. Its IRB leg is governed by record enforcement
  (`GlobalSearchService.irbExcludedFromSharedSearch()`, decision D9), not by
  this flag.
- **Dashboard** counts IRB through `DashboardService`'s own SQL; its IRB
  figures are likewise governed by enforcement (D9), not this flag.
- **Document search** keeps its own `module=IRB` handling.
- IRB services, repositories, tables, migrations and ETL are unchanged. Only
  the four controllers are conditional.

## Tests

- `IrbApiReleaseExclusionIntegrationTest` (full context, Testcontainers,
  flag `false`): no handler mapping under `/api/irb` or `/api/investigators`
  (scans the live `RequestMappingHandlerMapping`, so a new IRB route without the
  flag fails); every IRB route returns 404; Awards, Proposals, Negotiations,
  Subawards, Documents, Dashboard and Global Search still have handlers and
  answer 200, as does the PR #159 related-record path
  (`/api/v1/awards/{awardId}/funding-proposals`); Dashboard and Global Search
  still return the seeded IRB record.
- `IrbApiDefaultRegistrationIntegrationTest` (full context, flag absent): the
  property resolves to `true` and the exact IRB route set is registered and
  answers.
- `IrbApiFeatureFlagTest` (no database): controllers present when the flag is
  absent or `true`, absent when `false`.
