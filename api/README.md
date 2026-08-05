# Research Archive API

Spring Boot 3.5 / Java 21 read-only API for the Research Archive Platform.
It serves archived Award, Proposal, Negotiation, Subaward, and legacy IRB
data from PostgreSQL; streams approved attachments from local fixtures or
private S3; and optionally provides citation-checked Award AI features.

The API never connects to Kuali Oracle and does not run database migrations.

## Documentation

- [Getting started](../docs/api/getting-started.md) - run the API locally and
  make the first request.
- [Operations and testing](../docs/api/operations-testing.md) - test, build,
  troubleshoot, and verify a deployment.
- [Endpoint and configuration reference](../docs/api/reference.md) - route
  families, feature flags, environment variables, errors, and health checks.
- [Architecture and security](../docs/api/architecture-security.md) - request
  flow, read-only boundary, Cognito, attachment authorization, and AI trust
  boundaries.

## Quick commands

```bash
cd api
mvn test
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

With the local profile active:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/dashboard
```

OpenAPI JSON is available at `http://localhost:8080/v3/api-docs` and Swagger
UI at `http://localhost:8080/swagger-ui.html`.

