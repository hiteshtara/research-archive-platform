# DEV pilot dry runs — against the deployed revision-89 image

**Nothing was written, anywhere.** Every run below omitted `--confirm`, and
`authz.access_audit` holds **0 rows** afterwards — a confirmed write leaves an
audit row in the same transaction, so an empty audit table is the proof, not
the claim.

**DEV was never contacted.** The runs point at a disposable PostgreSQL
container on a private Docker network (`POSTGRES_HOST=rap-dryrun-pg`). The DEV
database has no route into that network and no credential for it was supplied.

## Why this is different from DEV_PILOT_DRY_RUNS.md

That file records the plan produced by `PilotDryRunPlanIntegrationTest` — the
utilities compiled **from source**. This one runs the utilities **as
deployed**: the exact image the DEV API service is running, pulled by its
immutable digest.

| | Source run | This run |
|---|---|---|
| What executes | `mvn test` against the working tree | `java -jar /app/app.jar` inside the published image |
| Identified by | a commit | `sha256:97071137…`, the digest revision 89 pins |
| Proves | the plan the code would produce | the plan **the running artifact** produces |

Both matter. The first catches a regression before release; the second catches
an image that is not what the source says it is.

## The artifact, verified before it was trusted

```
image   770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api
digest  sha256:970711374afae9658d90d0217b9e6dbb38d58f5b8e29711b84e99ada62413b2e
built from commit 94c22a5, deployed as task definition revision 89
platform linux/amd64, entrypoint ["java","-jar","/app/app.jar"], user archive (uid 10001)
```

The jar was extracted and byte-searched rather than assumed. Present:
`GrantProvisioningCommand`, `GrantProvisioningCli`, `IdentityLinkCommand`,
`IdentityLinkProvisioningCli`, `ArchiveAdminBootstrapCli`, `OperatorChecks`,
`RuntimeVerification`, `EcsTaskMetadataRuntimeIdentity`. Absent, as a positive
control that the search is discriminating: every test-scope class.

## The two things a dry run outside ECS cannot check

The utilities say so themselves, on every run:

```
WARN   runtime account unknown - a dry run outside ECS cannot verify where it is pointed
WARN   runtime cluster unknown - a dry run outside ECS can neither confirm the environment nor rule out production
```

That is the control working, not failing. `EcsTaskMetadataRuntimeIdentity`
reads `ECS_CONTAINER_METADATA_URI_V4`, which only Fargate injects; with no
metadata the runtime reports nothing rather than guessing, and a **write**
refuses outright ("Refusing to write. Run this as an ECS task; a dry run is
still available").

The consequence is honest and worth stating: **`--aws-account-id` and
`--environment` cannot be verified here.** A run with
`--aws-account-id=111111111111` returned `DRY_RUN_OK` locally, because there
was no runtime account to compare it against. In ECS the same run refuses with
"account mismatch". Those two checks can only be exercised as a registered
task, which is a separate approval.

## Step 4 — identity links, all eight roles

All eight returned `DRY_RUN_OK`, exit 0, nothing written. Representative
output:

```text
Research Archive identity linking - DRY_RUN_OK
  WARN   runtime account unknown - a dry run outside ECS cannot verify where it is pointed
  WARN   runtime cluster unknown - a dry run outside ECS can neither confirm the environment nor rule out production
  OK     operator, reason and approval reference recorded for the audit row
  OK     acting administrator is a well-formed archive identifier AR****01
  OK     identity being linked is a well-formed archive identifier AR****01
  OK     Cognito subject is a well-formed immutable sub 11****11
  OK     Cognito issuer matches the user pool
  NOTE   no Kuali PERSON_ID supplied. CENTRAL, UNIT, IO and PARENT_AWARD access work
         without one. CONTACT_DERIVATION does NOT: a mapping with no PERSON_ID never
         matches a contact row, so such a grant would be issued and confer nothing.
         Supply --kuali-person-id for a contact-derivation test identity.
  OK     acting administrator holds an ACTIVE membership
  OK     archive identity has never been linked
  NOTE   a link confers NO access to any record. It establishes who the signed-in
         person is. Access requires a grant, issued separately.
Not confirmed: every check passed, but --confirm was not given. Nothing was written.
```

### The Research Staff identity and its Kuali PERSON_ID

Four runs, because this is the one role where the identifier is not optional.

| Run | `--kuali-person-id` | Outcome |
|---|---|---|
| omitted | — | `DRY_RUN_OK` **with the NOTE above** — the link is valid, the contact grant would confer nothing |
| `NOTANUMBER` | not numeric | `REFUSED`, exit 1 |
| `rstaff@bu.edu` | an email | `REFUSED`, exit 1 |
| `1000570` | the real `award_person.person_id` | `OK Kuali PERSON_ID supplied; contact-derived access can resolve` |

```text
  FAIL   --kuali-person-id must be the numeric Kuali PERSON_ID. A username, an
         email address or an archive identifier is not a PERSON_ID.
Refusing to link: 1 problem(s). Nothing was written.
```

The grant utility checks the other end of the same requirement, so a
mis-ordered provisioning cannot leave a contact grant conferring nothing:

```text
  NOTE   grantee has no active identity link yet. CONTACT_DERIVATION resolves through
         the Kuali PERSON_ID on that link, so the link must carry one when it is
         created or this grant will confer nothing.
```

## Step 5 — grants, six roles

All six `DRY_RUN_OK`, exit 0, nothing written. Each one's scope line:

| Role | Grant | The line the utility printed |
|---|---|---|
| 1 Central User | `CENTRAL` | `OK CENTRAL takes no scope argument` |
| 2 Department User | `UNIT 1250000000` | `OK unit 1250000000 only, no descendants` |
| 3 Department User, descendants | `UNIT 1250000000 --include-descendants` | `OK unit 1250000000 INCLUDING descendants` |
| 4 Research Staff | `CONTACT_DERIVATION` | `OK CONTACT_DERIVATION takes no scope argument` + the PERSON_ID note |
| 5 SAP IO User | `IO 9500234095` | `OK IO value 95****95 (Awards only; an IO grant never reaches Proposals, Subawards or Negotiations)` |
| 6 Parent Award User | `PARENT_AWARD 100380-00001` | `OK parent Award 100380-00001, expanded at the configured depth - never across Award families` |

Every one carried the expiry caveat:

```text
  OK     expires at 2027-06-30T00:00:00Z. NOTE V087 constrains UNREVOKED grants, so
         after expiry this grant still occupies its scope until it is revoked.
```

## Roles 7 and 8 — and a control that is weaker than it looks

Roles 7 (Grant Administrator) and 8 (No-Grant Control) are linked and receive
**no grant**. That is what makes them worth testing: role 7 proves
administering access is not having it, role 8 proves an authenticated user
with no grant sees nothing.

**The utility will not stop you giving them one.** Offered a `CENTRAL` grant,
both returned `DRY_RUN_OK` — every check passed, because nothing in the data
marks an identity as "control". The control is **procedural**: it holds
because the approved plan says six grants and names which six.

Recorded here because a control nobody has tested is worth less than one whose
limits are written down. If a future run names role 7 or 8 as a grantee, the
pilot has spent this control and the comparison it exists for is gone.

## Refusals that did fire

| Attempt | Outcome |
|---|---|
| `--type=CENTRAL --unit-number=…` | `FAIL CENTRAL grants take no scope; remove --unit-number, --io-value, --award-number and --include-descendants` |
| `--type=PARENT_AWARD` with no `--award-number` | `FAIL PARENT_AWARD grants need --award-number` |
| `--grantee=pilot1@bu.edu` | `FAIL grantee looks like an email address. Supply the archive identifier (ARP-XXXXXXXX-XXXXXXXX); an email is not an identity here.` |
| actor is role 8, not an administrator | `FAIL the acting administrator AR****08 holds no ACTIVE ARCHIVE_ADMIN membership. Grants must be issued by a real administrator.` |
| revocation with no `--aws-account-id` / `--environment` | `FAIL … is required` ×2 |

Each one: `Refusing to provision: N problem(s). Nothing was written.`, exit 1.

## PARENT_AWARD depth 0 and depth 1

Depth is a **policy setting** (`app.authorization.parent-award-depth`), not a
per-grant flag — the utility says "expanded at the configured depth". Default
**1**, maximum 10, negative values rejected at startup.

The pilot's fixture, confirmed against the live DEV API on 2026-11-11:

```
100380-00001   root        parent 000000-00000
100380-00002 … 100380-00009   eight direct children, all parent 100380-00001
```

Nine Awards, one root, eight direct children, every row sharing
`root_award_number = 100380-00001`. So:

| Depth | Reaches | Why this fixture |
|---|---|---|
| 0 | **1** Award — the granted one alone | `JdbcAwardHierarchy` short-circuits before querying, so an Award with no hierarchy row still reaches itself |
| 1 | **9** Awards — root plus eight children | eight children make expansion unambiguous; a single-child parent cannot distinguish "expansion worked" from "expansion did nothing" |

Covered by tests, all passing:

- `AwardHierarchyExpansionIntegrationTest` — "depth 0 is the granted Award alone", "depth 1 reaches direct children and stops" (asserting a grandchild is **not** reached), "depth 2 reaches grandchildren, depth 3 reaches the whole chain"
- `SecurityAcceptancePackIntegrationTest` cases **21-24** — the granted Award always reaches itself; a child is reached at depth 1; **another Award family is denied however the hierarchy is shaped**; at depth 0 the grant is the Award alone
- `ParentAwardAccessTest` — a child within depth permitted, a grandchild beyond depth denied, an unrelated family denied, and the configured depth is the depth actually passed to the hierarchy

## Expiry, revocation and cleanup

Three separate facts, demonstrated against the real `V087` index rather than
described.

**1. Expiry does not free the scope.** `ux_access_grant_active_scope` is
UNIQUE on (identifier, type, unit, io, award) `WHERE revoked_at IS NULL`.
`expires_at` is not in that predicate. A grant that expired a year ago, left
unrevoked, still blocks its replacement:

```
ERROR:  duplicate key value violates unique constraint "ux_access_grant_active_scope"
DETAIL:  Key (institutional_identifier, grant_type, COALESCE(unit_number,''), …)
         =(ARP-PKT00001-00000001, CENTRAL, , , ) already exists.
```

**2. Revocation frees it, and keeps the history.** After revoking, the row is
still present (`rows_retained = 1`) and the replacement grant succeeds.
Revocation is a status change, never a delete.

**3. Closing the pilot therefore means revoking, not waiting.** Letting the
grants expire on 2027-06-30 would leave eight scopes occupied by grants that
confer nothing and cannot be reissued.

The CLI revocation path, dry-run, resolves the grant before it will act:

```text
Research Archive grant provisioning - DRY_RUN_OK
  OK     revoking by immutable grant_id 5
  OK     grant 5 is live: UNIT unit 1250000000 only for AR****02
  OK     acting administrator holds an ACTIVE membership
Not confirmed: every check passed, but --confirm was not given. Nothing was written.
```

The grant was still live afterwards — the dry run changed nothing.

Covered by `GrantProvisioningIntegrationTest` (63 tests, all passing):
"An expired grant is not a revoked one" (4), "Optional expiry (D-ADM-3)" (5),
"Revocation by immutable grant id" (11), "Refusals leave nothing behind" (16),
"Writes nothing unless confirmed" (2).

## What was written, and by what

| Table | Rows after | Written by |
|---|---|---|
| `authz.access_audit` | **0** | nothing — this is the proof no provisioning occurred |
| `authz.identity_link` | 1 | the seeded administrator fixture (SQL). **0** from eleven link dry runs |
| `authz.admin_role_member` | 1 | the seeded administrator fixture (SQL) |
| `authz.access_grant` | 3 | the expiry/revocation demonstration above (SQL). **0** from seven grant dry runs |

The three grant rows exist because demonstrating that expiry does not free a
scope requires a grant to expire. They were created by explicit SQL against
the disposable container, never by a utility, and never against DEV.

## Approval still required

| Step | Writes | Approval |
|---|---|---|
| Register the two operator task definitions | ECS task definitions | ☐ |
| 4 — link the eight identities | `authz.identity_link` | ☐ |
| 5 — issue the six grants | `authz.access_grant` | ☐ |

Six grants, not eight. The identifiers used above are placeholders; real
pilot identities come from BU IAM and the Cognito user pool and are handed
over out of band, never committed — `PHASE2_PILOT_PLAN.md` section 4b.
