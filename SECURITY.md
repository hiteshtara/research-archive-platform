# Security

## Core guarantee: read-only, end to end

This platform is a historical archive, not a system of record. It never
writes back to Oracle (the legacy source system) — the only path into
Oracle is the Python ETL running from a BU VPN-connected machine, and that
path is read-only extraction. The Spring Boot API and React UI have no
write path to Oracle at all, and the AI feature described below has no
write path to anything.

## Authentication and access

- All `/api/**` endpoints require a valid Cognito-issued JWT
  (`SecurityConfiguration`), validated against issuer, `client_id`, and
  `token_use` claims.
- Local development runs with security disabled
  (`app.security.enabled=false`, `LocalSecurityConfiguration`, permit-all) —
  this must never be the case in a deployed environment; it is gated behind
  an explicit property with no default that enables it.
- **Known limitation**: authorization today is "is this a valid
  authenticated user," not per-record ownership — any authenticated user in
  the Cognito pool can query any Award/Protocol/Proposal/etc. This matches
  the archive's intended access model (authorized BU staff can look up any
  historical record) but is worth knowing if that assumption ever changes.

## AI features (Award Summary / Award Questions)

The optional AI layer is designed so a misbehaving or prompt-injected model
cannot fabricate facts that reach the user, and cannot exfiltrate sensitive
data:

- Disabled by default (`app.ai.enabled=false` in production unless
  explicitly turned on) and gated by independent flags per capability.
- Providers never receive direct database access — only an already-built,
  already-redacted context. Fields like account numbers and sponsor award
  numbers are never included in that context at all.
- Text fields are passed through pattern-based redaction
  (`SensitiveFieldRedactor`: emails, phone-like numbers, credentials, JDBC
  URLs, AWS access keys, SigV4 signature parameters) before ever reaching a
  provider.
- Every citation and, for the Q&A feature, every model-selected support ID
  is validated against what was actually supplied — an invented citation or
  ID is rejected and the request fails closed (503), not silently dropped.
  For Award Questions specifically, the model never writes the user-facing
  answer text; it only selects among pre-built, citation-backed sentences.
- Structured logs record only safe operational metadata (correlation ID,
  JWT subject, provider/model, duration, token counts, category) — never
  prompts, archive context, model responses, or credentials.
- **Known limitation**: there is currently no rate limiting on the AI
  endpoints. A valid token can call them repeatedly, which is a cost/abuse
  exposure worth addressing before scaling usage.

## Secrets and configuration

- The OpenAI API key and database credentials are injected via AWS Secrets
  Manager / ECS task definition, never committed to the repository.
- Local development credentials are loaded through `.envrc` (direnv,
  gitignored) from AWS Secrets Manager — not hardcoded.

## Reporting a vulnerability

This is an internal Boston University platform. If you find a security
issue, do not open a public GitHub issue — contact the repository
maintainers directly, or route through BU Information Security's standard
vulnerability-reporting channel.
