# Phase 2 — policy values and the four-role pilot

Companion to [`PHASE2_AUTHORIZATION_VALIDATION.md`](PHASE2_AUTHORIZATION_VALIDATION.md).
Nothing here has been applied: no grant, no identity link, no configuration, no
deployment.

---

## 1. The identity import is not a prerequisite

The DEV inventory found `authz.kim_principal` and `authz.principal_crosswalk`
empty, and that was reported as blocking the pilot. Tracing the code says
otherwise.

| Path | Reads | Shipped? |
|---|---|---|
| **Request-time resolution** — `IdentityResolver.resolve()` | `authz.identity_link` **only** | yes |
| **Administrator linking** — `ArchiveAdminService.linkIdentity()` | its parameters; no lookup | yes |
| **Automatic enrolment** — `IdentityEnrollmentService`, `JdbcEnrollmentStore` | `kim_principal`, `principal_crosswalk` | **no** — no `CognitoProfileReader` bean exists, so enabling it fails fast at startup |

The crosswalk tables have exactly two consumers in `api/src/main`, and both
belong to the path that is deliberately not shipped. Decision **D-ID-1**
reinforces it: historical KRIM `ACTV_IND` is never consulted, because most
people on archived records are inactive in KRIM.

`AdminVerifiedPilotPathIntegrationTest` (9 tests) proves the whole chain —
link, resolve, scope, record decision, including SEC-003 version-specific
contact access — runs with both crosswalk tables **empty**, and asserts they
are still empty afterwards. If a future change puts a crosswalk read on the
request path, that test fails and says so.

### What the import would still buy

One thing, and it matters: an **authoritative lookup**. Research Staff access
is derived from `kuali_person_id`, which must equal
`archive.award_person.person_id`. With the crosswalk empty, the only place
that value exists is the archive itself, where it is found **by name**.

Two people sharing a name is the failure the import prevents. The last test in
that class states the consequence rather than leaving it implied: a
well-formed link carrying the wrong principal resolves correctly, evaluates
correctly, and silently hands one person another person's records. Nothing in
the system can detect it — only the administrator, at the moment of pairing.

**Mitigation for a pilot of this size:** pair the principal against one
specific, known Award, and have the participant confirm they see that Award and
nothing they should not. At four participants that is a minute of work. At
forty it is not, which is when the import stops being optional.

---

## 2. Policy values

### Settled — do not re-litigate

| Setting | Value | Authority |
|---|---|---|
| `app.authorization.department-match` | **`LEAD_UNIT_WITH_DESCENDANTS`** | **D4** — "Department users see only authorized department and descendant records" |
| Grant expiry default | none; explicit and optional | **D-ADM-3** |
| Second approver for a grant | not required | **D-ADM-2** |

### Awaiting business approval — engineering's recommendation, not a decision

| Setting | Options | Recommended for the pilot | Why |
|---|---|---|---|
| `app.authorization.version-scope` | `PER_VERSION` · `FAMILY_WIDE` | **`FAMILY_WIDE`** | `PER_VERSION` denies the AI endpoints with `AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS` whenever a family is partly visible, and research staff are listed on *some* versions by nature — so the AI features would mostly refuse for exactly the pilot role meant to exercise them. The cost is real and should be stated plainly: a user authorized for one version sees versions they were not individually granted. **This is the decision with the largest visible effect; it is Anthony's, not ours.** |
| `app.authorization.contact-derivation` | `EXPLICIT_GRANT` · `VERIFIED_PRINCIPAL` | **`EXPLICIT_GRANT`** | A pilot wants every access to be a reviewable row. `VERIFIED_PRINCIPAL` grants access with no grant row at all, appearing and disappearing as Kuali contact rows change — correct for steady state perhaps, but it makes "who could see this during the pilot" unanswerable afterwards |
| `app.authorization.research-staff-roles` | BU role codes | **`PI`** as the minimum viable set | Engineering cannot supply this — they are BU role names. `PI` is observed in `archive.award_person.contact_role_code` and is unambiguous. The real question is whether `COI`, `KP` and similar count |
| `app.authorization.max-sign-in-age` | a duration | **`12h`** | Long enough that a working day needs one BU sign-in; short enough that a 30-day refresh token cannot keep a session alive without one. No decision record exists; this is a starting point to react to, not a recommendation with authority behind it |
| `app.authorization.parent-award-depth` | 0–10 | **`1`** (the default) | No `PARENT_AWARD` grant exists, so the pilot does not exercise it |

A configuration that sets all of these looks like:

```
APP_AUTHORIZATION_ENFORCEMENT_ENABLED=true
APP_AUTHORIZATION_VERSION_SCOPE=FAMILY_WIDE
APP_AUTHORIZATION_DEPARTMENT_MATCH=LEAD_UNIT_WITH_DESCENDANTS
APP_AUTHORIZATION_CONTACT_DERIVATION=EXPLICIT_GRANT
APP_AUTHORIZATION_RESEARCH_STAFF_ROLES=PI
APP_AUTHORIZATION_MAX_SIGN_IN_AGE=12h
```

Set the flag without the rest and the application now **refuses to start**,
naming every missing property — see §4.

---

## 3. The four-role pilot

No Cognito user was enumerated and no identity was invented. Each row needs a
named person from the business; identifiers should be handed over out of band
and never committed.

| Role | Link | Grant | Reaches | Proves |
|---|---|---|---|---|
| **Central Administrator** | ADMIN_VERIFIED, no principal needed | `CENTRAL` | every record, every module | enforcement does not break the administrative view; Dashboard totals unchanged |
| **Department Administrator** | ADMIN_VERIFIED, no principal needed | `UNIT` + `include_descendants` | one lead unit and its sub-units | scoping works, and counts match what paging reaches |
| **Research Staff** | ADMIN_VERIFIED **with `kuali_person_id`** | `CONTACT_DERIVATION` (under `EXPLICIT_GRANT`) | only Award versions they are listed on in an approved role | SEC-003 is version-specific in practice, not just in tests |
| **No-Grant Control** | ADMIN_VERIFIED, no principal | **none** | nothing | denial is distinguishable from an empty archive — the single most important check |

The control is the one most likely to be dropped as pointless, and the one
that catches the worst failure: an archive that looks broken rather than
restricted.

### Verification order, once enforcement is on

1. **No-Grant Control first.** Must get an explicit "access not provisioned"
   response, never a silent empty list. If this is wrong, stop.
2. **Central Administrator.** Dashboard totals must match the pre-enforcement
   baseline exactly (Awards 40,926 · Proposals 33,960 · Historical Award
   267,386 · Historical Proposal 119,788 · Negotiations 10,775 · Subawards
   3,511 · Kuali Documents 491,678).
3. **Department Administrator.** Totals scoped; open a record, confirm it is in
   their unit; confirm the count equals what paging actually returns.
4. **Research Staff.** Confirm they see the Award version they are listed on
   and **not** an earlier version they were not on.

### What each participant should be told beforehand

That records may disappear from their view, that this is deliberate, and whom
to contact. A pilot participant who sees an empty Dashboard and reports "the
archive is broken" has given correct feedback about a wrong expectation.

---

## 4. The startup safeguard

`app.authorization.enforcement-enabled=true` on its own used to start cleanly
and deny **every record to every user**, administrators included:
`RecordAuthorizationGate.decide()` checks the policy before it consults any
grant, and no strategy has a default. The gate computed
`Status.ENFORCED_POLICY_NOT_CONFIGURED` for exactly this case, but nothing
consumed it — no endpoint, no health indicator, no log line. The visible
symptom was an empty archive, which reads as a broken deployment.

The application now refuses to start, listing every problem at once:

```
Record-level authorization is enabled (app.authorization.enforcement-enabled=true)
but its configuration is incomplete, and starting this way would deny every record
to every user, administrators included. 5 problems:
  - app.authorization.version-scope is not set (one of: PER_VERSION, FAMILY_WIDE)
  - app.authorization.department-match is not set (one of: EXACT_LEAD_UNIT, LEAD_UNIT_WITH_DESCENDANTS)
  - app.authorization.contact-derivation is not set (one of: EXPLICIT_GRANT, VERIFIED_PRINCIPAL)
  - app.authorization.research-staff-roles is empty (the Kuali contact role codes that count as Research Staff, e.g. PI)
  - app.authorization.max-sign-in-age is not set (e.g. 12h; a Cognito refresh keeps the original auth_time, ...)
Set them, or set app.authorization.enforcement-enabled=false.
```

Three properties beyond the policy are also validated: `max-sign-in-age` must
be present and positive (it is *not* part of `policy()`, so a configuration
missing only this one produced a valid policy and then denied every enforced
request somewhere else entirely), and `parent-award-depth` must be within
0–`MAX_PARENT_AWARD_DEPTH`, because the traversal clamps and a configured 25
would silently mean 10.

**Enforcement off is untouched.** The validator returns immediately, so DEV —
which carries no `APP_AUTHORIZATION_*` variable at all — starts exactly as
before. That is asserted directly, including with deliberately invalid values
present while the flag is off.

Operationally this converts the worst-case mistake from "a dark archive nobody
can explain" into "the deployment stopped and the previous task definition
keeps serving".

---

## 4b. Verifying a participant's identity — the procedure

Name matching is not a verification step. `archive.award_person.full_name` is
not unique: `AdminVerifiedPilotPathIntegrationTest.aSharedDisplayNameIsNotAnIdentity`
uses two rows reading `SYN RESEARCHER` with different principals on different
Awards, and shows that each reaches only their own — so a name tells the
administrator nothing about which principal is correct, and a wrong pairing is
undetectable afterwards.

For each participant, record all four values before creating anything:

| # | Value | Authoritative source | How to confirm it |
|---|---|---|---|
| 1 | Cognito `sub` | the Cognito user pool, via `AdminGetUser` on the user's BU login name | the pool is `us-east-1_VJ4ekQ27c`; `ListUsers` is not available in this account, so the login name must come from the participant or from BU IAM |
| 2 | Institutional identifier | **BU IAM / the person themselves** — never derived from archive data | this is the stable key every grant hangs off; it must be the same value BU would use elsewhere for that person |
| 3 | Kuali person id | `archive.award_person.person_id`, **only** for Research Staff | see the pairing check below — this is the one that cannot be confirmed from a name |
| 4 | Login name | the Cognito user record | recorded for display only; login names change, so nothing authorizes on it |

### The pairing check for a Kuali person id

Do not search by name and take the first hit.

1. Ask the participant to name **one specific Award they expect to see** — its
   Award number and roughly when they were on it.
2. Look up the contacts of that Award by Award number, not by person name.
3. Confirm exactly one contact row on that Award matches the participant, in an
   approved role, on the version they expect.
4. Take `person_id` from **that row**.
5. After the link exists, have the participant confirm they can see that Award
   and that nothing unexpected appears.

Step 5 is the real control. A wrong pairing shows up immediately as "I cannot
see my own Award" or "I can see someone else's", and both are visible to the
participant before any wider rollout.

**This procedure does not scale.** At four participants it is a few minutes
each. The moment the pilot grows beyond a handful, load `kim_principal` and
`principal_crosswalk` so the lookup is authoritative rather than manual.

### Failure modes, and what each looks like

Each is pinned by a test in `AdminVerifiedPilotPathIntegrationTest`.

| Failure | What the system does | Detectable? |
|---|---|---|
| Two people share a display name | nothing — each principal reaches only its own records | **no**, only the administrator can tell them apart at pairing time |
| Wrong Kuali person id paired | the link resolves, the evaluator decides correctly, the wrong records are reached | **no** — the pairing check above is the only control |
| Participant never linked | `IdentityResolution.NotProvisioned` → **403 `ACCESS_NOT_PROVISIONED`** | yes |
| Link revoked | `IdentityResolution.Revoked` → 403, and the principal on the revoked row grants nothing | yes |
| Two active links for one Cognito identity | refused by a database constraint before it can exist; resolution would report `Ambiguous` | yes |
| One institutional identity linked to two people | `IdentityResolution.Ambiguous` → 403 | yes |
| No Kuali person id on the link | contact derivation yields an empty scope, which denies; it never matches everything | yes |

---

## 4c. How a user tells "no access" apart from "empty archive"

A pilot participant with no grants must not see something that looks like a
broken site. The API already separates the three cases:

| Situation | Response | Code |
|---|---|---|
| Not linked at all | **403** | `ACCESS_NOT_PROVISIONED` — "No archive access has been provisioned for this account." |
| Linked, ambiguous, revoked or suspended | **403** | identity access denied |
| Linked and granted, but this record is out of scope | **404** | deliberately indistinguishable from a record that does not exist |

The 404 is intentional and should not be changed without a separate security
review: `RecordNotAccessibleException extends NoSuchElementException` and is
explicitly *not* handled by `GlobalExceptionHandler`, with a comment saying so,
so it falls through to the same 404 as a genuinely missing record. That is what
stops a scoped caller probing for the existence of records they cannot see.

### The gap

**The UI has no handling for `ACCESS_NOT_PROVISIONED` at all** — zero
references in `ui/src`. A participant in that state would get whatever the
generic error path renders, not the plain-English explanation the API is
supplying.

That matters because the No-Grant Control is the pilot's first and most
important check, and what it is really testing is the *user-visible*
difference between "you have not been given access" and "the archive is
empty". Right now the API makes that distinction and the UI discards it.

**Recommended before the pilot:** a small UI change that renders
`ACCESS_NOT_PROVISIONED` as its own state — who to contact, no record counts,
no suggestion that anything is broken. It is UI-only, needs no authorization
change, and is not included in this PR.

---

## 5. Still outstanding

| # | Decision | Owner | Blocks |
|---|---|---|---|
| 1 | `version-scope` | Anthony | enforcement |
| 2 | `contact-derivation` | Anthony | enforcement, Research Staff role |
| 3 | `research-staff-roles` — which Kuali role codes | Anthony | enforcement, SEC-003 |
| 4 | `max-sign-in-age` | Anthony / security | enforcement |
| 5 | The four pilot participants, by name | business | every pilot step |
| 6 | Whether the first grants carry an expiry | Anthony | grant issue |
| 7 | Whether the administrator's own identity is also a pilot grantee, or a distinct research identity is enrolled | Anthony | grant issue |
| 8 | Loading `kim_principal` / `principal_crosswalk` | ETL | **not the pilot** — needed before the pilot grows beyond a handful |

Items 1–4 are the ones that now stop the application from starting rather than
quietly denying everything, which is the point of this change.
