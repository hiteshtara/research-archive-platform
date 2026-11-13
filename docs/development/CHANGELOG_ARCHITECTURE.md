# Architecture Decision Changelog

This document records important architectural changes, why they were made, their impact, and the alternatives considered.

---

## 2026-11-13 — DEV RDS deletion protection enabled, with a temporary rollback-guard exception

### Decision

Enabled `deletion_protection` on the DEV RDS instance
`research-archive-platform-dev-postgres` via Terraform (`0 add, 1 change,
0 destroy`), **with `api_image_rollback_guard` left disabled for this
apply only**.

### Reason

The DEV database is not disposable: it is the authoritative dev data of
record, repopulated from Oracle staging, and its identifier is a
near-miss for the `-dev-api`, `-dev-etl` and `-dev-dbtunnel` resources in
the same account. Recovery from a snapshot creates a **new endpoint**, so
an accidental delete would also require repointing `POSTGRES_HOST` and
redeploying — a real outage, not a quick undo.

### The temporary guard exception, and why it was necessary

`api_image_rollback_guard` has been `false` in the gitignored dev tfvars
since ~2026-10-28, originally "only for this reviewed FORWARD
deployment". It was left disabled for this apply as well, deliberately:

- The pin (`api_image_digest = sha256:c9f3bc99…`, revision 84's image
  from 2026-10-06) is **stale** relative to the serving revision 94
  (`sha256:1d440f251c…`).
- With the guard **enabled**, the plan **fails** — verified.
- Correcting the pin to the serving image makes Terraform **replace the
  task definition**, turning the plan into `1 add, 1 change, 1 destroy`.

Only the guard-disabled path produces the approved `0 add, 1 change, 0
destroy`. **No image pin, task definition or ECS service was modified.**

**This exception is not resolved.** The pin remains stale and the guard
remains off. Reconciling both is tracked separately as "Option C" in
`docs/operations/ROLLBACK_GUARD_ANALYSIS.md` and has not been approved.

### Impact

- `DeletionProtection: true`; deleting the DEV database now requires
  clearing the flag first.
- **No downtime.** `status: available` throughout, `PendingModifiedValues`
  empty, **no RDS events in 60 minutes**, and the API task has run
  unchanged since 2026-11-12 19:58 — the database was never restarted.
- DEV API still serving **revision 94**, ALB target healthy, revision 93
  retained for rollback.
- Post-apply verification: **9 of 9 checks passed** — 401 without a
  token, award `212137-00001`, dashboard `awards=40926`/`subawards=3511`,
  Documents `totalElements` 3382 and 491685, Global Search 83 results,
  and Negotiations/Subawards/Proposals returning real rows.

### Alternatives Considered

- **Enable the guard first** — rejected for this apply: it blocks the
  plan until the stale pin is fixed, coupling an unrelated safety change
  to an image reconciliation.
- **Correct the pin and apply together** — rejected: produces a
  task-definition replacement, which should be a deliberate, separately
  reviewed re-sync rather than a side effect.
- **Enable the flag out-of-band with the AWS CLI** — rejected: would
  create console-versus-code drift that the next `terraform apply` would
  revert.

### Status

**Applied.** Terraform-managed, so the setting survives future applies.
The guard exception remains **open** and is the one follow-up this entry
leaves behind.

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
