# BU identity → KRIM principal mapping — investigation

Investigation only, 2026-10-27. **Nothing implemented**, no migration, no
enrollment. Read-only dev database via the approved SSM tunnel; Cognito
inspected read-only. No names, emails, identifiers or tokens appear here.

This is open decision #2 in the handoff and blocks enabling enforcement
anywhere.

---

## 1. Current identity flow, and the identifier at each step

```
BU user
  │  SAML, Shibboleth IdP (shib-test.bu.edu)
  ▼
Cognito user pool us-east-1_VJ4ekQ27c, IdP "BUShibboleth"
  │  attribute_mapping (verified live):
  │      email    ← urn:oid:0.9.2342.19200300.100.1.3   (mail)
  │      username ← urn:oid:1.3.6.1.4.1.5923.1.1.1.6    (eppn)
  │  → pool username becomes  BUShibboleth_<eppn>
  ▼
Access token (JWT)
  │  token_use=access, iss, sub (Cognito UUID), username, auth_time, scope
  ▼
JwtCurrentIdentityProvider            identifier: the whole JWT
  ▼
ValidatedCognitoIdentity              identifier: (issuer, subject)
  │  equality and hashCode use ONLY issuer+subject
  ▼
IdentityResolver                      identifier: (issuer, subject)
  │  looks up authz.identity_link
  ▼
IdentityResolution.Mapped             identifiers: institutionalIdentifier
  │                                                + kualiPersonId
  ▼
AccessScopeResolver → grants          identifier: institutionalIdentifier
  ▼
RecordAccessEvaluator / RecordScopeSqlBuilder
                                      identifier: KualiPersonId (contacts),
                                                  unit numbers (grants)
```

**The pivot is `authz.identity_link`.** Everything upstream is Cognito;
everything downstream is KRIM. The link row is the only thing that joins
them, and it is written by an administrator or an enrollment process —
never derived at request time.

### What the code actually keys on

| Step | Identifier | Note |
|---|---|---|
| JWT | `sub` | Cognito UUID, stable per Cognito user, meaningless to KRIM |
| | `username` | for federated users, `BUShibboleth_<eppn>` — **the only place eppn reaches the API** |
| `ValidatedCognitoIdentity` | `(issuer, subject)` | `username` is carried but **not** part of equality |
| `IdentityResolver` | `(issuer, subject)` → link | fails closed on 0, >1, revoked, suspended |
| `IdentityLink` | `institutional_identifier`, `kuali_person_id` | both free TEXT |
| Grants, evaluator | `institutional_identifier`, `KualiPersonId` | |

`InstitutionalIdentityAttributeSource` is an interface with a single method
and, as far as this investigation found, **no production implementation** —
the attribute that would carry the institutional identifier is not yet
sourced from anywhere.

---

## 2. Schema and code findings

### V082 — `authz.identity_link`, `person_status`, `access_grant`

- `identity_link` holds `(cognito_issuer, cognito_subject)` →
  `institutional_identifier` + optional `kuali_person_id`, with
  `method ∈ {AUTO_VERIFIED, ADMIN_VERIFIED}` and
  `status ∈ {ACTIVE, REVOKED}`.
- **A partial unique index already enforces one ACTIVE link per Cognito
  identity** (`ux_identity_link_active_cognito`). The design anticipated
  both automatic and administrator paths from the start.
- `person_status.suspended` is a separate, institution-keyed kill switch.

### V083 — `authz.kim_principal`, `authz.principal_crosswalk`

- `kim_principal(prncpl_id, entity_id, actv_ind)` — a KRIM snapshot, with
  the active flag preserved.
- `principal_crosswalk(attribute_name, attribute_value, prncpl_id, …)` —
  **this is exactly the eppn→PRNCPL_ID table**, generic over the attribute
  name, with evidence and verifier columns and two partial unique indexes
  enforcing one active value per attribute **and** one active principal per
  attribute. The uniqueness this investigation would otherwise have to
  invent is already modelled.

### Tests

`IdentityResolverTest` and `IdentityEnrollmentServiceTest` exist and cover
the resolution states. They are unit tests over the repository port; none
exercises a real crosswalk, because no data exists.

---

## 3. Real-data profiling

### ⚠️ The `authz` schema does not exist in dev

```
schemas present matching ('authz','archive'):  archive
```

V082, V083 and V084 are committed but **have never been applied**. So:

- `authz.identity_link` — **does not exist**; zero verified mappings, trivially
- `authz.kim_principal`, `authz.principal_crosswalk` — do not exist
- `authz.person_status`, `authz.access_grant` — do not exist

Questions 1, 4, 5 and 8 cannot be answered from dev data because the tables
holding that data are not there. They are answered from DDL above instead.

### `archive.person` — the only KRIM-derived identity data present

| | |
|---|---|
| rows | **467** |
| distinct `person_id` | 467 |
| `person_id` shape | 9 characters, **not** all digits (KRIM `PRNCPL_ID`-shaped) |
| with full name / last name | 467 / 467 |
| with email | **466** |
| with phone | 461 |

**Email uniqueness — the one encouraging result.** Of 466 people with an
email: **466 distinct addresses, 0 duplicates, 0 addresses shared by more
than one person**, and **all 466 are `bu.edu`**.

### ⚠️ Coverage is the problem, not uniqueness

| | |
|---|---|
| distinct contact `person_id` across `award_person` + `proposal_person` | **4,685** |
| of those, **having no `archive.person` row** | **4,618 (98.6%)** |
| `archive.person` rows that are unit administrators | 128 |
| …that are Award contacts | 32 |
| …that are Proposal contacts | 56 |

This is by design, not a defect. V056 states it plainly: `archive.person`
"is NOT a full dump of BU's identity system… deliberately scoped, this
phase, to exactly the person_ids referenced by
`archive.unit_administrator` and `archive.award_unit_contact`".

**So the archive today can identify 467 people, and 98.6% of the people who
appear as Award or Proposal contacts are not among them.** Question 7 —
whether the archive holds enough identity records for users who are not
Award/Proposal contacts — is answered emphatically: it does not hold enough
even *for* Award and Proposal contacts.

### Cognito (read-only, live)

| | |
|---|---|
| pool | `us-east-1_VJ4ekQ27c` |
| users | 12 — **6 federated** (`EXTERNAL_PROVIDER`), 6 native |
| federated username shape | `BUShibboleth_<eppn-like>` |
| SAML IdP | `BUShibboleth` |
| attribute mapping | `email` ← mail OID, `username` ← **eppn OID** |
| custom attributes on the pool | **none** |

`custom:entitlement` from SHIBBOLETH-PLAN-3 was **never created**. There is
no group, role or entitlement attribute arriving from Shibboleth — so
nothing in the token can carry authorization directly, which is consistent
with the archive-side grant model and worth stating.

### Question 6 — can eppn be mapped reliably to `PRNCPL_ID`?

**Not from any data the archive currently holds.** There is no eppn column
anywhere in `archive`, and `authz.principal_crosswalk` — the table designed
for exactly this — does not exist in dev and would be empty if it did.

The only bridge candidate present is **email**: `archive.person.email_address`
(unique, all `bu.edu`) against the Shibboleth `mail` attribute. That is an
*inference*, not a mapping, and it is only available for those 467 people.

---

## 4. Evaluating the three approaches

| | A — automatic via stable institutional id | B — administrator-verified | C — controlled hybrid |
|---|---|---|---|
| Coverage today | ≤467 people, and only by inferring from email | any user, one at a time | ≤467 automatic, everyone else manual |
| Correctness | depends on an unproven eppn/mail ↔ PRNCPL_ID relationship | human-verified | automatic only where uniqueness is *proven* |
| Failure mode | **wrong person silently linked** | slow, but wrong links are deliberate acts | ambiguity falls back to a human |
| Live-Kuali dependency | none if a crosswalk is loaded by ETL | none | none |
| Auditability | weak unless evidence is recorded | strong (`verified_by`, `verified_at`) | strong — V083 already has `evidence_ref` |
| Bootstrap | cannot link the first administrator | works from day one | works from day one |

### Recommendation — **C, the controlled hybrid**

Automatic linking only where uniqueness and validity are *proven at link
time*; administrator verification for everything else. Reasons:

1. **A cannot work yet on the evidence.** Mapping eppn→PRNCPL_ID requires
   data the archive does not have. Inferring it from email would mean
   trusting an undocumented relationship for an authorization decision —
   exactly the shape of mistake the Negotiation
   `associated_document_id` collisions would have been.
2. **B alone does not scale and is not needed** — where a crosswalk row is
   loaded with evidence and both unique indexes hold, a human adds nothing.
3. **C is what the schema already models.** V083's `evidence_ref`,
   `verified_by` and the two partial unique indexes exist precisely to let
   an automatic link be recorded with proof and to make ambiguity
   structurally impossible.
4. **No live-Kuali dependency in the request path**, which was a hard
   requirement. The crosswalk is ETL-loaded; request-time resolution reads
   only `authz` tables.

**Automatic is permitted only when all of:** an ACTIVE
`principal_crosswalk` row matches the asserted attribute exactly; its
`prncpl_id` has `kim_principal.actv_ind = 'Y'`; exactly one row matches in
both directions; and `person_status` does not mark the institutional
identity suspended. Anything else → administrator verification, and until a
human acts the user is **not provisioned**.

---

## 5. Failure behaviour — all fail closed

The resolver already implements most of this; the table states intent.

| State | Behaviour | Where |
|---|---|---|
| No KRIM match | `NotProvisioned` → 403 `ACCESS_NOT_PROVISIONED`, no records | `IdentityResolver`, present |
| Multiple KRIM principals match | `Ambiguous` → denied. **Never pick one** | present (two checks: >1 active link, >1 active person for the institutional id) |
| Principal inactive (`actv_ind='N'`) | denied | **not yet implemented** — `kim_principal.actv_ind` is unread |
| Identity link revoked | `Revoked` → denied; takes effect on the next request | present |
| No archive grants | provisioned but empty scope → `ACCESS_NOT_PROVISIONED` (D1), never an empty archive | present |
| Suspended | `Suspended` → denied, independent of links | present |
| BU email changes | **must not silently relink.** Email is an inference, not a key; a changed address invalidates the evidence and requires re-verification | **needs a decision** |
| KRIM principal name changes | no effect — `PRNCPL_ID` is the key, names are display only | by design |

Two gaps worth naming: **inactive principals are not currently checked**,
and **there is no defined behaviour when the attribute a link was derived
from changes**.

---

## 5a. Follow-up: targeted KRIM extraction

The extraction design, measured coverage and the BU IAM request are in
[`KRIM_EXTRACTION_DESIGN.md`](KRIM_EXTRACTION_DESIGN.md). Headlines:

- the grand union of contact `person_id`s across Award, Proposal and
  Negotiation is **5,917**; `archive.person` holds **467** — **7.89%**;
- `sql/extract/reference/05_person.sql` already does targeted KRIM
  extraction correctly and needs only three more columns —
  `PRNCPL_NM`, `ENTITY_ID`, `ACTV_IND`;
- **Subaward contacts carry no `person_id`** (`rolodex_id` /
  `requisitioner_id` only), so no contact-based identity path exists there;
- the **current targeted load is itself only 41% resolved** — 1,127 driving
  ids produced 467 rows — which must be explained before any coverage
  estimate is trusted.

## 6. Required database / ETL changes (none applied)

1. **Apply V082–V084 to dev.** They are committed and absent. Nothing can
   be tested end to end until they exist. *Migration — requires approval.*
2. **Load `authz.kim_principal`** from `KRIM_PRNCPL_T`
   (`PRNCPL_ID`, `ENTITY_ID`, `ACTV_IND`) — targeted, bounded, the same
   convention V056 established. *ETL work.*
3. **Load `authz.principal_crosswalk`** with `attribute_name='eppn'` — this
   is the missing bridge, and **BU must supply the authoritative source**.
   The archive cannot derive it.
4. **Consider widening `archive.person`** beyond its current 467 if
   per-person identity display is needed; not required for authorization.

No schema change is needed to the existing DDL — V083 already models the
crosswalk correctly, including its uniqueness constraints.

---

## 7. Proposed integration tests (Testcontainers)

Mirroring the four modules already shipped:

- exactly one active crosswalk row + active principal → **Mapped**
- two crosswalk rows for one attribute value → **Ambiguous**, denied
- crosswalk row whose principal is `actv_ind='N'` → denied
- revoked link → denied on the next request (immediate revocation)
- suspended institutional identity → denied regardless of links
- no link at all → `NotProvisioned`, not an empty archive
- link present, zero grants → `ACCESS_NOT_PROVISIONED`
- attribute value changes → the old link does **not** silently follow
- enforcement OFF → none of the above applies; behaviour unchanged
- SQL predicate vs Java evaluator agreement, as for every other module

---

## 8. Questions for BU IAM and Anthony

**BU IAM**

1. **What is the authoritative eppn → `PRNCPL_ID` source?** The archive has
   none. Without it, automatic linking cannot be built at all.
2. **Is eppn immutable at BU?** If it is reassigned or changes on a name
   change, it cannot key an authorization link.
3. **Is `mail` stable and unique per principal**, and is it ever reused?
   The archive's 466 addresses are unique today, but that is a sample of
   467 people.
4. **Can one person hold multiple `PRNCPL_ID`s** (affiliate + employee)?
   `KRIM_PRNCPL_T` permits several principals per `ENTITY_ID`.
5. Should `custom:entitlement` be released after all? It is mapped in the
   plan document but absent from the pool.

**Anthony**

6. **Who may verify an identity link?** V082 requires `verified_by`;
   no role is defined for it.
7. **What happens to existing grants when a link is revoked** — revoked
   with it, or retained for re-linking?
8. **Bootstrap:** who links the first administrator, given that doing so
   requires an administrator?
9. Is **≤467 automatically-linkable people** acceptable for an initial
   phase, with everyone else administrator-verified?

---

## 9. Implementation sequence

Each step is separately approvable; none begins before the one above lands.

1. **Decisions** — questions 1–3 at minimum. Without the crosswalk source,
   stop here.
2. **Apply V082–V084 to dev** (migration approval).
3. **ETL `authz.kim_principal`** from KRIM, targeted.
4. **ETL `authz.principal_crosswalk`** from the BU-supplied source, with
   `evidence_ref` recorded per row.
5. **Implement `InstitutionalIdentityAttributeSource`** to read eppn from
   the Cognito username (`BUShibboleth_<eppn>`) — the only place it reaches
   the API today.
6. **Add the inactive-principal check** to `IdentityResolver`.
7. **Automatic linking**, gated on the four conditions in §4, writing
   `method='AUTO_VERIFIED'` with evidence.
8. **Administrator verification surface** for everything else — overlaps
   the outstanding ARCHIVE_ADMIN interface work.
9. Integration tests per §7, then a real-data agreement check.

**Enforcement stays OFF throughout.** Identity mapping being correct is a
precondition for enabling it, not a trigger.
