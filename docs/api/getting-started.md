# Run the API locally

This tutorial starts the API with local authentication disabled, verifies its
health, and makes a read-only archive request.

## What you need

- Java 21
- Maven 3.9 or newer
- PostgreSQL 17 containing the archive schema and data
- The repository's database migrations applied by the ETL

The API does not create tables. See the [ETL operations guide](../etl/operations.md)
if the database has not been loaded yet.

## Step 1: Run the tests

```bash
cd api
mvn test
```

This compiles the application and runs controller, service, repository,
security, attachment, feature-flag, and AI trust-boundary tests.

## Step 2: Start the API

For the default local Homebrew PostgreSQL setup:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

`application-local.yml` selects the local database, disables Cognito
authentication, uses local attachment fixtures, and selects the deterministic
AI stub. If your PostgreSQL role differs from the operating-system username,
export `POSTGRES_USER` and `POSTGRES_PASSWORD` first.

You can also run the repository-level launcher from the repository root:

```bash
./scripts/run-local.sh
```

That script starts Homebrew PostgreSQL, verifies the API and UI ports, starts
the API with `SPRING_PROFILES_ACTIVE=local`, waits for health, and then starts
the UI.

## Step 3: Verify the running API

```bash
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:8080/api/dashboard
```

The health endpoint should report `UP`. The dashboard response returns counts
at their documented grains, including distinct Award and Proposal business
identifiers alongside historical row counts.

Explore the complete generated contract at:

- `http://localhost:8080/swagger-ui.html`
- `http://localhost:8080/v3/api-docs`

## Step 4: Try a paginated endpoint

```bash
curl --get http://localhost:8080/api/v1/awards/search \
  --data-urlencode 'query=100' \
  --data-urlencode 'page=0' \
  --data-urlencode 'size=20'
```

Page numbers are zero-based. Validation failures return a structured `400`
response rather than an unhandled server error.

## What you built

You now have the same controller, service, repository, and serialization code
used in AWS, with local-only authentication and attachment-storage adapters.
Continue with [operations and testing](operations-testing.md), or use the
[reference](reference.md) to find an endpoint or configuration value.

## Troubleshooting

### The API cannot connect to PostgreSQL

Confirm the server is reachable and the `research_archive` database exists.
The local defaults are `127.0.0.1:5432`, database `research_archive`, and a
username matching the current developer configured in `application-local.yml`.

### Tables or columns are missing

Run the ETL migration path. Spring Flyway is intentionally disabled, so
restarting the API will not repair schema drift.

### Requests return 401 locally

The local profile is not active. Restart with
`-Dspring-boot.run.profiles=local` or `SPRING_PROFILES_ACTIVE=local`.

