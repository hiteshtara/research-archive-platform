# Architecture Decision Changelog

This document records important architectural changes, why they were made, their impact, and the alternatives considered.

---

## 2026-07-28 — Added Provider-Neutral AI Foundation

### Decision

Introduce a provider-neutral AI layer using an `AiProvider` interface and a deterministic stub provider.

### Reason

The application should support Kimi, OpenAI, Anthropic, Gemini, or a future BU-hosted model without coupling archive services to one vendor.

### Impact

- AI providers are isolated behind a common interface.
- Archive services remain provider-independent.
- Tests can run without external network calls.
- Future providers can be added without changing the public API.

### Alternatives Considered

- Directly integrate one provider into the Award service.
- Use provider-specific SDKs throughout the application.
- Delay the provider abstraction until a live model is selected.

### Status

Accepted and implemented.

---

## 2026-07-28 — Preserved the Read-Only AI Boundary

### Decision

AI providers receive only approved, application-generated context and never receive direct database access.

### Reason

The Research Archive Platform is a read-only system containing research-administration data. A model must not receive SQL execution capability, database credentials, repositories, datasources, or unrestricted archive rows.

### Impact

- Spring Boot remains responsible for record retrieval.
- SQL remains static and parameterized.
- AI cannot modify archive or source-system data.
- Context builders define the data boundary.

### Alternatives Considered

- Allow the model to generate SQL.
- Allow an agent to connect directly to PostgreSQL.
- Send full database records to the provider.

### Status

Accepted and implemented.

---

## 2026-07-28 — Used Award Number as the Public AI Identifier

### Decision

Use the business-level Award number in the AI summary endpoint.

```http
POST /api/ai/awards/{awardNumber}/summary
