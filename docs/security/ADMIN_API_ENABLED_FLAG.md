# Proposed: `admin-api-enabled`, independent of record enforcement

**Design only. Not implemented, not enabled, deliberately not in the same
PR as the grant CLI.**

## The problem

`AdminAuthorizationController.requireEnforcementEnabled()` 404s the whole
`/api/v1/admin` tree whenever `app.authorization.enforcement-enabled` is
false. Its stated reason:

> 404, not 403: while enforcement is off the surface genuinely does not
> exist, and changes made through it would have no effect.

**The second half is no longer true.** A grant created while enforcement
is off takes effect the moment enforcement is switched on. Pre-provisioning
is exactly the safe order, and the gate currently prevents it — which is
why an operator CLI had to be written instead.

## Proposal

`app.authorization.admin-api-enabled`, default **false**, read separately
from `enforcement-enabled`. `requireEnforcementEnabled()` becomes
`requireAdminApiAvailable()` and consults the new flag only.

Four states, all meaningful:

| enforcement | admin API | Meaning |
|---|---|---|
| off | off | today — no restrictions, no admin surface |
| off | **on** | **the gap this fills**: provision grants before enabling |
| on | on | steady state |
| on | off | enforcement without an admin surface; break-glass |

## What still protects the API

Nothing in the chain below depends on record enforcement:

1. **Authentication.** `SecurityConfiguration` is a Cognito JWT resource
   server; `/api/**` requires a valid token. Unchanged.
2. **ARCHIVE_ADMIN authorization.** Every method resolves the caller to an
   `InstitutionalIdentifier` and calls `requireAdministrator`, which needs
   an ACTIVE `admin_role_member` row reached through an ACTIVE
   `identity_link`. The controller enforces nothing itself — the check is
   in `ArchiveAdminService`, deliberately, so a new endpoint cannot forget
   it.
3. **Audit.** Every change writes its row in the same transaction as the
   change. `archive_api` holds INSERT and nothing else on `access_audit`,
   and `tr_access_audit_append_only` blocks UPDATE and DELETE.

So enabling the flag exposes a surface that is authenticated,
administrator-gated and fully audited. It does **not** weaken record
enforcement: separate flags, separate code paths.

## The honest cost

Today, changing who can see research data requires **AWS credentials and
the ability to run an ECS task**. With the flag on it requires **an
administrator's Cognito session**.

That is a genuine reduction in difficulty, and it is the whole decision.
Duo-backed BU federation is a strong authenticator, and the alternative —
operators running ECS tasks for every grant — does not scale and tempts
people into broader AWS access. But it should be approved on those terms,
not as a side effect of activation.

Mitigations worth considering with it: a short-lived enablement window
rather than a standing `true`; alerting on `ACCESS_GRANTED` rows; and
SEC-008's denied-request auditing, which would make probing the admin API
visible.

## Not in this proposal

Changing what the admin API can do, who may call it, or how grants are
evaluated. Only *when the surface exists* changes.
