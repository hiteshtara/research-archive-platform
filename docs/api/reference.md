# API endpoint and configuration reference

The generated OpenAPI document at `/v3/api-docs` is the source for request
and response schemas. This reference inventories the route families and
runtime configuration that determine which routes are available.

## Access rules

| Path | Default non-local access |
| --- | --- |
| `/actuator/health`, `/actuator/info` | Public |
| `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` | Public |
| `/api/**` | Valid Cognito access token required |
| Any other path | Denied |

The `local` profile sets `app.security.enabled=false` and permits all
requests. Never use that setting in a deployed environment.

## Endpoint families

### Dashboard and cross-domain search

| Method and path | Purpose |
| --- | --- |
| `GET /api/dashboard` | Dashboard counts at explicitly defined business and historical grains. |
| `GET /api/global-search?query=` | Fan-out search across archived domains; query length 2-200. |
| `GET /api/investigators?email=` | Investigator profile and linked studies. |

### Award current API

Base path: `/api/v1/awards`.

| Routes | Purpose |
| --- | --- |
| `GET /search`, `/by-number/{awardNumber}` | Search and stable-identifier resolution. |
| `GET /{awardId}/summary`, `/versions`, `/hierarchy` | Summary, historical versions, and Award family hierarchy. |
| `GET /{awardId}/people`, `/unit-details`, `/unit-contacts`, `/sponsor-contacts`, `/central-administration-contacts` | People and contacts. |
| `GET /{awardId}/funding-proposals`, `/amounts`, `/terms`, `/comments`, `/sap-transmissions` | Funding, financial history, terms, comments, and SAP transmission history. |
| `GET /{awardId}/time-and-money/summary`, `/actions`, `/history` | Time-and-money views. |
| `GET /{awardId}/time-and-money/transactions/{pendingTransactionId}` | One transaction with details. |
| `GET /{awardId}/time-and-money/documents/{timeAndMoneyDocumentNumber}` | One time-and-money document. |
| `GET /{awardId}/budget/summary`, `/versions`, `/periods`, `/line-items`, `/personnel` | Budget views. |
| `GET /{awardId}/attachments` | Paginated attachment metadata. |
| `GET /{awardId}/attachments/{attachmentId}/download` | Authorized streaming download. |

Legacy Award routes remain under `/api/awards` for families, workspace,
history, people, amounts, proposals, and funding. New work should use the
`/api/v1/awards` contract unless compatibility requires the legacy shape.

### Proposal

Current routes under `/api/v1/proposals/{proposalId}` provide summary,
versions, people, units, attachments, attachment download, comments, and
funded Awards. Legacy `/api/proposals` routes provide families and
proposal-number-based workspace, history, and Award links.

### Negotiation

`GET /api/negotiations` searches Negotiations. Routes under
`/api/negotiations/{negotiationId}` provide workspace, activities,
custom-data, notifications, and unassociated details.

### Subaward

`GET /api/subawards` searches Subawards. Routes under
`/api/subawards/{subawardId}` provide workspace, amounts, contacts,
custom-data, funding, attachments, authorized download, template info,
closeout, reports, notepad, and notifications.

### Legacy IRB

Routes under `/api/irb` provide search, record/study lookup, families,
history, and record workspace. This is the legacy compatibility path and is
not the template for new domains.

### Archive Explorer

Base path: `/api/v1/explorer`. The entire controller exists only when
`APP_EXPLORER_ENABLED=true`. It exposes fixed read-only lookups for Awards,
Award versions, workflow documents, units, unit administrators, Award
contacts, persons, rolodex entries, sponsors, and attachment metadata. It
does not accept arbitrary SQL.

### AI

| Method and path | Required flags |
| --- | --- |
| `POST /api/ai/awards/{awardNumber}/summary` | `APP_AI_ENABLED=true` |
| `POST /api/ai/awards/{awardNumber}/questions` | AI enabled and `APP_AI_QUESTIONS_ENABLED=true` |

The summary request must have no body. The questions request contains a
validated `question` field. Both associate work with the authenticated JWT
subject, or `local-dev` when security is explicitly disabled.

## Pagination

Most paginated routes accept zero-based `page` and bounded `size` query
parameters and return `PageResponse<T>`. Exact defaults and bounds are in the
controller/OpenAPI contract because some legacy routes differ.

## Error responses

General errors have this shape:

```json
{
  "timestamp": "2026-08-04T12:00:00Z",
  "status": 404,
  "error": "Not Found",
  "code": "NOT_FOUND",
  "message": "Record was not found",
  "path": "/api/example",
  "correlationId": "00000000-0000-0000-0000-000000000000"
}
```

Constraint violations and illegal arguments return `400`; missing records
return `404`. AI provider/execution failures normally return `503` with a
smaller AI-specific error body.

## Runtime configuration

### Database and server

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `POSTGRES_HOST` | Required non-local | PostgreSQL host. |
| `POSTGRES_PORT` | `5432` | PostgreSQL port. |
| `POSTGRES_DB` | `research_archive` | Database name. |
| `POSTGRES_USER` | Required non-local | Database username. |
| `POSTGRES_PASSWORD` | Required non-local | Database password. |

The Hikari pool uses maximum 10 connections, minimum 2 idle, and a 30-second
connection timeout. Hibernate schema generation and Flyway are disabled.

### Authentication and browser access

| Variable | Default | Purpose |
| --- | --- | --- |
| `COGNITO_ISSUER_URI` | Required non-local | Expected JWT issuer and discovery location. |
| `COGNITO_CLIENT_ID` | Required non-local | Required access-token `client_id`. |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated exact UI origins. |

### Feature and storage configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `APP_EXPLORER_ENABLED` | `false` | Registers the Explorer controller. |
| `APP_ATTACHMENTS_STORAGE` | `s3` | `s3` or `local` storage adapter. |
| `APP_ATTACHMENTS_LOCAL_DIRECTORY` | `local-data/attachments` | Local fixture directory. |
| `APP_ATTACHMENTS_LOCAL_BUCKET` | `local-fixtures` | Required local bucket sentinel. |
| `ARCHIVE_DOCUMENTS_BUCKET` | Required for S3 storage | Private attachment bucket read by S3 adapters. |
| `AWS_REGION` | AWS SDK resolution | Region for the S3 client. |

### AI configuration

| Variable | Default |
| --- | --- |
| `APP_AI_ENABLED` / legacy `AI_ENABLED` | `false` |
| `APP_AI_STUB_ENABLED` / legacy `AI_STUB_ENABLED` | `false` |
| `APP_AI_OPENAI_ENABLED` | `false` |
| `APP_AI_QUESTIONS_ENABLED` | `false` |
| `APP_AI_PROVIDER` / legacy `AI_PROVIDER` | Empty |
| `APP_AI_OPENAI_MODEL` | `gpt-5-mini` |
| `APP_AI_OPENAI_BASE_URL` | `https://api.openai.com/v1` |
| `APP_AI_OPENAI_TIMEOUT_SECONDS` | `60` |
| `APP_AI_OPENAI_CONNECT_TIMEOUT_SECONDS` | `10` |
| `APP_AI_PROMPT_VERSION` | `award-summary-v2` |
| `APP_AI_QUESTION_PROMPT_VERSION` | `award-question-v1` |
| `APP_AI_CACHE_ENABLED` | `false` |
| `APP_AI_CACHE_MAX_ENTRIES` | `250` |
| `AI_MAX_RECORDS` | `100` |
| `AI_MAX_CONTEXT_CHARS` | `20000` |
| `OPENAI_API_KEY` | Required only for the enabled OpenAI provider |

## Related

- [Getting started](getting-started.md)
- [Operations and testing](operations-testing.md)
- [Architecture and security](architecture-security.md)
- [AI architecture](../AI_ARCHITECTURE.md)
- [Archive Explorer](../ARCHIVE_EXPLORER.md)

