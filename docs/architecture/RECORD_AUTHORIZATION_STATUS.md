# Record authorization: implementation status

**Status (stages 1–3): implemented, OFF by default.** With `app.authorization.enforcement-enabled=false` (the default, and every deployed environment), behaviour is unchanged: "record authorization not enforced". Any authenticated user may read any record, plus the existing `ArchiveAttachmentViewer` gate. Turning enforcement on requires the policy strategies to be configured (no defaults) and is a separate decision. A local synthetic demonstration is in `scripts/authz-demo/README.md`.

| Label | Meaning |
|---|---|
| Fixture-based authorization verified | The identity and policy core is exercised against synthetic identities and records only |
| Real BU federation and identity mapping | **NOT VERIFIED.** Awaiting BU IAM: a stable NameID for a Cognito SP; the exact content and reassignment policy of the identity attribute; the authoritative binding to the Kuali principal id |

The design and decisions are maintained privately (authorization design rev 3.6a; requirements IDs 1–7). This file records what the code does.

## Identity is separate from permissions

```
validated Cognito access token (issuer + sub)
  → archive identity link (authz.identity_link, written only by a trusted enrollment process)
  → verified institutional identifier (+ Kuali employee PERSON_ID when known)
  → active archive grants (authz.access_grant: CENTRAL | UNIT | IO | CONTACT_DERIVATION)
  → AccessScope → per-record decision
```

- **Never used to identify anyone:** the Cognito username (for a federated user it is generated from the IdP name and NameID), email, display names, or anything the browser sends.
- **The real BU attribute adapter** (`AwaitingIamConfirmationAttributeSource`) resolves nobody until BU IAM confirms the contract.
- **No access:** unmapped, ambiguous, revoked and suspended identities, and mapped people with no active grant, get no record access. The unmapped and no-grant cases are "access not provisioned".
- **Grants** are keyed on the institutional identifier, never on a login name, so a reassigned login name can't inherit grants.

## Unapproved policy choices are explicit strategies

`app.authorization.*` (`AuthorizationProperties`):

| Property | Default | Notes |
|---|---|---|
| `enforcement-enabled` | `false` | off → "record authorization not enforced" |
| `version-scope` | **none** | `PER_VERSION` or `FAMILY_WIDE` (proposal P3) |
| `department-match` | **none** | `EXACT_LEAD_UNIT` or `LEAD_UNIT_WITH_DESCENDANTS` (proposal P6) |
| `research-staff-roles` | **none** | e.g. PI, MPI, COI (proposal P4) |

With enforcement on, any missing strategy, a missing identity or any evaluation failure **denies**. It never falls back to unrestricted access.

**Module rules in the evaluator:**
- **Central:** every module.
- **Department:** Award, Proposal, Negotiation by lead unit.
- **Research Staff:** Award and Proposal employee contacts in the configured roles.
- **IO:** only records whose IO resolver supplies values. The real resolver is `DisabledIoResolver` until the authoritative IO field is confirmed.
- **Subaward and IRB:** central only (proposal P8).
- **Relationships:** a relationship to another record never authorizes it.

## Implemented (stage 1)

| Part | Where | Verified by |
|---|---|---|
| Identity types, resolver, fail-closed outcomes | `application/authorization` | `IdentityResolverTest`, `SyntheticFederationTest` |
| Grant union, expiry, revocation, suspension | `AccessScopeResolver` | `AccessScopeResolverTest` |
| Record evaluator + strategies | `RecordAccessEvaluator` | `RecordAccessEvaluatorTest` (all modules, positive/negative) |
| Enforcement switch | `RecordAuthorizationGate` | `RecordAuthorizationGateTest` |
| "Access not provisioned" body | `AccessNotProvisionedProblem` (**not wired**) | `AccessNotProvisionedProblemTest` |
| Store: `authz` schema (V082) + JDBC readers + unit hierarchy | `adapter/out/persistence/authorization` | `AuthorizationStoreIntegrationTest` (Testcontainers) |
| Production token validator (issuer, client, signature, expiry, access-only) | `SecurityConfiguration.accessTokenValidator` | `AccessTokenValidationTest` (locally generated keys) |

Synthetic identity fixtures live only under `src/test`. There is no mock-login endpoint, trusted identity header, magic username or access fallback in any deployable path.

## Enforcement paths (stages 2–3)

With enforcement on, every `/api` request passes `RecordAuthorizationInterceptor` before the controller runs. Unprovisioned or denied identities are refused, Central users may use every path, and other users may use only these:

| Path | How it is enforced |
|---|---|
| `/api/v1/awards/search`, `/api/v1/awards/versions/search`, `/api/proposals/search` | scope predicate in the same SQL WHERE as the filters (page **and** count) |
| `/api/global-search` | Award/Proposal branches scoped (the access outcome is propagated to worker threads); other modules and semantic search not run |
| `/api/dashboard` | counts from the same scope predicates; other modules 0 |
| `/api/v1/awards/{id}/**`, `/api/v1/awards/by-number/{n}` | record checked by the evaluator before any query; out of scope → 404 |
| `/api/v1/awards/{n}/hierarchy` | requested Award checked; out-of-scope nodes omitted, re-rooted if an ancestor is hidden |
| Award versions, related Proposals, Proposal → Award lists | filtered; related Negotiations and Subawards omitted |
| `/api/v1/proposals/{id}/**`, `/api/proposals/{n}[/history\|/awards]` | record checked before any query |
| attachments | record check **and** `ArchiveAttachmentViewer` |
| `/api/v1/me/access` | access mode and grant kinds only (no identifiers) |

**Every other path is closed to non-Central users** with `403 NOT_AVAILABLE_UNDER_RECORD_AUTHORIZATION`, so unfinished paths cannot leak records. This covers:
- Award report PDFs;
- Negotiation, Subaward and IRB;
- Explorer, Document Explorer and File Finder;
- AI;
- legacy `/api/awards`.

Verified by `RecordAuthorizationEnforcementIntegrationTest`: the full application on Testcontainers with the synthetic seed. Every persona, direct URLs, counts and pages, attachments, related lists, hierarchy, dashboard, Global Search, closed paths, revocation, and agreement between the SQL scope and the per-record checks.

## Not implemented yet

| Scope | Notes |
|---|---|
| Non-Central rules for Negotiation, Subaward, IRB | proposal P8 / decision D-G |
| Award report PDFs for non-Central users | closed until every section is reviewed |
| File Finder, Explorer, Document Explorer, AI | closed for non-Central users |
| Grant-administration UI and workflow | not planned until approved (P2) |
| Real enrollment from BU attributes; real IO resolution | awaiting BU IAM and decision D-A |
| Wiring production `CurrentIdentityProvider` to a populated identity store | depends on enrollment |

## Migration numbering

`V082` (this work). `V081` is reserved for the Award amount-dates work on a separate branch. If V082 lands first, the migration runner logs a harmless gap warning until V081 arrives; it applies migrations by version regardless of order.
