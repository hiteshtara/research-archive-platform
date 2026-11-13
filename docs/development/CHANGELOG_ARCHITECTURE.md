# Architecture Decision Changelog

This document records important architectural changes, why they were made, their impact, and the alternatives considered.

---

## 2026-11-13 — Rollback guard corrected and re-enabled; DEV image pin re-synced (Option C)

### Decision

Merged the rollback-guard correction (PR #146, merge commit `0e2c3c1`)
and applied Option C to DEV: re-synced `api_image_digest` to the serving
image and set `api_image_rollback_guard = true`.

Plan, from merged main, applied exactly as reviewed:

```
Plan: 1 to add, 0 to change, 1 to destroy.
module.api_service.aws_ecs_task_definition.api  ["delete","create"]
  action_reason : replace_because_cannot_update
  revision      : 84 -> 101
  image         : sha256:c9f3bc99… -> sha256:1d440f251c…
ECS service changes : 0
RDS changes         : 0
```

### What PR #146 actually fixed

The guard resolved the "live" image from the **newest ACTIVE revision in
the task-definition family**. That is not what the service is running.
After the CPU capacity experiment the family's newest ACTIVE revision was
100 — an experiment image the service never adopted — so the guard was
comparing the pin against the wrong thing, and any newly registered
revision silently became its reference point.

It now reads `data.aws_ecs_service.api_live[0].task_definition`: the
**serving** revision. Covered by seven `terraform test` cases.

### Why a replacement, and why that is safe

ECS task definitions are immutable; changing the image forces a new
revision, which Terraform expresses as `["delete","create"]`. A
replacement counts in **both** the add and the destroy column — hence
`1 add … 1 destroy` for a single resource.

`skip_destroy = true` makes the destroy half **state-only**: Terraform
released revision 84 from that address and never called
`DeregisterTaskDefinition`. Verified after the apply — revisions 84 and
93–101 are all still `ACTIVE`.

### Impact

- New revision **101** registered, carrying the serving digest, and
  **not serving**. The service remains on **revision 94**.
- **No deployment and no restart.** Same task ARN
  `84d161fae8ed45459663ccf7c4e6d47c`, same `startedAt`
  (2026-11-12 19:58:14), ALB target `10.30.2.111` `healthy` before and
  after. API verified live: 401 on a protected endpoint (not a 500),
  `/actuator/health` `{"status":"UP"}`.
- **Zero RDS changes** and **zero ECS service changes** in the plan;
  nothing outside `module.api_service.aws_ecs_task_definition.api`.
- A subsequent DEV plan with the guard active returns **`No changes.`**
- One more unused ACTIVE revision on the family (101). That cost is real
  but now harmless: with the guard reading the service rather than the
  family, an unused revision can no longer become its reference.

### A correction worth recording

Two earlier reports of this plan gave the totals as `0 add, 0 change, 1
destroy` and `1 add, 1 change, 1 destroy`. Both were mine, and both came
from a script that counted an addition only when a resource's `actions`
array was exactly `["create"]` — skipping the `["delete","create"]`
replacement on the add side. Terraform's own summary was always `1 to
add, 0 to change, 1 to destroy`. The affected-resource count was one
throughout, so no risk assessment changed; the lesson is to quote
Terraform's summary rather than recompute it.

### Status

**Applied and verified.** The temporary rollback-guard exception opened
by the deletion-protection apply is **closed**: the guard is enabled, the
pin matches the serving digest, and the guard is now reading the right
revision.

Note that `terraform.tfvars` is gitignored, so "guard enabled" lives in
operational config rather than version control — the same property that
let the guard sit disabled for roughly two weeks unnoticed. Moving
`api_image_rollback_guard` out of tfvars so its declared default of
`true` applies remains an open suggestion in
`docs/operations/OPTION_C_PIN_RESYNC_PROPOSAL.md`.

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
  task definition**, turning the plan into `1 add, 0 change, 1 destroy`
  (corrected 2026-11-13 — an earlier draft of this entry said `1 add, 1
  change, 1 destroy`, which came from a miscount of mine, not from
  Terraform).

Only the guard-disabled path produces the approved `0 add, 1 change, 0
destroy`. **No image pin, task definition or ECS service was modified.**

**This exception was resolved later the same day** by Option C — see the
entry below. At the time of this apply the pin remained stale and the
guard remained off.

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
The guard exception this entry opened is **closed** — see the Option C
entry below.

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
