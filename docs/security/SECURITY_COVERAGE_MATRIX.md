# RAP security coverage matrix

Maps the 15 requested lifecycle categories onto the tests that **already
exist**, with verified execution results, and separates genuine gaps from
things merely not yet written.

Built by inventory rather than by writing 15 new suites: much of this is
already covered, and duplicating it would hide the real gaps.

Verified 2026-11-13/14 against `main` (PR #152 merged, `c8622d1`).

## Execution evidence used throughout

| Run | Scope | Result |
|---|---|---|
| Local, full database job | 26 suites, the exact CI list | **548 tests, 0 failures, BUILD SUCCESS** |
| CI on `4af0bde` (#152) | API unit | **1766 tests, 0 failures** |
| CI on `4af0bde` (#152) | PostgreSQL integration | **548 tests, 0 failures** |
| CI on `1f868f7` (#154) | PostgreSQL integration | **pass** |

PR #154 touches **8 frontend files only** — no `api/`, no `database/` — so
the API evidence above applies to it unchanged.

Every suite below is Testcontainers-based with synthetic identities
(`SYN-INST-*`, `ARP-TEST-*`) and a disposable PostgreSQL container. **No
real identity, grant or membership was touched, and Anthony's Cognito
account was read only.**

---

## 1. Cognito identity exists but is not linked to RAP

*Anthony's actual starting condition.*

| | |
|---|---|
| Tests | `ArchiveIssuedIdentityIntegrationTest.anUnlinkedCognitoIdentityResolvesToNothingHowEverManyGrantsExist`; `IdentityResolverTest.anAuthenticatedIdentityWithNoLinkIsNotProvisioned`; `AdminVerifiedPilotPathIntegrationTest.anUnlinkedIdentityIsNotProvisioned`; `CapabilitiesMembershipIntegrationTest.anIdentityWithNoRowAtAllIsNotAnAdministrator`; `GrantProvisioningIntegrationTest.anUnlinkedGranteeIsAllowedWithAWarning` |
| Proves | An authenticated Cognito identity with no `authz.identity_link` row resolves to `NotProvisioned` and reaches nothing — **however many grants exist against the identifier**. A grant may be written for a not-yet-linked identifier, with a warning. |
| Result | pass |
| Coverage | **Complete** |

Also verified live on DEV: searching `antcast` returns 0 results because a
login name is only searchable via `identity_link.login_name`. Documented in
the UI copy change in PR #154.

## 2. Identity successfully linked

| | |
|---|---|
| Tests | `AuthorizationStoreIntegrationTest.anActiveLinkResolvesAndOnlyActiveUnexpiredGrantsCount`, `.unknownSubjectsAreNotProvisionedAndRevokedLinksAreRevoked`; `ArchiveAdminBootstrapIntegrationTest.successWritesMembershipLinkAndBothAuditRowsTogether` |
| Proves | An ACTIVE link resolves to `Mapped`; a REVOKED link resolves to `Revoked` and authorises nothing; link creation and its audit row are atomic. |
| Result | pass |
| Coverage | **Complete** |

## 3. Linked identity with no research-record grants

| | |
|---|---|
| Tests | `AccessScopeResolverTest.aMappedPersonWithNoActiveGrantsIsNotProvisioned`; `ProposalRecordAuthorizationIntegrationTest.anAuthenticatedUserWithNoGrantsGetsAccessNotProvisioned`; `ArchiveAdminAccessManagementTest.readingAnIdentityWithNoLinksAndNoGrantsReportsNoAccess` |
| Proves | Being linked grants nothing. A mapped person with no grant is `NotProvisioned`, and the admin screen reports "no archive access" rather than an empty list that could read as "all". |
| Result | pass |
| Coverage | **Complete** |

## 4. UNIT grants, with and without descendants

| | |
|---|---|
| Tests | `AuthorizationStoreIntegrationTest.unitHierarchyFindsDescendantsAndSurvivesCycles`; `ProposalRecordAuthorizationIntegrationTest.aParentUnitReachesItsDescendantWhenTheGrantAllowsIt`, `.aParentUnitAloneReachesNothingWhenDescendantsAreNotGranted`, `.dashboardForAParentUnitWithDescendantsReachesTheChildUnit`, `.dashboardForAParentUnitWithoutDescendantsReachesNothing`; `SubawardRecordAuthorizationIntegrationTest.scenario8_*`; `GrantProvisioningIntegrationTest.aUnitGrantWithoutAUnitNumberIsRefused` |
| Proves | Descendant inclusion is honoured **and** its absence is honoured, on records and on dashboard counts. Unit hierarchy traversal terminates on a cyclic hierarchy. |
| Result | pass |
| Coverage | **Complete** — notably covers the *negative* case, which is the one that leaks if wrong. |

## 5. IO grants

| | |
|---|---|
| Tests | `GrantProvisioningIntegrationTest.anIoValueOnAUnitGrantIsRefused` and the IO-scope cases; `AwardV1ControllerTest`/`AdminAuthorizationControllerTest.grantingReturns201AndLabelsAnIoGrantAsSapAccount` |
| Proves | IO grants are stored and labelled as SAP accounts, scope-limited to Awards, and type/value mismatches are refused. |
| Result | pass |
| Coverage | **Complete** |

## 6. PARENT_AWARD grants and award-family boundaries

| | |
|---|---|
| Tests | `GrantProvisioningIntegrationTest.aParentAwardGrantCanBeCreated`, `.twoDifferentParentAwardsAreBothStorable`, `.theSameParentAwardTwiceIsRefused`; `AwardHierarchyExpansionIntegrationTest.anAwardWithNoHierarchyRowReachesItself` (+10 tests); `AwardRecordAuthorizationIntegrationTest.decision5_unauthorizedHierarchyRelativesAreOmittedNotStubbed` |
| Proves | Parent-award expansion respects the configured depth; an award with no hierarchy row still reaches itself; unauthorised relatives are **omitted, not stubbed** — so the hierarchy does not leak existence. |
| Result | pass |
| Coverage | **Complete** |

## 7. CENTRAL grants

| | |
|---|---|
| Tests | `AuthorizationStoreIntegrationTest`, `GrantProvisioningIntegrationTest`, `ArchiveIssuedIdentityIntegrationTest`, `ArchiveApiRoleIntegrationTest`, `SecurityAcceptancePackIntegrationTest` (CENTRAL fixtures throughout) |
| Proves | CENTRAL reaches every module; V087's index uses `COALESCE(unit_number,'')` and `COALESCE(io_value,'')` specifically so NULL-scoped CENTRAL grants are **not** left unguarded by SQL's distinct-NULL rule. |
| Result | pass |
| Coverage | **Complete** for behaviour. See §12 for the UI defect that *preselects* CENTRAL. |

## 8. Expired, revoked and modified grants

| | |
|---|---|
| Tests | `AuthorizationStoreIntegrationTest.anActiveLinkResolvesAndOnlyActiveUnexpiredGrantsCount`; `AccessScopeResolverTest.expiredAndNotYetValidGrantsAreIgnored`; `GrantProvisioningIntegrationTest.anExpiredButUnrevokedGrantStillBlocksTheScope`, `.anExpiredGrantStillBlocksTheSameScope`, `.aRevokedScopeCanBeGrantedAgainWithoutViolatingV087` |
| Proves | Expired and not-yet-valid grants are ignored for access, yet still occupy the scope for uniqueness — so an expired grant cannot be silently shadowed by a second one. A revoked scope can be re-granted. |
| Result | pass |
| Coverage | **Complete** for expiry/revocation. **Partial** for *modification* — see §12. |

## 9. Suspended and reactivated identities

| | |
|---|---|
| Tests | `NegotiationRecordAuthorizationIntegrationTest.aSuspendedIdentityIsRefusedBeforeAnyQueryRuns`; `SubawardRecordAuthorizationIntegrationTest.scenario12_*`, `.d7_aSuspendedIdentityIsRefusedBeforeAnyFundingIsRead`; `ProposalRecordAuthorizationIntegrationTest.aRevokedOrSuspendedIdentitySeesNothingAndLeaksNoCount`, `.dashboardForARevokedOrSuspendedIdentityCountsNoProposals`; `CapabilitiesMembershipIntegrationTest.aSuspendedIdentityHoldingTheRealActiveRowIsStillNotAnAdministrator`; `AdministrationReadOnlyIntegrationTest.aSuspendedIdentityHoldingAnActiveMembershipIsRefused` |
| Proves | Suspension is checked **before any query runs**, overrides an ACTIVE ARCHIVE_ADMIN membership, and leaks no counts. Verified against the real schema at the HTTP layer. |
| Result | pass |
| Coverage | **Complete** for suspension. **Missing** for *reactivation* — no test asserts access returns after un-suspension. |

## 10. ARCHIVE_ADMIN membership independent of record access

| | |
|---|---|
| Tests | `AdministrationAvailabilityTest` (13); `CapabilitiesMembershipIntegrationTest` (8); `AdministrationReadOnlyIntegrationTest` (10); `ArchiveAdminAccessManagementTest.readingAnIdentityWithNoLinksAndNoGrantsReportsNoAccess` |
| Proves | Holding the role grants no record access, and record enforcement does not gate the role. Every flag combination asserted; record enforcement pinned `false` throughout. |
| Result | pass |
| Coverage | **Complete** — this is the best-covered category, being the subject of PR #152. |

## 11. Administrator promotion, revocation, last-administrator protection

| | |
|---|---|
| Tests | `ArchiveAdminConcurrencyIntegrationTest` (row-locked last-administrator guard); `ArchiveAdminBootstrapIntegrationTest.theMembershipAndItsAuditRowArePairedAtomically`, `.theMembershipAndItsAuditRowRollBackTogether`, `.failureWhileLinkingRollsBackTheMembershipAndItsAuditRow`; `AdminAuthorizationControllerTest.revokingTheLastAdministratorIs409`, `.thereIsNoBootstrapEndpoint` |
| Proves | Revocation cannot empty the role — two concurrent revokes of the final two serialise and exactly one is refused, under a row lock. Membership and audit are atomic in both directions. There is no HTTP bootstrap route. |
| Result | pass |
| Coverage | **Complete for revocation. MISSING for promotion** — see §12. |

## 12. Self-granting, grant widening, independent approval

**This is the weak category, and the findings are implementation gaps, not
test gaps.**

| Condition | Status |
|---|---|
| Self-approval on **relinking** | **Covered and enforced** — `ArchiveIssuedIdentityIntegrationTest.theDatabaseRefusesASelfApprovalEvenIfTheServiceCheckIsBypassed`, plus `.relinkingAnIdentityWithGrantsIsRefusedWithoutASecondApproval`, `.anApprovalCannotBeReusedForAnotherIdentityOrTarget`, `.anApprovalIsConsumedAndCannotAuthoriseASecondRelink`, `.aRevokedApprovalAuthorisesNothing`, `.concurrentRelinksCannotBothConsumeOneApproval`. V086 enforces it in the database, not only in service code. |
| Approval reference on the **provisioning CLI** | **Covered** — `GrantProvisioningIntegrationTest.aMissingApprovalReferenceIsRefused`, `.theAuditRowCarriesOperatorAndApprovalReference` |
| **Self-granting record access over HTTP** | **GAP — no control and no test.** `ArchiveAdminService.grantAccess(actor, subject, spec)` calls `requireAdministrator(actor)` and `requireSubject(subject)` and **never compares them**. The only `actor.equals(subject)` check lives in `provisioning/IdentityLinkCommand:249`, a CLI. |
| **Grant widening via `modifyAccess`** | **GAP — deliberately unobstructed.** `modifyAccess(actor, grantId, expiresAt, includeDescendants, reason)` is addressed by `grantId` alone: no subject, no self-check. It can extend expiry indefinitely and flip `includeDescendants`. V087's index comment states the design explicitly: *"include_descendants is excluded so modifyAccess can toggle it without colliding."* So the widening path is intentionally free of database obstruction. |
| **Administrator promotion** | **GAP — no two-person rule.** `assignAdministrator` requires only that the actor is *an* administrator. Any single administrator can unilaterally appoint another. Inconsistent with relinking (two-person) and revocation (last-administrator guard). |

`GrantProvisioningIntegrationTest.aSecondGrantDifferingOnlyInIncludeDescendantsIsRefusedByTheIndex`
is worth reading carefully: it shows the *create-a-second-grant* route is
refused, while documenting that the *modify-an-existing-grant* route is
deliberately open. Those are different paths; only the first is guarded.

## 13. Audit records for successful and refused actions

| | |
|---|---|
| Tests | `AuthorizationStoreIntegrationTest.theAuditTrailIsAppendOnly`; `ArchiveApiRoleIntegrationTest.auditInsertSucceeds`, `.auditUpdateAndDeleteFailOnTheGrantBeforeTheTrigger`, `.droppingTheAuditTriggerFails`; `ArchiveAdminBootstrapIntegrationTest` (4 atomicity tests); `ActivityAuditGrantIntegrationTest` (10); `AdministrationReadOnlyIntegrationTest.everyWriteIsRefusedAndTheDatabaseIsUntouched`, `.theAuditTrailIsAppendOnlyRegardlessOfRecordEnforcement` |
| Proves | Audit is append-only **by database trigger**, and the API role cannot even drop the trigger. Audit rows are written in the same transaction as the action and roll back with it. **A refused action writes no audit row** — proven by counting before and after all eight refused writes. |
| Result | pass |
| Coverage | **Complete** |

> One deliberate design point, not a gap: successful *reads* are not
> audited. The audit page states this. Record-access auditing is V089
> (`archive_activity_audit`, SEC-008), which is unmerged and out of scope.

## 14. Access-denied responses, expired sessions, reauthentication

| | |
|---|---|
| Tests | `MeCapabilitiesControllerTest.withoutATokenTheEndpointIsUnauthorized`, `.anInvalidOrExpiredTokenIsUnauthorized`; `IdentityEnrollmentServiceTest.anEnforcedDeploymentWithoutAMaximumSignInAgeDeniesEveryRequest`, `.withEnforcementOffTheSignInAgeIsNotChecked`, `.theSameSignInSessionIsVerifiedOnceThenCached`, `.aStaleSessionKeepsItsVerificationButTheNextSignInSeesTheChangedAttribute`; `AccessNotProvisionedProblemTest`; `AdminAuthorizationControllerTest.anUnresolvableCallerIsAlsoRefusedWith403` |
| Proves | 401 without or with a bad token; 403 (not 404) for an authenticated non-administrator once the surface exists; denial shape discloses nothing about the caller's own state; sign-in age is enforced only when enforcement is on. |
| Result | pass |
| Coverage | **Complete** at the unit/HTTP layer. **Partial** end-to-end: a real Cognito token expiring mid-session was observed manually during this engagement (the session expired and redirected to Shibboleth) but is not automated — and arguably should not be. |

## 15. Cross-module access checks

| | |
|---|---|
| Tests | `AwardRecordAuthorizationIntegrationTest`, `ProposalRecordAuthorizationIntegrationTest`, `SubawardRecordAuthorizationIntegrationTest`, `NegotiationRecordAuthorizationIntegrationTest`; `RecordAccessEvaluatorTest` (+ `Sec003ContactAccessIsAlwaysVersionSpecific`); `DocumentExplorerFilteredLightPathIntegrationTest`; dashboard and Global Search scoping inside each module suite |
| Proves | Award, Proposal, Negotiation and Subaward each enforce scope on records, counts and search. Dashboard counts and Global Search are scoped per module. Denial is 404 for records (existence is itself disclosure) and counts never leak. |
| Result | pass |
| Coverage | **Complete for the four authorization modules.** **Not applicable to IRB** — no record rule by decision. **Documents**: covered via the Documents explorer suite; its authorization story rides on the four modules rather than having its own rule. |

---

## Release blockers, categorised

### A. Security vulnerabilities requiring implementation

**These are the minimum before administrator writes are enabled.**

| # | Gap | Why it matters | Fix |
|---|---|---|---|
| A1 | **Self-granting over HTTP** — `grantAccess` never compares actor to subject | An administrator can grant themselves record access. Inert while enforcement is off, live the moment it is switched on | Refuse `actor.equals(subject)`, or require a second approval as relinking does |
| A2 | **Grant widening via `modifyAccess`** — `grantId` only, no subject, no self-check; can extend expiry and flip `includeDescendants` | Same latency problem: a widened grant is invisible until enforcement is on. The database deliberately does not obstruct it | Add a self-check, and require approval to widen scope or extend expiry |
| A3 | **Administrator promotion has no two-person rule** | One administrator can unilaterally create another, bypassing every other control by adding an accomplice | Apply the V086 relink-approval pattern to `assignAdministrator` |
| A4 | **V087 applied status in DEV unverified** | `grantAccess`'s duplicate protection *is* that index, not service code. If absent, duplicate active grants are possible | Confirm read-only via CloudShell before enabling writes |

### B. Missing automated tests

| # | Gap |
|---|---|
| B1 | Self-grant refusal (cannot be written until A1 exists) |
| B2 | `modifyAccess` widening refusal (blocked on A2) |
| B3 | Administrator promotion approval (blocked on A3) |
| B4 | **Reactivation** — no test asserts access returns after un-suspension |
| B5 | End-to-end token expiry with a real Cognito token (low value; manual is reasonable) |

B1–B3 are **blocked on implementation**, not merely unwritten.

### C. Functionality intentionally disabled

| | |
|---|---|
| Administrator writes | OFF by design — `administration-writes-enabled=false`. All 8 endpoints 409 |
| Record-level enforcement | OFF — `enforcement-enabled` absent |
| Record-access auditing (V089) | Unmerged, out of scope |
| IRB record rules | None, by decision |
| HTTP bootstrap of the first administrator | Deliberately absent (decision D-ID-2) |

### D. Non-blocking UI improvements

| # | Item | Status |
|---|---|---|
| D1 | Read-only indicators and write-control gating | **Fixed in PR #154**, awaiting review |
| D2 | **Grant form preselects "Central (all records)"** | **FOUND, NOT FIXED.** `useState({ type: "CENTRAL", … })`. The most privileged scope is the default |
| D3 | **`validateGrantForm` does not require a reason** | **FOUND, NOT FIXED.** A grant can be submitted with an empty reason, producing a weak audit row |
| D4 | Identity details: name, login, status, readable unit names, active/expired/revoked distinction | Not started |
| D5 | Audit history readable columns | Not started |
| D6 | Self-suspend / self-grant UI guards | Not started |
| D7 | Unit Permissions page absent (`/admin/units` renders the overview) | Not started |

**D2 and D3 are mislabelled as "non-blocking".** They are UI-only today
*because writes are disabled* — the server refuses everything. But they
must be fixed **before** writes are enabled, or the first real grant an
administrator creates will default to unrestricted CENTRAL access with no
required justification. I would treat both as **A-category once writes are
in scope**.

### Minimum work before enabling administrator writes

1. **A1, A2, A3** — the three missing controls
2. **A4** — confirm V087 is applied in DEV
3. **D2, D3** — the dangerous default and the missing reason requirement
4. **B1–B3** — tests proving the new refusals

### Minimum work before enabling record-level enforcement

Everything above, plus a separate assessment out of scope here. Enforcement
is the point at which every latent grant becomes live, so the self-grant and
widening gaps must be closed first regardless of the write decision.
