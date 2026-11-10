# DEV security pilot — the eight-user package

Everything needed to request activation, in one place. **Nothing here has
been created.** No identity link, no grant, no task definition registration,
no enforcement, no V089.

## First, the honest part: the identities are not filled in

I cannot provide eight *verified* identities, and should not appear to. An
identity here is three values that come from outside this repository:

| Value | Authoritative source | Why not me |
|---|---|---|
| Institutional identifier (ARP) | **BU IAM, or the person** | it is the stable key every grant hangs off; deriving it from archive data would invent it |
| Cognito `sub` | the user pool `us-east-1_VJ4ekQ27c`, via `AdminGetUser` on their BU login name | `ListUsers` is not available in this account, so the login name must come from the participant or BU IAM |
| Kuali `PERSON_ID` (role 4 only) | `archive.award_person.person_id`, via the Award-first pairing procedure | a name does not identify a principal — see below |

So what follows is **eight fully specified roles** with every field named, and
the procedure for filling them. The people are a business decision. Filling
these in is step B of the approval checklist, not something a reviewer should
find already done.

### The one that cannot be checked afterwards

Role 4's `PERSON_ID`. A wrong pairing resolves cleanly, the evaluator decides
correctly, and the **wrong records** are reached. Name matching is not
verification —
`AdminVerifiedPilotPathIntegrationTest.aSharedDisplayNameIsNotAnIdentity`
uses two different principals sharing a display name and shows each reaches
only its own records.

The procedure, from `PHASE2_PILOT_PLAN.md` section 4b:

1. Ask the participant to name **one Award they expect to see**, and roughly when.
2. Look that Award up **by Award number**, never by person name.
3. Confirm exactly one contact row matches them, in an approved role, on the version they expect.
4. Take `person_id` from **that row**.
5. After the link exists, have them confirm they see that Award and nothing unexpected.

Step 5 is the real control: a wrong pairing shows up immediately as "I cannot
see my own Award" or "I can see someone else's", and both are visible to the
participant before any wider rollout.

---

## The eight roles

| # | Role | ARCHIVE_ADMIN | Grant | PERSON_ID | ARP | Cognito `sub` |
|---|---|---|---|---|---|---|
| 1 | Central User | no | `CENTRAL` | — | ☐ | ☐ |
| 2 | Department User | no | `UNIT 1250000000` | — | ☐ | ☐ |
| 3 | Department User, descendants | no | `UNIT 1250000000 --include-descendants` | — | ☐ | ☐ |
| 4 | Research Staff | no | `CONTACT_DERIVATION` | ☐ **required** | ☐ | ☐ |
| 5 | SAP IO User | no | `IO 9500234095` | — | ☐ | ☐ |
| 6 | Parent Award User | no | `PARENT_AWARD 100380-00001` | — | ☐ | ☐ |
| 7 | **Grant Administrator** | **yes** | **none** | — | ☐ | ☐ |
| 8 | **No-Grant Control** | no | **none** | — | ☐ | ☐ |

**Six grants, not eight.** Roles 7 and 8 receive none. If a provisioning run
names either as a grantee, stop — that is the pilot's control being spent.

---

## Expected access, per role

Every scope value below was read from the live DEV API on 2026-11-10, and
every expectation is executed in `SecurityAcceptancePackIntegrationTest`
against a real schema. The matrix case number is given so the expectation and
its test are the same statement.

| # | Should SEE | Should NOT see | Case |
|---|---|---|---|
| 1 | every record in every module | — | 1 |
| 2 | Award `212137-00001` (lead unit `1250000000`) | `114471-00001` — the **child** unit's Award | 2, 3 |
| 3 | `114471-00001` (child `1250400000`) **and** `200613-00001` (grandchild `1252060000`) | a record in an unrelated unit | 4, 5 |
| 4 | only Award versions they are listed on, in an approved role (`PI`, `COI`, `KP`, `MPI`) | an earlier version of the same Award they were not on | 12 |
| 5 | Award `100004-00002` (`account_number 9500234095`) | **any** Proposal, Subaward or Negotiation — an IO grant never reaches them | 14, 15 |
| 6 | Award `100380-00001` **and** its children `-00002`…`-00009` | `100330-00002` — a **different Award family** | 21–24 |
| 7 | **nothing** | every record | 20, 20b |
| 8 | **nothing** | every record | 16 |

### Expectations that depend on a policy value not yet decided

- **Role 3** is only different from role 2 if `department-match` is
  `LEAD_UNIT_WITH_DESCENDANTS`. Under `EXACT_LEAD_UNIT` the flag is ignored
  and the two roles become the same test.
- **Role 6** reaches **one** Award at `parent-award-depth = 0` and **nine** at
  `1`. Without that value decided, role 6 has no expected result.
- **Role 4** depends on `version-scope` and `contact-derivation`.

---

## Exact DEV records

| Purpose | Record | Verified |
|---|---|---|
| Unit hierarchy | `1250000000` → `1250400000` → `1252060000` | acceptance matrix, 2026-10-29 |
| Award at the parent unit | `212137-00001` | same |
| Award at the child unit | `114471-00001` | same |
| Award at the grandchild | `200613-00001` | same |
| IO account | `9500234095` on Award `100004-00002` (award_id 2) | same |
| Parent Award | `100380-00001`, **eight** children `-00002`…`-00009` | live Award search, 2026-11-10 |
| Different Award family (negative) | `100330-00002`, root `100330-00001` | same |
| Research staff roles present in DEV | `PI`, `COI`, `KP`, `MPI` | acceptance matrix |

`100380-00001` was chosen over the other hierarchy roots because eight
children make depth unambiguous: one Award at depth 0, nine at depth 1. A
parent with a single child cannot distinguish "expansion worked" from
"expansion did nothing".

---

## No-Grant Control verification

The single most important check, and the one most likely to be dropped as
pointless. Run it **first**, before any other role.

| Step | Expected | Not acceptable |
|---|---|---|
| Role 8 signs in | reaches the archive | an error page |
| Opens the Dashboard | an explicit "access not provisioned" message naming who to contact | a silently empty Dashboard, or "the dashboard data could not be loaded" |
| Opens any record by URL | **404** | 403, or anything distinguishing "exists but forbidden" from "not found" |
| Uses Global Search | **200 with zero results** | 404 — an empty result set is not a denial |
| Compare with role 7 | identical resolution (`NO_ACTIVE_GRANTS`) | role 7 seeing anything |

**If role 8 sees a silently empty archive rather than an explanation, stop.**
That is the failure mode that produces a bug report instead of an access
request, and it is what this control exists to catch.

Roles 7 and 8 resolve **identically**, and that is itself the finding: the
engine does not treat an administrator as special, so nothing about role 7
depends on remembering to withhold something.

---

## Rollback

| What | How | What survives |
|---|---|---|
| A link | `--unlink-link-id=<id> --confirm` | the row, status `REVOKED`, plus an `IDENTITY_UNLINKED` audit row. **Grants are untouched** |
| A grant | `--revoke-grant-id=<id> --confirm` | the row with `revoked_at` set, plus `ACCESS_REVOKED` |
| Enforcement | deploy the previous task definition revision | everything; it writes nothing |
| The whole pilot | revoke six grants, then unlink eight links | the full history |

**Two things to be clear about.**

**Revocation is not an undo.** It writes a new fact. The history survives on
purpose — that is what makes the audit trail an audit trail — so "rolled
back" means "withdrawn and recorded", never "as if it had not happened".

**Expiry does not replace revocation.** Every pilot grant is proposed with an
expiry so an unreviewed pilot cannot become permanent through inattention —
but V087 constrains **unrevoked** grants, so an **expired** grant still
occupies its scope and still blocks a replacement for it. Proven by four tests
in `GrantProvisioningIntegrationTest.ExpiryIsNotRevocation`. **Closing the
pilot means revoking, not waiting.**

---

## Outstanding BU policy decisions

None of these is engineering. All block activation.

| # | Decision | Owner | Note |
|---|---|---|---|
| P1 | Who the eight participants are | business | |
| P2 | `version-scope` — `FAMILY_WIDE` or `PER_VERSION` | business | changes what role 4 sees |
| P3 | `department-match` | business | `EXACT_LEAD_UNIT` collapses roles 2 and 3 into one test |
| P4 | `contact-derivation` — `EXPLICIT_GRANT` or `VERIFIED_PRINCIPAL` | business | |
| P5 | `research-staff-roles` | business | DEV holds only PI, COI, KP, MPI |
| P6 | **`parent-award-depth`** | business | role 6 has no expected result without it |
| P7 | `max-sign-in-age` | security | |
| P8 | Grant expiry date (proposed `2027-06-30`) | business | |
| P9 | SEC-008 retention period (90 days proposed) | business + records management | blocks V089 |
| P10 | SEC-008 failure policy for routine reads | security + business | `FAIL_CLOSED` is the planning direction; no default exists |
| P11 | SEC-008 privacy defaults | security + privacy | |
| P12 | Is a person told when their activity is inspected? | privacy | no channel exists today |

P9–P12 block approval 6 only. P1–P8 block approvals 3, 4 and 5.

---

## What to request, in order

1. **Approval 1** — deploy the API. Writes nothing.
2. **Approval 2** — register the two operator task definitions. Writes nothing.
3. Fill in the eight identities (P1, and role 4's pairing).
4. **Approval 3** — create the eight links. First write.
5. **Approval 4** — issue the six grants.
6. Decide P2–P8.
7. **Approval 5** — enable enforcement. Then verify the matrix on DEV, No-Grant Control first.
8. Decide P9–P12, then **approval 6a/6b**.

Steps 1–2 can proceed while P1–P8 are still open. Nothing after step 2 can.
