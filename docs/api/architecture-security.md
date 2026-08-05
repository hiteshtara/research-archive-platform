# API architecture and security

The API is a read-only delivery layer over an archive-owned PostgreSQL
database and private attachment storage. Its main safety property is a hard
boundary: only the ETL can read Kuali Oracle or populate the archive; the API
can only query preserved data and stream approved objects.

## Request flow

```text
Browser
   |
   | Cognito access token
   v
Spring Security
   | signature + issuer + token_use + client_id
   v
Controller
   | validation and HTTP mapping
   v
Application service
   | business selection and ownership checks
   v
JdbcClient repository --------> archive PostgreSQL (SELECT)
   |
   +--> attachment storage ---> local fixture or private S3 (GET)
   |
   +--> AI context builder ---> stub or OpenAI provider (optional)
```

## Package responsibilities

- `adapter/in/web` owns HTTP routes, validation, response DTOs, streaming,
  and exception mapping.
- `application` owns use-case orchestration, record selection, attachment
  ownership checks, AI context construction, and deterministic AI answers.
- `adapter/out/persistence` owns SQL queries and attachment-storage adapters.
- `adapter/out/ai` owns the stub and OpenAI provider implementations.
- `domain/model` contains transport-independent domain and AI records.
- `config` selects security, CORS, AWS, attachment, and AI beans.

IRB alone uses formal input/output ports. The other domains use concrete
service/repository classes. New domain work should follow the established
Award-style pattern unless the whole application is deliberately migrated to
ports and adapters.

## Read-only database boundary

Repositories use `JdbcClient` for explicit SQL. Hibernate DDL is `none`,
Flyway is disabled, and normal request paths are queries. The migration owner
is the Python ETL, not Spring Boot.

Read-only is also a deployment responsibility: use a PostgreSQL principal
whose grants cannot mutate archive data. Application conventions alone are
not a substitute for database permissions.

Dashboard and search counts must preserve their business grain. For example,
Awards are counted by distinct `award_number`, while Award historical records
count version rows. A convenient `COUNT(*)` must not silently replace the
domain definition.

## Authentication and authorization

Non-local security is enabled by default. Spring Security permits health,
info, and OpenAPI paths, requires authentication for `/api/**`, and denies
everything else.

The JWT decoder validates:

1. signature using the configured issuer's keys;
2. standard timestamp and issuer claims;
3. `token_use` equals `access`;
4. `client_id` equals the configured Cognito app client.

Cognito scopes become `SCOPE_*` authorities and groups become `ROLE_*`
authorities. Current controllers primarily require authentication rather
than fine-grained role annotations.

The local profile explicitly replaces this chain with permit-all security.
That is convenient for development and dangerous in any shared environment.

## CORS and CSRF

The API is a bearer-token resource server, so CSRF protection is disabled.
CORS applies to `/api/**`, uses an exact allowlist, permits credentials, and
allows GET, POST, PUT, PATCH, DELETE, and OPTIONS with Authorization,
Content-Type, and Accept headers.

The deployed UI origin must be configured explicitly. Do not use wildcard
origins with credentials.

## Attachment security

Attachment list and download routes include both a parent record ID and an
attachment ID. Services verify that the attachment belongs to that parent
before opening storage. This prevents an authenticated user from changing
only the attachment ID to retrieve another record's document.

Storage adapters then fail closed:

- local adapters constrain reads to the configured fixture directory and
  bucket sentinel;
- S3 adapters require matching archive metadata and stream with `GetObject`;
- missing keys, bucket mismatches, and ownership mismatches do not fall back
  to another source;
- controllers sanitize response metadata through Spring's content-disposition
  builder and fall back to `application/octet-stream` for invalid MIME types.

The API task role should have only `ListBucket`/`GetObject` for the documents
bucket. It does not need upload or delete permission.

## Feature gates

Explorer, AI summaries, and AI questions are separate feature gates. A
disabled controller bean has no registered route, which is safer than a route
that checks a flag after accepting the request.

Explorer exposes only predefined repository operations. There is no
arbitrary-SQL endpoint.

## AI trust boundary

The AI provider never receives database access. The flow is:

```text
Award archive records
   -> select allowed fields
   -> redact sensitive-looking values
   -> cap records and serialized characters
   -> provider request
   -> validate every returned citation/support ID
   -> return only validated, archive-backed output
```

For Award questions, deterministic intents bypass the model and read facts
directly from the archive. Other intents let the model select from
pre-generated, citation-backed statements; the model does not author the
user-facing factual prose. Provider, schema, invented-citation, or execution
failures fail closed with `503`.

OpenAI requests use structured output and `store=false`. Logs should contain
metadata and correlation identifiers, never raw secrets or full archive
context.

## Error boundary

General client validation maps to `400`; missing records map to `404`.
Errors include a machine-readable code and per-error correlation ID. AI
controllers have a narrower handler so provider failures do not expose
internal exceptions.

The correlation ID is not propagated from inbound headers and is not a full
distributed trace. Operational tooling should not assume otherwise.

## Trade-offs

### Explicit SQL over ORM-generated queries

Explicit SQL makes archive grain and historical joins reviewable. It costs
more mapping code but avoids hidden ORM behavior in a read-only historical
model.

### Local security profile

Permit-all local mode makes development fast and deterministic. It creates a
sharp configuration boundary, so deployed environments must never activate
the local profile.

### Public OpenAPI

Public Swagger improves integration and support. It also exposes the route
contract to unauthenticated clients. If deployment policy changes, tighten
the security matcher rather than assuming Swagger follows `/api/**` rules.

### Mixed architectural styles

Keeping the current concrete service pattern avoids a broad refactor. The
cost is inconsistency with IRB's formal ports. Documentation and code review
must prevent contributors from inferring that every domain follows IRB.

## Related

- [Endpoint and configuration reference](reference.md)
- [Operations and testing](operations-testing.md)
- [AI architecture](../AI_ARCHITECTURE.md)
- [Attachment architecture](../ATTACHMENT_ARCHITECTURE.md)
- [Database schema](../architecture/DATABASE_SCHEMA.md)

