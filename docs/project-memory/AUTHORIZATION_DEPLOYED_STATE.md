# Authorization — what is actually deployed

Corrects documentation that describes intent rather than reality.
Verified 2026-10-29 against live DEV.

| Item | State |
|---|---|
| API | revision **84**, `@sha256:c9f3bc99…`, built from `66f3cfe`, 1/1 healthy |
| Database role | **`archive_api`** — least privilege, no DDL, no DELETE, audit INSERT-only |
| Migrations | **V082–V086** applied |
| First ARCHIVE_ADMIN | **`ARP-FK7QY195-QW5HV7G0`** — membership 3, identity link 4, both ACTIVE |
| Linked Cognito identity | `b438a418-d0a1-7071-2169-023a374a4038` (BUShibboleth SAML, federated) |
| Research-data grants | **zero** |
| Enforcement | **OFF** |
| Admin API | **404 for everyone** while enforcement is off |
| SEC-008 | **unresolved** — see the decision matrix |
| Attachment download | **unverified** — acceptance gap, below |
| Nightly Subaward | `loader:237` |
| Terraform | no drift |

## The administrator can do nothing yet

Holding ARCHIVE_ADMIN confers **no** data access, and the administrative
API does not exist while enforcement is off. So the bootstrapped account
currently has no reachable capability through HTTP. Only direct table
reads confirm it exists. This is by design and is the reason the
activation plan starts with an operator CLI rather than the API.

## Two Cognito users share mukadder@bu.edu

| Username | Status | sub |
|---|---|---|
| `BUShibboleth_mukadder@bu.edu` | EXTERNAL_PROVIDER | **`b438a418-…`** — the linked one |
| `045894f8-40b1-7050-7f25-3b4818356af7` | CONFIRMED (native) | `045894f8-…` — **not** linked |

Selecting by email is ambiguous here. The identity was resolved from the
`sub` claim in a real token and confirmed against the pool. Any future
linking must do the same.

Related: `ALLOW_ADMIN_USER_PASSWORD_AUTH` is still enabled on the app
client. `terraform/modules/cognito/variables.tf` says to leave it false
once federation is live. It is a non-Duo path for any native user and
should be revisited separately.

## IO resolver discrepancy — recommendation

`DisabledIoResolver` says:

> The only IO resolver for real data until the authoritative IO field is
> confirmed (decision D-A). Returns no IO values, so IO grants reach no
> real record. **Do NOT replace with award_version.account_number on the
> strength of the requirements' examples alone.**

Production wires `AwardAccountNumberIoResolver`, which does exactly that
— and carries "APPROVED (Hitesh, 2026-10-01; design rev 3.9, decision
D-A)". So D-A **was** settled after that warning was written, and the
warning is stale.

`DisabledIoResolver` has **no reference in `src/main`**. It is dead in
production.

**Recommendation: delete it.** A dead class whose comment contradicts an
approved decision is worse than no class — the next reader has to work
out which of two documents is current, and the warning reads as
authoritative. If a kill-switch is genuinely wanted, express it as a
property on the real resolver, not as a second implementation nobody
wires. If it is kept for tests, the comment must be rewritten to say D-A
is resolved and this exists only as a test stand-in.

Either way the comment cannot stay as it is.

## Acceptance gaps

**Attachment download is unverified end to end.** The account used for
testing is not in the Cognito group `ArchiveAttachmentViewer`, so both
attachment search and listing return 403. The Postgres→S3 path has never
been exercised by an authorized request. Closing it needs either a group
membership change (a Cognito change, so a separate decision) or a
different tester.

**The Proposal-inheritance branch of the negotiation rule is barely
covered by real data** — only 3 negotiations are of type
`Institutional Proposal`.

**54 negotiations are CENTRAL-only by construction** — 38 of type `None`
and 16 of type `Subaward` have no lead unit and no inheritance path. See
the acceptance matrix.
