# Archive-issued identity — removing the BU IAM dependency

Design recommendation, 2026-10-28. **Nothing deployed, migrated, bootstrapped
or enabled.** The migration in this change is written and tested, not applied.

---

## 1. Recommendation

**Yes — `institutional_identifier` can be an archive-issued identifier, and it
is a better key than any of the BU-owned alternatives.** The dependency on BU
IAM is removable for *authorization*, and should be removed.

The question that has blocked activation is "which BU attribute identifies a
person?" — a question only BU IAM can answer. This replaces it with one the
archive can answer itself: **the archive issues its own identifier, and an
administrator decides which human it belongs to.**

| | |
|---|---|
| **Authentication** | unchanged — BU Shibboleth + Duo through Cognito SAML |
| **Authentication identity** | unchanged — `(cognito_issuer, cognito_subject)`, immutable |
| **Authorization key** | **`ARP-XXXXXXXX-XXXXXXXX`**, minted by the archive |
| **Kuali principal** | kept on the link as a historical crosswalk, **never a grant key** |
| **Schema change needed** | **one partial unique index** (V085) |

Nothing about who may sign in changes. What changes is what a grant is
attached to.

## 2. Why an archive-issued identifier is better, not merely available

Every candidate is owned by someone. The question is who, and what they can do
to it without telling us.

| Candidate | Owned by | Failure mode |
|---|---|---|
| Email address | BU | reassignable. A grant follows the **address**, so a new holder of `jsmith@bu.edu` inherits the old one's access |
| Cognito username | AWS/Cognito | derived from the IdP name + NameID; changes if federation is re-created |
| SAML NameID (`eppn`) | BU IdP | the likeliest BU answer, but **unconfirmed** — and here it is email-shaped, so it may carry the same reassignment risk |
| Kuali `PRNCPL_ID` | a **retired** system | no longer issued; 61% of referenced principals are inactive; a new BU employee has none at all |
| Cognito `sub` | AWS/Cognito | immutable *within a pool*, and gone when the pool is replaced — grants would die with it |
| **`ARP-…`** | **the archive** | nobody else can change or reassign it |

The decisive one is the Kuali row. The archive is the retirement platform for
Kuali. Keying its access on a Kuali identifier means a system that no longer
runs decides who can read the archive of itself — and it cannot issue an
identifier for anyone hired since it was retired.

## 3. The model

```
BU Shibboleth + Duo
        │  (unchanged)
        ▼
Cognito  issuer + sub        ← authentication: who signed in
        │
        │  authz.identity_link, created by an ARCHIVE_ADMIN who
        │  verified the person out of band. method='ADMIN_VERIFIED'
        ▼
ARP-7F3K9QM2-XZ4W8B6N        ← the archive's own identity
        │
        ├─ authz.access_grant      CENTRAL / UNIT / IO / CONTACT_DERIVATION
        ├─ authz.person_status     suspension
        └─ authz.admin_role_member ARCHIVE_ADMIN (separate table, no data access)

        kuali_person_id stays ON THE LINK — historical crosswalk only
```

`ARP-` is Crockford base32 (no I, L, O, U, so a transcription slip is a
*rejected value* rather than a different person's identity), 80 bits of
randomness, minted with `SecureRandom` and **never derived from anything about
the person**. `mint()` takes no argument, which is the structural guarantee.

## 4. ⚠️ A real hole this exposes, and the smallest safe fix

`authz.identity_link` guarantees **one active link per Cognito identity**
(`ux_identity_link_active_cognito`). It does **not** guarantee the reverse.
`ix_identity_link_institutional` is a plain index, so two different Cognito
subjects could both hold active links to the same identifier — **and both
would resolve to it and inherit every grant it holds**.

`IdentityResolver` does not catch it. Its ambiguity check asks
`activePersonIdsFor(identifier)`, and that SQL filters
`kuali_person_id IS NOT NULL` — an administrator-verified link has no Kuali
principal, so the check sees nothing and returns `Mapped`.

This is true today. It becomes **materially more dangerous** under this model:
a BU-issued identifier can at least be cross-checked against an external
source, while an archive-minted opaque id has nothing to check against except
the administrator's own record.

**Smallest safe change — the whole schema delta:**

```sql
CREATE UNIQUE INDEX IF NOT EXISTS ux_identity_link_active_identity
    ON authz.identity_link (institutional_identifier)
    WHERE status = 'ACTIVE';
```

Additive, alters no data, and partial so revoked links stay repeatable — which
is exactly what allows re-linking after a pool replacement. It **fails** if the
table already holds a violation, which is the right outcome: that would mean
two people already share one identity and it must be resolved deliberately.

`V082`–`V084` need no other change. `institutional_identifier` is already
`TEXT`, so the format needs no migration.

## 4b. ⚠️ A second hole, found by probing this design

V085 stops two **active** links sharing an identity. It does not stop this:

```
link person A → ARP-X ;  grant CENTRAL to ARP-X ;  revoke A's link
link person B → ARP-X            ← permitted: the old row is REVOKED
```

**Person B inherits person A's grant.** Probed against a real database before
being believed: B resolved to `ARP-X` holding one active grant.

And at the schema level this is **the same two statements** as a legitimate
re-link after a Cognito pool replacement — revoke the old link, create a new
one. No constraint can tell them apart. Only a human knows which it is.

**Resolution: split the operation.**

| | |
|---|---|
| `link(...)` | refuses an identity with **any** prior history, active or revoked |
| `relink(...)` | the named, separately audited act for "same person, new subject". Audited as `IDENTITY_RELINKED`, with **how many grants moved with it** recorded in the detail |

So handing someone else's identity to a new person is no longer a side effect
of ordinary linking — it requires deliberately choosing the re-link path, and
the audit row says how much access travelled.

## 4c. Issuance lifecycle, and safe retry

| Step | What persists | Retry is safe because |
|---|---|---|
| Mint | **nothing** — the value exists only in memory until written | nothing is reserved, so an abandoned mint leaves no trace |
| Dry run | **nothing** — and no sample identifier is even displayed | a dry-run value could never be mistaken for the real one |
| Checks fail on a confirmed run | **nothing** — minting happens past every check | the next attempt starts clean |
| Membership written, link fails | membership only | the identifier is recorded in `admin_role_member`; link it through the API |
| Both written | both, paired in audit | a second bootstrap is refused outright |

Minting is **not** idempotent and deliberately so: there is nothing to make
idempotent, because an identifier means nothing until a human attaches it to a
person. Re-running produces a different value and no orphan.

## 4d. Two-person rule for re-linking an identity that holds grants

Approved 2026-10-28. Ordinary grants and revocations still need **one**
administrator (D-ADM-2). Re-linking is different in kind: the grants hang off
the archive identity, so pointing it at a new Cognito identity hands that
person everything it holds. One administrator should not be able to do that
alone.

**Identities with no active grants keep the single-administrator procedure.**
There is nothing to transfer, and the extra ceremony would only discourage the
legitimate pool-replacement case.

### Not a second role-management system

Approvers are ARCHIVE_ADMINs from `authz.admin_role_member`. `V086` holds
**approvals**, not roles — individual decisions bound to one identity and one
target, consumed once.

### How each requirement is held

| Requirement | Where |
|---|---|
| Bound to the exact identity **and** proposed issuer/subject | all three are columns, all three are compared on lookup *and* re-asserted by `RelinkApproval.covers(...)` |
| Cannot be reused for another identity or target | the binding above; `ux_relink_approval_live` also permits one live approval per target |
| A revoked or changed approval authorises nothing | only `APPROVED` is usable; `REVOKED` and `PENDING` are refused by status |
| Grant state rechecked at execution | the count is taken **at relink time**, never from the request — so an identity that gains a grant after approval still requires one, and one that loses all its grants no longer does |
| Approver ≠ requester | `ck_relink_approval_two_people` (a **database** CHECK), plus a readable service refusal |
| Approver ≠ the identity being linked | the service refuses when the proposed Cognito identity is the approver's own active link — one person on both sides |
| Concurrency cannot bypass it | `consume()` is a single conditional `UPDATE … WHERE status='APPROVED'`; concurrent relinks serialise and exactly one consumes |
| Auditable | `RELINK_APPROVAL_REQUESTED`, `RELINK_APPROVAL_GRANTED`, `RELINK_APPROVAL_REVOKED`, `IDENTITY_RELINKED` — and the relink row records **how many grants moved** and **under which approval** |
| Bootstrap unaffected | it links a freshly minted identity with no history and no grants, so it never reaches this path |

### Who may execute

Deliberately not constrained. The two-person rule is about the **decision**,
and that is held where it cannot be bypassed — `approved_by <> requested_by`
as a database constraint. Whoever runs the resulting relink can only do what
two administrators already agreed to, against that exact identity and target.

### Migration impact

**`V086` only.** One new table and two indexes; `V082`–`V085` are untouched.
Additive, no data altered, and `relink_approval` is read by nothing until a
relink is attempted against an identity holding grants.

### Not yet exposed over HTTP

`requestRelinkApproval`, `approveRelink`, `revokeRelinkApproval` and
`relinkIdentity` are service-layer only, consistent with `relink` itself.
`RelinkApprovalRequiredException` maps to **409** when they are exposed.
Adding the four endpoints is a follow-up, tracked rather than assumed.

## 5. Cognito replacement, federation re-creation, account linking

| Scenario | Effect | Recovery |
|---|---|---|
| **User pool replaced** | every `sub` changes; every link breaks; **every grant survives**, because grants key on `ARP-…` not on `sub` | revoke the old links, create new ones. N re-links, zero re-grants |
| **SAML federation re-created** | Cognito username changes; `sub` *may* change | same as above. Username is display-only and nothing keys on it |
| **Same person signs in a second way** | a second Cognito user with a different `sub` → `NotProvisioned`. They get nothing | an administrator links it deliberately — and must revoke the first, because V085 permits only one active link per identity |
| **Administrator mis-links person B to person A's identity** | **refused** — by V085, and by a readable message above it | mint a separate identity for B |

> The pool-replacement row is the one worth noticing. Keying grants on the
> Cognito `sub` would lose every grant when the pool is replaced. Keying on an
> archive identifier means re-linking is clerical and the access model is
> untouched. This is **safer** than the BU-issued option on recovery, not
> merely equal.

**Residual risk, stated plainly:** re-linking after a pool replacement is
manual and proportional to the number of users. At dev scale that is minutes;
at full BU scale it would want tooling. That tooling is not built, and is not
needed to activate dev.

## 6. What does not change

- **Authentication** — BU Shibboleth with Duo, through Cognito. Untouched.
- **No automatic grant from email, username or KRIM status.** Automatic
  enrollment stays disabled. Historical `ACTV_IND` still decides nothing
  (D-ID-1). An administrator links; nothing infers.
- **Administration stays separate from data access.** `admin_role_member` is
  its own table, not a `grant_type`; an ARCHIVE_ADMIN with no grant sees no
  records and the UI says so.
- **ARCHIVE_ADMIN can still verify, link, grant, revoke and suspend** — the
  12 endpoints from PR #74 work unchanged, because the identifier's *format*
  changed and its *role* did not.
- **The Kuali crosswalk survives.** `kuali_person_id` stays on the link for
  historical research relationships, and `authz.kim_principal` /
  `principal_crosswalk` (V083) are untouched.

## 7. Tests

`ArchiveIssuedIdentityIntegrationTest`, 13 tests on disposable PostgreSQL,
registered in **both** `ci.yml` lists.

| Property | Tests |
|---|---|
| **Cannot collide** | 20,000 mints with no repeat; two mints differ; transcription slips (I, L, O, U, wrong length, an email) are **rejected, not resolved**; case and spacing normalised but nothing else repaired |
| **Cannot be reassigned** | a second Cognito identity cannot take a linked archive identity; **the database holds it even when the application check is bypassed**; revoking releases it for re-linking and keeps the old row as history; one Cognito identity still cannot hold two archive identities |
| **No inherited grants** | a mis-linked second person resolves to `NotProvisioned`, so there is nothing to inherit; each person resolves only to their own; revoking a link leaves the grant with the archive identity rather than moving or lapsing it; an unlinked identity resolves to nothing however many grants exist |
| Crosswalk preserved | the KRIM principal is recorded on the link while the grant keys on `ARP-…` |

**Verified load-bearing:** with V085 removed and the application check
disabled, **three of these fail**. The fix is not decorative.

## 8. What this unblocks, and what it does not

**Unblocked.** D-ID-3 and D-ID-2(c) stop blocking activation. The bootstrap can
run with a minted `ARP-…`, with no provisional-identifier compromise and no
re-keying obligation later.

**Still needed, unchanged:** rebuild the loader image, apply V082–V085, rebuild
and deploy the API image, run the bootstrap, acceptance-test, enable
enforcement.

**Still worth asking BU IAM**, now as an improvement rather than a blocker:
whether a stable institutional identifier exists. If one is confirmed later it
can be recorded **alongside** the archive identity as a second crosswalk
attribute, enabling automated re-linking after a pool replacement. It would
not need to become the grant key, and it should not.

## 9. Decision requested

| | |
|---|---|
| **D-ID-5** | Adopt the archive-issued identity model, making `institutional_identifier` an `ARP-…` value and closing D-ID-3 as a blocker. **Recommended.** |
| | Apply V085 as part of the V082–V084 migration step |
| | Treat a future BU identifier as an optional crosswalk, never the grant key |
