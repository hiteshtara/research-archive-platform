# How to operate and test the API

Use this guide to run focused tests, build an artifact or container, verify a
deployment, and diagnose common failures.

## Prerequisites

- Java 21 and Maven 3.9+
- A compatible archive PostgreSQL schema
- Cognito issuer/client configuration outside the local profile
- AWS credentials only when directly testing S3-backed attachments

## How to run tests

Run the complete suite:

```bash
cd api
mvn test
```

Run a class or one method:

```bash
mvn test -Dtest=AwardV1ControllerTest
mvn test -Dtest=AwardV1ControllerTest#searchReturnsResults
```

The suite uses several test shapes:

- controller tests verify HTTP status, validation, serialization, and route
  contracts;
- service tests verify business selection and not-found behavior;
- repository tests verify SQL mapping and business grain;
- security tests verify ownership checks and feature-gated routes;
- AI tests verify redaction, citation validation, deterministic answers,
  prompt hashes, and provider failure handling;
- storage tests verify local paths and S3 error mapping.

## How to build the application

Build the executable JAR:

```bash
mvn clean package
java -jar target/research-archive-api-0.0.1-SNAPSHOT.jar
```

Build the production container from `api/`:

```bash
docker build -t research-archive-api:local .
```

The runtime image uses Java 21 and runs as the non-root `archive` user with
UID 10001.

## How to run against dev RDS

**Removed 2026-08-13:** `api/scripts/dev.sh` and the SSM database tunnel
it depended on (`scripts/start-db-tunnel.sh`). This project has no EC2
bastion, so the tunnel could never actually be opened. There is no
supported direct Mac-to-dev-RDS connection for the API — use
`scripts/run-local.sh` for local Postgres, or an ECS Fargate one-off task
for dev RDS investigation/ETL (see `CLAUDE.md`'s "Authoritative data
location" section).

## How to verify a deployed API

1. Check load-balancer health:

   ```bash
   curl --fail https://API_HOST/actuator/health
   ```

2. Confirm unauthenticated archive access is denied:

   ```bash
   curl -i https://API_HOST/api/dashboard
   ```

   Expect `401` without a bearer token.

3. Call an endpoint with a Cognito access token:

   ```bash
   curl --fail \
     -H "Authorization: Bearer $ACCESS_TOKEN" \
     https://API_HOST/api/dashboard
   ```

4. Verify Swagger/OpenAPI remain publicly reachable only if that is the
   intended deployment policy:

   ```bash
   curl --fail https://API_HOST/v3/api-docs
   ```

5. Verify attachment metadata and one authorized download. The URL parent ID
   and attachment ID must belong to the same record.

6. If Explorer or AI is enabled, verify both the enabled route and its
   disabled-by-default behavior in another environment.

## How to diagnose errors

Normal API errors include a generated `correlationId` with timestamp, status,
machine-readable `code`, message, and request path. Use the correlation ID in
support reports, but note that it is generated for the error response and is
not yet a propagated distributed trace ID.

AI failures use their own scoped handler. Provider and execution failures
return `503 Service Unavailable` without exposing provider internals.

### Database connection failures

Check `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, username/password
secret injection, security groups, and the JDBC pool timeout. The API will
not apply missing migrations.

### Cognito tokens are rejected

The API accepts Cognito access tokens, not ID tokens. It validates signature,
issuer, `token_use=access`, and `client_id`. Confirm the token and API use the
same pool and app client.

### Browser CORS failures

Set `APP_CORS_ALLOWED_ORIGINS` to the exact deployed UI origins. The fallback
only includes `http://localhost:5173`. CORS permits credentials and the common
HTTP methods but does not accept arbitrary origins.

### Attachment download failures

Confirm the selected storage adapter, documents bucket configuration, ECS
task-role `s3:GetObject`, archived metadata row, parent-record ownership, S3
key, and object existence. A missing or mismatched object fails closed.

### AI endpoint is missing

The summary controller requires `APP_AI_ENABLED=true`. Questions additionally
require `APP_AI_QUESTIONS_ENABLED=true`. A live OpenAI provider also requires
the provider/openai flags and injected `OPENAI_API_KEY`.

## Verification checklist

- `mvn test` passes.
- `/actuator/health` reports `UP`.
- unauthenticated `/api/**` requests fail outside local mode.
- the expected Cognito access token succeeds.
- database queries return the expected business grain.
- downloads enforce parent/attachment ownership.
- disabled feature routes are not registered.
- logs contain no passwords, tokens, attachment contents, or AI context.

