# KIM principal crosswalk: import format and validation (prepared, not run on real data)

**Purpose.** The archive resolves a signed-in person to their **existing** KIM principal.
It does this so that Award and Proposal contact relationships (`PERSON_ID` = KIM `PRNCPL_ID`)
give access without anyone re-entering each PI by hand. The crosswalk is the only bridge
between the two.

```
verified sign-in attribute (from BU Shibboleth via Cognito)
  → exactly one ACTIVE crosswalk row
  → an ACTIVE KIM principal (PRNCPL_ID)
  → Award/Proposal contacts with PERSON_ID = PRNCPL_ID, in the approved roles
  → access to those records (Anthony ID 3)
```

**Status:**

- The format and the validator are ready.
- **No real data has been extracted or imported.** Real use needs approved,
  read-only extraction, and **the attribute name is Warren's answer**: see "Open question".

## Boundaries (non-negotiable)

- **A KIM account alone grants nothing.** A principal who is nobody's qualifying contact
  stays "not provisioned".
- **Central, Department and IO access stay explicit grants** (`authz.access_grant`).
  The crosswalk never creates grants.
- **Historical Kuali roles are evidence only** (`lab_evidence.kuali_role_evidence`). They
  are never turned into current grants.
- **Missing, ambiguous, revoked or inactive mappings deny access.** On every sign-in the
  link is re-checked against the crosswalk and the principal, and revoked if either changed.
- **Non-employee (rolodex) contacts are never login accounts.** A rolodex ID is not a KIM
  principal, and is rejected at import and refused at runtime.
- **No email fallback.** Kuali Core matches on email when its primary key misses; the archive
  does not.
- **No login-name (`PRNCPL_NM`) mapping** until BU confirms how login names are retired
  and reassigned. A reused login name must never inherit the previous holder's access.

## Files

There are two tab-separated files, UTF-8, with header rows exactly as shown below.

### `kim_principals.tsv`

This file holds the minimal `KRIM_PRNCPL_T` columns, filtered to the principals named in
the crosswalk.

| Column | Required | Rule |
|---|---|---|
| `prncpl_id` | yes | KIM principal ID, as stored in contact `PERSON_ID` |
| `entity_id` | yes | KIM entity ID, used for ambiguity checks only |
| `actv_ind` | yes | `Y` or `N` |

### `principal_crosswalk.tsv`

| Column | Required | Rule |
|---|---|---|
| `attribute_name` | yes | the **one** approved verified attribute (configured; the lab uses `labInstitutionalId`) |
| `attribute_value` | yes | the value BU releases for this person; whitespace trimmed, never an email address |
| `prncpl_id` | yes | must exist in `kim_principals.tsv` with `actv_ind = Y` |
| `evidence_ref` | yes | where this pairing was verified (ticket or batch ID); no personal data |
| `verified_by` | yes | the person or process that verified it |

## Validation checks (`validate_crosswalk.py`)

**Hard failure: nothing is imported.**

1. Header rows are exact. Every required column is non-empty.
2. Exactly one `attribute_name` value appears, and it equals the configured approved
   attribute.
3. No `attribute_value` looks like an email address. No value contains whitespace or
   control characters.
4. **Uniqueness:** each `attribute_value` maps to exactly one `prncpl_id`, and each
   `prncpl_id` to exactly one `attribute_value`.
5. Every `prncpl_id` exists in `kim_principals.tsv` and is active. Inactive ones are
   reported separately and not imported.
6. No `prncpl_id` is a rolodex (non-employee) ID. Rolodex IDs come from contact rows'
   `rolodex_id` and are supplied as a deny-list.
7. No two principals share an `entity_id` within the import, unless explicitly allowed.
   One entity with several principals is an ambiguity for BU to resolve.

**Report** (counts and row numbers only, never values):

- rows read, accepted and rejected, by check;
- how many accepted principals are a qualifying contact on at least one record.

The rest can sign in but have no contact-derived access.

## Open question for Warren (BU IAM)

> **What verified Shibboleth attribute should we use to resolve the existing KIM principal,
> and what guarantees its ownership over time?**

"Ownership over time" means three things:

- the value is never reassigned to another person;
- it survives a login-name change;
- BU can say what happens to it when a person leaves and returns.

**Stable NameID configuration for the Cognito SP is a separate integration requirement.**
Cognito needs it to recognize a returning user. It does not answer the question above.
