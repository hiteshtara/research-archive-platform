# Record authorization: implementation status

**Status (stages 1–3): implemented, OFF by default.** With `app.authorization.enforcement-enabled=false` (the default, and every deployed environment), behaviour is unchanged: "record authorization not enforced". Any authenticated user may read any record, and attachments still need the `ArchiveAttachmentViewer` group (that gate is replaced by parent-record authorization only when enforcement is on). Turning enforcement on requires the policy strategies to be configured (no defaults) and is a separate decision. A local synthetic demonstration is in `scripts/authz-demo/README.md`.

**Enrollment (design 12.2 Option A, section 13): implemented, OFF by default** (`app.authorization.enrollment.enabled=false`). It runs only while enforcement is also on. The rollout procedure, with configuration and infrastructure templates, is `docs/runbooks/RECORD_AUTHORIZATION_ROLLOUT.md`.

| Label | Meaning |
|---|---|
| Fixture-based authorization verified | The identity and policy core is exercised against synthetic identities and records only |
| Real BU federation and identity mapping | **NOT VERIFIED.** Awaiting BU IAM: a stable NameID for a Cognito SP; the exact content and reassignment policy of the identity attribute; the authoritative binding to the Kuali principal id |

The design and decisions are maintained privately (authorization design rev 3.6a; requirements IDs 1–7). This file records what the code does.

## Approved policy decisions (Hitesh, 2026-10-01)

| Decision | Effect in code |
|---|---|
| **Department access follows the record's lead unit** (design 4.2) | UNIT grants match `lead_unit_number`; whether sub-units count is still P6 (see below) |
| **IO grants use the Award account number** (decision D-A) | `AwardAccountNumberIoResolver` and `AwardAccountNumberIoSql` match `TRIM(award_version.account_number)` exactly. They apply to Awards only, never through relationships |
| **Record access includes all content of that record** (replaces P5) | Under enforcement, `AttachmentAuthorizationService` relies on the parent record's authorization. `ArchiveAttachmentViewer` is no longer a separate condition for attachments, reports with attachments or the File Finder. While enforcement is **off**, the group rule stays exactly as before |
| **One record never authorizes another** | Child Awards, other versions, related Proposals, Negotiations and Subawards each need their own authorization (unchanged) |
| **Research Staff via verified KIM principal** (design section 13) | `app.authorization.contact-derivation=VERIFIED_PRINCIPAL`. A verified link's PERSON_ID gives contact access with no grant row, but only while that person is a qualifying contact somewhere. `EXPLICIT_GRANT` keeps the earlier rule. The setting has no default |

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
- **The token `username` claim** is read for one purpose only: enrollment looks that exact profile up (`AdminGetUser`) and then requires the profile's `sub` to equal the token's `sub`. It is never parsed. `ValidatedCognitoIdentity` equality is still issuer + subject only.
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
| `contact-derivation` | **none** | `VERIFIED_PRINCIPAL` (approved) or `EXPLICIT_GRANT` |
| `enrollment.enabled` | `false` | server-side enrollment; runs only while enforcement is on |
| `enrollment.user-pool-id` | **none** | required when enrollment is enabled |
| `enrollment.region` | **none** | required when enrollment is enabled |
| `enrollment.endpoint-override` | none | optional; only for a local lab's simulated user pool |
| `enrollment.saml-provider-name` | **none** | the Cognito identity provider the profile must be federated through |
| `enrollment.identifier-attribute` | **none** | the user-pool attribute carrying the ONE verified value (e.g. `custom:...`) |
| `enrollment.crosswalk-attribute-name` | **none** | `authz.principal_crosswalk.attribute_name` for that value |
| `enrollment.refusal-retry-seconds` | `60` | a refused sign-in is re-tried (and re-audited) after this; `0` = every request |

Enrollment enabled with any required setting missing: **the API refuses to start**, and the message names the missing keys. No AWS credentials are configured. The default provider chain supplies them.

With enforcement on, any missing strategy, a missing identity or any evaluation failure **denies**. It never falls back to unrestricted access.

**Module rules in the evaluator:**
- **Central:** every module.
- **Department:** Award, Proposal, Negotiation by lead unit.
- **Research Staff:** Award and Proposal employee contacts in the configured roles.
- **IO:** Award versions whose account number equals a granted IO value (approved decision D-A).
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

## Enrollment, crosswalk and administration (stage 4)

```
validated access token (iss, sub, username)
  → AdminGetUser(username) on the configured pool; profile sub must equal the token sub
  → profile enabled, and its Cognito "identities" record names the configured SAML provider
  → the ONE configured verified attribute (no email, no login name, ever)
  → exactly one ACTIVE authz.principal_crosswalk row → an existing authz.kim_principal with actv_ind = 'Y'
  → no REVOKED link for this (iss, sub); no other ACTIVE link for this identifier under this issuer
  → authz.identity_link: AUTO_VERIFIED, institutional_identifier = the value, kuali_person_id = PRNCPL_ID,
    login_name NULL, verified_by = 'api-enrollment'
```

| Part | Where | Verified by |
|---|---|---|
| Crosswalk schema (V083): `authz.kim_principal`, `authz.principal_crosswalk`; unique among ACTIVE rows both ways; FK to the principal | `database/migrations/V083__create_authz_kim_crosswalk.sql` | `IdentityEnrollmentIntegrationTest`, `scripts/authz-admin/test_integration.sh` |
| Enrollment service (fail closed, audited) | `IdentityEnrollmentService` (ports `CognitoProfileReader`, `EnrollmentStore`) | `IdentityEnrollmentServiceTest` (every outcome, fake pool) |
| Profile reader (`AdminGetUser`, AWS SDK v2, default credentials) | `adapter/out/cognito/AwsCognitoProfileReader` | `AwsCognitoProfileReaderTest` (mocked client) |
| Store (link + audit in one transaction; per-identifier advisory lock; `ON CONFLICT` on the active `(iss, sub)` index) | `JdbcEnrollmentStore` | `IdentityEnrollmentIntegrationTest` (Testcontainers) |
| Wiring: runs once per request, before the identity is resolved, only when enforcement **and** enrollment are on | `RecordAuthorizationService.compute()` | `IdentityEnrollmentServiceTest` |
| Administration CLI | `scripts/authz-admin/authz_admin.py` (README there) | `test_authz_admin.py`, `test_validate_crosswalk.py`, `test_integration.sh` |

**Behaviour:**

- **Every refusal links nothing.** The request stays `ACCESS_NOT_PROVISIONED`, or `ACCESS_DENIED` for a previously revoked profile.
- **A Cognito or database failure fails closed.**
- **A linked principal with no grant row** sees exactly the records where they are a qualifying contact (`VERIFIED_PRINCIPAL`). A KIM principal alone grants nothing.
- **Re-validation.** On every request, an ACTIVE `AUTO_VERIFIED` link is re-checked with one indexed query: its crosswalk row must still be ACTIVE and its principal still active. Otherwise the link is revoked (`revoked_by = 'api-enrollment'`) and the request denied. A revoked profile is never re-linked automatically. `ADMIN_VERIFIED` links are not re-checked against the crosswalk.
- **Refusals are remembered per API instance** for `refusal-retry-seconds`. This bounds `AdminGetUser` calls and audit rows for a user who keeps retrying.
- **Audit** (`authz.access_audit`, actor `api-enrollment`). Actions are `ENROLLMENT_LINKED`, `ENROLLMENT_ALREADY_LINKED`, `ENROLLMENT_REFUSED`, `ENROLLMENT_FAILED` and `ENROLLMENT_LINK_REVOKED`. `detail->>'outcome'` holds the exact code: `REFUSED_UNKNOWN_PERSON`, `REFUSED_AMBIGUOUS_MAPPING`, `REFUSED_NOT_A_KIM_PRINCIPAL`, `REFUSED_INACTIVE_PRINCIPAL`, `REFUSED_PREVIOUSLY_REVOKED`, `REFUSED_IDENTIFIER_LINKED_TO_ANOTHER_PROFILE`, `REFUSED_SUBJECT_MISMATCH`, `REFUSED_NOT_FEDERATED`, `REFUSED_MISSING_IDENTIFIER`, `REFUSED_PROFILE_NOT_FOUND`, `REFUSED_PROFILE_DISABLED`, `REFUSED_NO_USERNAME`, `FAILED_PROFILE_READ`, `FAILED` or `REVOKED_MAPPING_NO_LONGER_VALID`. The detail holds identifiers that already live in authz tables (issuer, subject, principal id), never email, names or the token username. An attribute value that matches no crosswalk row is not recorded.
- **Required IAM permission:** `cognito-idp:AdminGetUser` on the one user pool, for the API task role. **NOT applied** to any environment. The template is in the rollout runbook.

**Known limits:**

- An existing link is not re-read from Cognito on each request, so a changed IdP attribute is handled by revoking the link or crosswalk row.
- With the unique crosswalk indexes, an ambiguous or non-principal crosswalk row can't be stored at all. The service still refuses both defensively.

Synthetic identity fixtures live only under `src/test`. There is no mock-login endpoint, trusted identity header, magic username or access fallback in any deployable path.

## Enforcement paths (stages 2–3)

With enforcement on, every `/api` request passes `RecordAuthorizationInterceptor` before the controller runs. Unprovisioned or denied identities are refused, and Central users may use every path. Other ("restricted") users may use only the paths below. For Central users, and with enforcement off, every response is unchanged: each filter below applies only when `RecordVisibility.unrestricted()` is false.

Rules that apply to every scoped path:

- **A relationship never authorizes.** A related, child or other-version row is shown only if it is separately visible.
- **Invisible rows are omitted.** They leave no stub and no hidden count, and paging is computed after filtering.
- **Out of scope looks like missing.** An out-of-scope record returns 404, the same as a record that does not exist.

### Scoped for restricted users

| Path | How it is enforced |
|---|---|
| `/api/v1/awards/search`, `/api/v1/awards/versions/search`, `/api/proposals/search` | scope predicate in the same SQL WHERE as the filters (page **and** count) |
| `/api/global-search` | Award and Proposal branches scoped (the access outcome is propagated to worker threads); other modules and semantic search not run |
| `/api/dashboard` | counts from the same scope predicates; other modules 0 |
| `/api/v1/awards/by-number/{n}` | current version checked before any query |
| `/api/v1/awards/{n}/hierarchy` | requested Award checked; out-of-scope nodes omitted; re-rooted if an ancestor is hidden |
| `/api/v1/awards/{id}` + **explicit sub-path allow-list** | `requireAward(id)` before any query; **any other sub-path → 403** (a new Award endpoint is closed until reviewed) |
| `…/summary` | root/parent award numbers blanked unless `canSeeAwardNumber` |
| `…/versions` | versions filtered by `canSeeAward` |
| `…/people`, `/unit-details`, `/unit-contacts`, `/sponsor-contacts`, `/central-administration-contacts`, `/terms`, `/custom-data` | version-scoped by query (`award_id`) |
| `…/amounts`, `…/time-and-money/history` | rows filtered to visible `award_id`s |
| `…/time-and-money/summary` | family-wide fields (`familyTransactionCount`, last family action) **null** unless every version of the family is visible |
| `…/time-and-money/actions` | **empty** unless every version of the family is visible (rows carry no version key; see POLICY P3 below) |
| `…/time-and-money/transactions/{n}` | 404 unless every T&M document it names has the root of `{id}`'s hierarchy family (`archive.award_hierarchy`) **and** every source, destination and detail award number is visible (closes a proven cross-award read) |
| `…/time-and-money/documents/{x}` | 404 unless its `root_award_number` is the root of `{id}`'s family; the root number is blanked unless visible |
| `…/comments` | per-version comments on invisible versions dropped; notepad (family level) only when every version is visible, and never `restricted_view` entries |
| `…/sap-transmissions` | child rows with an invisible award number dropped; `sentData`/`returnedData` (whole-hierarchy XML) null unless every child is visible |
| `…/budget/summary\|versions\|periods\|line-items\|personnel` | budgets owned by invisible versions dropped **before** the archive budget is selected (the selected budget can differ from Central's) |
| `…/funding-proposals` | Proposal must be visible **and** the link's own Award version must be visible |
| `…/funding-subawards`, `…/negotiations` | empty (no non-Central rule for those modules yet) |
| `…/attachments`, `…/attachments/{n}/download` | record check. The download also refuses an attachment that belongs to another record. No separate group (approved decision) |
| `…/report.pdf` | allowed for an in-scope Award: built from the same scoped service methods |
| `…/report-with-attachments.pdf` | as `report.pdf`; its attachment list covers this `award_id` only |
| `/api/v1/attachments/search` (Archived File Finder) | **Award rows only**, with the Award scope predicate in the SQL WHERE (page and count). `recordType=ALL` returns the caller's Award rows; `PROPOSAL` and `NEGOTIATION` return an empty page |
| `/api/v1/explorer/awards?awardNumber=` | `requireAwardNumber` (the response is that current version only) |
| `/api/v1/explorer/award-versions?awardId=` | `requireAward`; exactly one well-formed parameter value, else 404 |
| `/api/ai/awards/{n}/summary\|questions\|evidence-search` | `requireAwardNumber` (else 404), **and** every version of the family must be visible, else `403 AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS` (see POLICY P3 below) |
| `/api/v1/proposals/{id}/**`, `/api/proposals/{n}[/history\|/awards]` | record checked before any query; Proposal → Award lists filtered |
| `/api/v1/me/access` | access mode and grant kinds only (no identifiers) |

"Every version of the family is visible" is computed per `award_id` with the same rule as `canSeeAward` (`RecordVisibility.canSeeEveryAwardVersion`). It is never assumed from the version-scope setting.

### Still closed (NOT IMPLEMENTED for restricted users)

Every other path returns `403 NOT_AVAILABLE_UNDER_RECORD_AUTHORIZATION` to non-Central users. This includes:

- Negotiation, Subaward and IRB (all paths);
- Document Explorer `/api/v1/documents` and `/api/documents/search`;
- the other Explorer paths: workflows, units, unit administrators, award contacts, persons, rolodex, sponsors, attachments, proposals;
- legacy `/api/awards/**`;
- any Award sub-path not on the allow-list.

Proposal and Negotiation rows in the Archived File Finder are omitted for restricted users. A Proposal scope predicate exists but is not yet wired into `AttachmentSearchRepository`.

### Policy-dependent omissions

These follow from the unapproved policy choices. Revisit them when the policy is approved.

| Policy | Omission |
|---|---|
| **P3** (version scope) | Under `PER_VERSION`, a restricted user who cannot see every version of a family gets **no** T&M actions, **no** notepad, **null** family T&M totals, and **no AI** (`AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS`), because none of these carry a version key. Under `FAMILY_WIDE` every version of a permitted family is visible, so they appear. |
| **P4** (research-staff roles) | Which contacts make a version visible decides every per-version filter above. |
| **P6** (department match) | The same: the unit rule decides which versions, root/parent numbers, transmission children and T&M nodes are visible. |
| — | Budget selection for a restricted user ignores budgets owned by invisible versions, so it may differ from Central's selection. |
| — | T&M transactions are refused if **any** node they name is invisible (stricter than the source/destination pair). |
| — | `restricted_view` notepad entries are never shown to non-Central users. |

Verified by `RecordAuthorizationEnforcementIntegrationTest`: the full application on Testcontainers with the synthetic seed. It covers every persona, direct URLs, counts and pages, attachments, related lists, hierarchy, dashboard, Global Search, every section fix above (including the cross-award T&M read), reports, the File Finder, Explorer, AI, closed paths, revocation, and agreement between the SQL scope and the per-record checks. The allow-list is unit-tested in `RecordAuthorizationInterceptorTest`.

## Not implemented yet

| Scope | Notes |
|---|---|
| Non-Central rules for Negotiation, Subaward, IRB | proposal P8 / decision D-G |
| Document Explorer, other Explorer paths, legacy `/api/awards` | closed for non-Central users |
| Proposal / Negotiation rows in the File Finder | omitted for non-Central users |
| Partial-family AI and T&M actions | closed by design under P3 (see above) |
| Real federation, the real verified attribute, and a real crosswalk extract | enrollment code is ready but OFF; awaiting the identity provider's answers and an approved read-only extract |
| Cognito SAML provider, attribute mapping, IAM `AdminGetUser` | templates only (rollout runbook); **not applied** |
| Grant-administration UI | the CLI (`scripts/authz-admin`) is the only administration path |

## Migration numbering

`V082` (identity, grants, audit) and `V083` (KIM principal crosswalk). Both are additive and create empty tables. Apply them through the ETL `--migrate-only` path and verify them **before** deploying API code that reads them (rollout runbook, section 1). `V081` is reserved for the Award amount-dates work on a separate branch. If V082 lands first, the migration runner logs a harmless gap warning until V081 arrives; it applies migrations by version regardless of order.
