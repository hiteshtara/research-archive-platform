# The administrative API becomes reachable when enforcement is enabled

Written for the project's security reviewers.

Recorded 2026-11-11, before the 12 November DEV enforcement window, because
this is a side effect of enabling enforcement rather than anything the window
adds deliberately.

## The coupling

`AdminAuthorizationController` gates its whole tree on
`requireEnforcementEnabled()`, which reads
`app.authorization.enforcement-enabled` — the **same** flag that turns on
record-level enforcement. While that flag is false every endpoint answers 404:

> 404, not 403: while enforcement is off the surface genuinely does not
> exist, and changes made through it would have no effect.

A separate `app.authorization.admin-api-enabled` flag was designed to decouple
the two (`ADMIN_API_ENABLED_FLAG.md`) but is **not implemented**. So there is
no way to enable record enforcement without also exposing the administrative
API, and no new environment variable is involved: the six approved
`APP_AUTHORIZATION_*` variables on revision 92 are sufficient to expose it.

## What becomes reachable

Twelve endpoints under `/api/v1/admin` — four reads and eight writes:

| Method | Path | Kind |
|---|---|---|
| GET | `/identities` | read |
| GET | `/identities/{identifier}` | read |
| GET | `/audit` | read |
| GET | `/administrators` | read |
| POST | `/identities/{identifier}/links` | **write** |
| DELETE | `/identities/{identifier}/links/{linkId}` | **write** |
| PUT | `/identities/{identifier}/suspension` | **write** |
| POST | `/identities/{identifier}/grants` | **write** |
| PATCH | `/identities/{identifier}/grants/{grantId}` | **write** |
| DELETE | `/identities/{identifier}/grants/{grantId}` | **write** |
| POST | `/administrators` | **write** |
| DELETE | `/administrators/{identifier}` | **write** |

There is deliberately **no** bootstrap endpoint; the first administrator can
only be created by the operator CLI. `AdminAuthorizationControllerTest`
asserts its absence.

## What protects them

1. **Authentication.** `SecurityConfiguration` is a Cognito JWT resource
   server; `/api/**` requires a valid token.
2. **Administrator membership.** Every one of the twelve calls `actor()`,
   whose first statement is `requireEnforcementEnabled()` and which then
   resolves the caller's institutional identity, throwing
   `NotAnArchiveAdministratorException` (403 `NOT_AN_ARCHIVE_ADMINISTRATOR`)
   when it cannot. The service layer then calls `requireAdministrator(actor)`
   independently.
3. **Administrator confers no data access.** Being an ARCHIVE_ADMIN grants no
   record scope: an administrator holding no `access_grant` row is denied
   records like anyone else.
4. **Refusals are not audited as actions.** A refused request leaves no audit
   row, so the audit log cannot be filled by unauthorized probing.

## Are the authorization checks covered by existing tests?

**Yes, with one gap, all in `AdminAuthorizationControllerTest`:**

| Check | Test | Coverage |
|---|---|---|
| Ordinary authenticated user refused 403 | `anAuthenticatedOrdinaryUserGets403OnEveryOneOfTheTwelveEndpoints` | **all 12 explicitly** |
| Caller whose identity cannot be resolved | `anUnresolvableCallerIsAlsoRefusedWith403` | yes |
| Holds the admin role but has no identity link | `holdingTheAdminRoleWithoutALinkIsStillRefused` | yes |
| Admin recognised with no record policy configured | `anAdministratorIsStillRecognisedWhenNoRecordPolicyIsConfigured` | yes |
| Non-admin refused with no record policy configured | `aNonAdministratorIsStillRefusedWhenNoRecordPolicyIsConfigured` | yes |
| A record policy does not make anyone an admin | `aFullyConfiguredRecordPolicyDoesNotMakeAnyoneAnAdministrator` | yes |
| Admin with no grant gets no record access | `anAdministratorHoldingNoGrantIsNotGivenRecordAccessByTheAdminApi` | yes |
| No administrators before bootstrap | `thereAreNoAdministratorsAtAllBeforeOneIsBootstrapped` | yes |
| No bootstrap endpoint exists | `thereIsNoBootstrapEndpoint` | yes |
| Refused request writes no audit row | asserted inside the 403 test | yes |
| Last administrator cannot be revoked | `revokingTheLastAdministratorIs409` | yes |
| Enforcement off ⇒ 404 | `everyAdministrativeEndpointIs404` | **5 of 12 explicitly** |
| Enforcement off ⇒ nothing written | `nothingIsWrittenWhileTheSurfaceIsAbsent` | grants only |

**The gap.** The enforcement-off 404 test exercises 5 of the 12 endpoints
(`GET /administrators`, `GET /identities`, `GET /identities/{id}`,
`GET /audit`, `POST .../grants`). The other seven are protected *by
construction* — every method calls `actor()`, and `requireEnforcementEnabled()`
is its first statement, verified by reading all twelve method bodies — but that
is not proven by a test. The parallel 403 test had exactly this gap once
before and was widened after review; its own comment records that the
parameterised list "covered 9 of the 12 endpoints". The same widening is worth
doing for the 404 case.

This is a test-coverage gap, not a known defect: no endpoint is reachable
while enforcement is off.

## Position for the 12 November window

The window needs exactly one of these endpoints —
`GET /api/v1/admin/identities/{identifier}` — to verify the authenticated
administrator and the grant association from the server's own point of view,
which is stronger than inferring identity from an HTTP status.

**No administrative write endpoint will be called**, with the single exception
of the separately approved revocation of grant 2
(`DELETE /identities/{identifier}/grants/{grantId}`) at the end of the window.
Widening live use of this surface requires separate approval.
