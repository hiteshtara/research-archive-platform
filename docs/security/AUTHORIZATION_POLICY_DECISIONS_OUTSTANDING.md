# Authorization policy — the decisions still outstanding

**Status:** awaiting Anthony's decision. Nothing here is decided by
engineering, and nothing in this file has been applied to any environment.

**Why it blocks:** `AuthorizationProperties.policy()` returns
`Optional.empty()` unless `versionScope`, `departmentMatch`,
`researchStaffRoles` **and** `contactDerivation` are all set. With enforcement
on and the policy empty, the gate denies **every** record
(`ENFORCED_POLICY_NOT_CONFIGURED`). A missing strategy does not fall back to
permissive — it falls back to a total record outage. `maxSignInAge` has no
default either and is required when enforcement is on.

So all five must be set **in the same change** that sets
`app.authorization.enforcement-enabled=true`.

---

## 1. Already decided — do not re-litigate

| Setting | Value | Authority |
|---|---|---|
| `app.authorization.department-match` | **`LEAD_UNIT_WITH_DESCENDANTS`** | **D4** — "Department users see only authorized department and descendant records." |
| grant expiry default | **none; expiry is explicit and optional** | **D-ADM-3** — a grant does not expire unless an administrator sets an expiry. Now expressible via `--expires-at` |
| second approver for a grant | **not required** | **D-ADM-2** |

Note on D4: `LEAD_UNIT_WITH_DESCENDANTS` is permissive *only in combination
with a grant that sets `includeDescendants`*. `RecordAccessEvaluator`
expands the hierarchy only when **both** are true, so the policy value alone
widens nothing.

---

## 2. Outstanding — Anthony's decisions

### 2.1 `app.authorization.version-scope` — `PER_VERSION` | `FAMILY_WIDE`

Whether a grant that reaches one version of an Award reaches the whole family.

| | `PER_VERSION` | `FAMILY_WIDE` |
|---|---|---|
| Record visibility | decided per version row | any version in scope ⇒ whole family |
| AI endpoints (summary, questions) | **denied** with `AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS` whenever a family is partially visible | satisfied free of charge |
| Subaward inheritance (D6a) | Award clause evaluated per version | Award clause is family-wrapped |
| Risk | a user sees a fragmentary history and an AI feature that mostly refuses | a user authorized for one version sees versions they were not individually granted |

The **derived family-completeness rule** exists because of this: a response
spanning every version of a family requires every version to be visible, or
an AI summary could be synthesised from versions the caller may not read and
presented as the whole Award.

**Question for Anthony:** if someone is authorized for an Award's current
version, should they see that Award's entire history?

### 2.2 `app.authorization.contact-derivation` — `EXPLICIT_GRANT` | `VERIFIED_PRINCIPAL`

| | `EXPLICIT_GRANT` | `VERIFIED_PRINCIPAL` |
|---|---|---|
| Who gets contact-derived access | only identities holding a `CONTACT_DERIVATION` grant | any identity whose ACTIVE link carries a verified KIM `PERSON_ID`, while that principal is a qualifying contact on at least one record |
| Administrative load | a grant per person | none |
| Risk | research staff cannot see their own records until granted | access appears and disappears as Kuali contact rows change, with no grant row to review |

**Moot for the first DEV grant:** the bootstrapped identity's link has
`kuali_person_id = NULL`, and `AccessScopeResolver` never matches contact
derivation without a PERSON_ID. The value still has to be set for `policy()`
to be non-empty.

**Question for Anthony:** should being a listed contact on a record grant
access by itself, or must access always be granted explicitly?

### 2.3 `app.authorization.research-staff-roles`

The set of Kuali role names that count as research staff for
`VERIFIED_PRINCIPAL`. **No decision record exists and engineering cannot
supply it** — these are BU role names, not a technical choice. `policy()`
requires the set to be non-empty even under `EXPLICIT_GRANT`.

**Question for Anthony:** which Kuali roles are "research staff"? If
`EXPLICIT_GRANT` is chosen, a single placeholder role is enough to make the
policy valid — but it should still be a real role name, not an invented one.

### 2.4 `app.authorization.max-sign-in-age`

How long since the caller's last BU sign-in (`auth_time`) an enforced request
will accept. A Cognito refresh keeps the original `auth_time`, so without
this a refresh token (30 days) keeps a session alive with no new BU login and
no per-session re-check.

Shorter is safer and more disruptive. There is no decision record and no
default.

**Question for Anthony:** what is the maximum acceptable age of a BU sign-in
for someone reading research records?

### 2.5 First grantee, and whether the first grant expires

The dry run deliberately used the administrator's own ARP
(`ARP-FK7QY195-QW5HV7G0`) because it is the only approved identity that
exists. The CLI says so out loud: *"the grantee IS the acting administrator.
ARCHIVE_ADMIN confers no data access, so this grant is what would give them
records."*

**Questions for Anthony:** should the first real grant go to this identity or
to a distinct research-staff identity that must first be enrolled? And should
it carry an `--expires-at`?

---

## 3. What each choice changes, by module

With a UNIT grant on `1242040000` ("ENG BIOMEDICAL ENG"), enforcement on:

| Module | Effect |
|---|---|
| **Award** | `lead_unit_number` match. `FAMILY_WIDE` widens one matching version to its whole family |
| **Proposal** | same lead-unit rule |
| **Negotiation** | D8 hybrid: lead unit **or** a type-gated association. `archive.negotiation` has **no unit column**, so the lead unit arrives via the Award/Proposal association path |
| **Subaward** | D6a: inherited from funding Awards at **FAMILY** level. D7: restricted funding Awards are omitted and leave no trace |
| **IRB** | not a department module — a UNIT grant reaches **nothing**. D9 removes IRB from the Dashboard entirely when enforcement is on, and IRB leaves shared Global Search |
| **Dashboard** | counts scoped only when enforcement is on; counts and pages must come from the same `WHERE` (D2) |
| **Global Search** | same scoping; cross-module indexes fail closed |

---

## 4. Not a decision — a consequence worth stating

V087's index predicate is `revoked_at IS NULL`, which is **not time-aware**.
An expired-but-unrevoked grant still occupies its scope and still blocks a
replacement for the same grantee and scope. Expiry is therefore not
self-cleaning: the lifecycle still ends with an audited revocation. This is
tested (`anExpiredButUnrevokedGrantStillBlocksTheScope`).
