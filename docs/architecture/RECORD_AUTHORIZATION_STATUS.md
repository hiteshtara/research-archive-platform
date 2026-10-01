# Record authorization: implementation status

**Status (stages 1–3): implemented, OFF by default.** With `app.authorization.enforcement-enabled=false` (the default, and every deployed environment), behaviour is unchanged: "record authorization not enforced". Any authenticated user may read any record, and attachments still need the `ArchiveAttachmentViewer` group (that gate is replaced by parent-record authorization only when enforcement is on). Turning enforcement on requires the policy strategies to be configured (no defaults) and is a separate decision. A local synthetic demonstration is in `scripts/authz-demo/README.md`.

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
| `/api/v1/awards/search`, `/api/v1/awards/versions/search`, `/api/proposals/search` | scope predicate in the same SQL WHERE as the filters (page **and** count). Award family rows: `rootAwardNumber`/`parentAwardNumber` blanked unless `canSeeAwardNumber` (same rule as `…/summary`); version rows carry no hierarchy numbers |
| `/api/global-search` | Award and Proposal branches scoped (the access outcome is propagated to worker threads); other modules and semantic search not run. Award rows come from the scoped Award search above (no hierarchy numbers in the item payload) |
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
| `/api/ai/awards/{n}/summary\|questions\|evidence-search` | `requireAwardNumber` (else 404), **and** every version of the family must be visible, else `403 AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS` (see POLICY P3 below). Summary/Questions context (`AwardContextBuilder`, fact resolver, diff builder) holds only this Award family's own rows: no related records |
| `…/evidence-search` related excerpts | `RELATED_NEGOTIATION`/`RELATED_SUBAWARD` removed from the searched types (no non-Central rule). `RELATED_PROPOSAL` kept only when the linked Proposal (`canSeeProposalNumber`) **and** the link's own Award version (`canSeeAward`) are visible, looked up from `award_funding_proposal_id`. Unknown types are dropped (fail closed). `topK` is applied **after** filtering |
| `/api/v1/proposals/{id}` + **explicit sub-path allow-list** (`""`, `/versions`, `/people`, `/units`, `/attachments`, `/attachments/{n}/download`, `/comments`, `/funded-awards`, `/custom-data`) | `requireProposal(id)` before any query; **any other sub-path → 403** (a new Proposal endpoint is closed until reviewed) |
| `…/versions` (v1), `/api/proposals/{n}/history` | versions filtered by `canSeeProposal(proposal_id)`; count and paging after filtering |
| `…/people`, `/units`, `/attachments`, `/custom-data` | version-scoped by query (`proposal_id`) |
| `…/comments` | comments on invisible versions become the no-comment placeholder; a comment with no version key is family-level and shown only when every version is visible (`canSeeEveryProposalVersion`) |
| `…/funded-awards` | a row needs its own Proposal version, the exact linked Award version and the Award's current version all visible |
| `/api/proposals/{n}` | latest version checked |
| `/api/proposals/{n}/awards` | computed only over links made on visible Proposal versions; then the linked Award version and the Award must be visible |
| `/api/v1/me/access` | access mode and grant kinds only (no identifiers) |

"Every version of the family is visible" is computed per `award_id` with the same rule as `canSeeAward` (`RecordVisibility.canSeeEveryAwardVersion`), and per `proposal_id` for Proposals (`canSeeEveryProposalVersion`). It is never assumed from the version-scope setting.

### Still closed (NOT IMPLEMENTED for restricted users)

Every other path returns `403 NOT_AVAILABLE_UNDER_RECORD_AUTHORIZATION` to non-Central users. This includes:

- Negotiation, Subaward and IRB (all paths);
- Document Explorer `/api/v1/documents` and `/api/documents/search`;
- the other Explorer paths: workflows, units, unit administrators, award contacts, persons, rolodex, sponsors, attachments, proposals;
- legacy `/api/awards/**`;
- any Award or Proposal sub-path not on its allow-list.

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

Verified by `RecordAuthorizationEnforcementIntegrationTest`: the full application on Testcontainers with the synthetic seed. It covers every persona, direct URLs, counts and pages, attachments, related lists, hierarchy, dashboard, Global Search, every section fix above (including the cross-award T&M read), Evidence Search related excerpts (with `topK` after filtering), Proposal versions/history/comments/funded Awards, search-row root/parent numbers, reports, the File Finder, Explorer, AI, closed paths, revocation, and agreement between the SQL scope and the per-record checks. The Award and Proposal allow-lists are unit-tested in `RecordAuthorizationInterceptorTest`.

## Not implemented yet

| Scope | Notes |
|---|---|
| Non-Central rules for Negotiation, Subaward, IRB | proposal P8 / decision D-G |
| Document Explorer, other Explorer paths, legacy `/api/awards` | closed for non-Central users |
| Proposal / Negotiation rows in the File Finder | omitted for non-Central users |
| Partial-family AI and T&M actions | closed by design under P3 (see above) |
| Grant-administration UI and workflow | not planned until approved (P2) |
| Real enrollment from BU attributes; real IO resolution | awaiting BU IAM and decision D-A |
| Wiring production `CurrentIdentityProvider` to a populated identity store | depends on enrollment |

## Migration numbering

`V082` (this work). `V081` is reserved for the Award amount-dates work on a separate branch. If V082 lands first, the migration runner logs a harmless gap warning until V081 arrives; it applies migrations by version regardless of order.
