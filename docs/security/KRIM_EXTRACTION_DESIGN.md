# Targeted KRIM extraction for identity mapping — design

Investigation and design, 2026-10-27. **Nothing implemented**, no migration
applied, no Oracle query run against production. Coverage measured
read-only against dev PostgreSQL. No names, emails or identifiers here.

Companion to
[`IDENTITY_MAPPING_INVESTIGATION.md`](IDENTITY_MAPPING_INVESTIGATION.md).

---

## 1. The precedent already exists and is correct

`sql/extract/reference/05_person.sql`, loaded by
`reference_data.load_person_reference_data()`, already does targeted KRIM
extraction. Its own header records the facts this design depends on, each
verified live rather than assumed:

- there is **no `PERSON`/`KC_PERSON` table** in BU's real schema; identity
  lives in Rice KIM;
- **`KRIM_PRNCPL_T.PRNCPL_ID` IS the `PERSON_ID`** used throughout KCOEUS
  (`UNIT_ADMINISTRATOR.PERSON_ID`, `AWARD_UNIT_CONTACTS.PERSON_ID`) —
  "confirmed by a live join, not assumed";
- `DFLT_IND='Y'` picks each person's single default name/email/phone, since
  a person may have several of each.

Current query:

```sql
SELECT p.PRNCPL_ID AS PERSON_ID, n.FIRST_NM, n.MIDDLE_NM, n.LAST_NM,
       e.EMAIL_ADDR, ph.PHONE_NBR
FROM KRIM_PRNCPL_T p
LEFT JOIN KRIM_ENTITY_NM_T    n  ON n.ENTITY_ID  = p.ENTITY_ID AND n.DFLT_IND='Y'
LEFT JOIN KRIM_ENTITY_EMAIL_T e  ON e.ENTITY_ID  = p.ENTITY_ID AND e.DFLT_IND='Y'
LEFT JOIN KRIM_ENTITY_PHONE_T ph ON ph.ENTITY_ID = p.ENTITY_ID AND ph.DFLT_IND='Y';
```

Driven by `OracleDataSource.read_filtered(column="PERSON_ID", values=…)` —
a bind-variable read, never a full scan.

### What it does NOT select

**`PRNCPL_NM`, `ENTITY_ID` and `ACTV_IND` are all absent.** Those are
precisely the three columns identity mapping needs:

| Column | Needed for |
|---|---|
| `PRNCPL_NM` | the Kuali principal *name* — the most likely eppn counterpart |
| `ENTITY_ID` | detecting several principals for one human |
| `ACTV_IND` | refusing inactive principals (currently impossible — see §4) |

So the extraction change is **additive and small**: three columns, plus a
wider driving set.

---

## 2. Measured coverage (dev, read-only)

### Distinct `person_id` by source

| Source | distinct `person_id` |
|---|---|
| `proposal_person` | 4,063 |
| `award_person` | 3,258 |
| `negotiation_search_attribute.principal_investigator_person_id` | 1,238 |
| `negotiation_unassociated_detail.pi_person_id` | 1,150 |
| `award_unit_contact` | 1,101 |
| `unit_administrator` | 128 |
| `negotiation_unassociated_detail.contact_admin_person_id` | 92 |
| `negotiation.negotiator_person_id` | 16 |

| | |
|---|---|
| **Grand union across all four modules** | **5,917** |
| Loaded in `archive.person` today | **467** |
| Missing | **5,450** |
| **Coverage today** | **7.89%** |

### ⚠️ Two findings in that table

**Subaward has no `person_id` contacts at all.** `archive.subaward_contact`
carries `rolodex_id` and `requisitioner_id`, not `person_id`. Subaward
contacts are therefore **not KRIM principals by this route**, and a
contact-based identity path does not exist for that module. Not a defect —
it is how Kuali models them — but it bounds what identity mapping can ever
do for Subaward.

**The current targeted load is itself incomplete.** The driving set
(`unit_administrator ∪ award_unit_contact`) is **1,127** person_ids, but
only **467** resolved into `archive.person` — **41%**.

> **RESOLVED 2026-10-27 — neither of the two hypotheses below was right.**
> All 660 unresolved ids **are** valid KRIM principals (660/660 verified
> against Oracle staging), and the extraction query is correct. The cause
> is that `archive.person` is a point-in-time snapshot loaded on
> 2026-08-03 and never re-run, while `award_unit_contact` kept loading
> until 2026-08-12 — all 660 arrived after the person snapshot. The real
> KRIM hit-rate is therefore ~100%, now measured rather than assumed.
> Full evidence, plus the unplanned finding that **61% of referenced
> principals are inactive in KRIM**, is in
> [`KRIM_LOCAL_IMPORT_PLAN.md`](KRIM_LOCAL_IMPORT_PLAN.md) §2.

Original open question, retained for the record: either 660 of those ids
are not principals in `KRIM_PRNCPL_T`, or the load did not finish. **This
must be determined before trusting any coverage estimate**, because it
means the real KRIM hit-rate for the wider set is unknown rather than
assumed-100%.

---

## 3. Proposed extraction changes

### 3a. Widen the driving set

`_referenced_person_ids()` currently unions two tables. It should union
every contact-bearing column that holds a `PRNCPL_ID`:

```
archive.award_person.person_id
archive.proposal_person.person_id
archive.award_unit_contact.person_id
archive.proposal_unit_contact.person_id
archive.unit_administrator.person_id
archive.award_budget_person.person_id
archive.negotiation.negotiator_person_id
archive.negotiation_search_attribute.principal_investigator_person_id
archive.negotiation_unassociated_detail.pi_person_id
archive.negotiation_unassociated_detail.contact_admin_person_id
```

5,917 bind values — still bounded and targeted, still nothing like a full
`KRIM_PRNCPL_T` scan. `read_filtered` already chunks bind lists.

**Deliberately excluded:** `archive.subaward_contact` (no `person_id`),
`archive.protocol_person` / `protocol_unit` (**IRB — out of scope**).

### 3b. Add three columns to `05_person.sql`

```sql
SELECT p.PRNCPL_ID AS PERSON_ID,
       p.PRNCPL_NM,                  -- new
       p.ENTITY_ID,                  -- new
       p.ACTV_IND,                   -- new
       n.FIRST_NM, n.MIDDLE_NM, n.LAST_NM,
       e.EMAIL_ADDR, ph.PHONE_NBR
FROM KRIM_PRNCPL_T p
LEFT JOIN KRIM_ENTITY_NM_T    n  ON n.ENTITY_ID  = p.ENTITY_ID AND n.DFLT_IND='Y'
LEFT JOIN KRIM_ENTITY_EMAIL_T e  ON e.ENTITY_ID  = p.ENTITY_ID AND e.DFLT_IND='Y'
LEFT JOIN KRIM_ENTITY_PHONE_T ph ON ph.ENTITY_ID = p.ENTITY_ID AND ph.DFLT_IND='Y';
```

Same tables, same join chain, same `DFLT_IND` convention — no new Oracle
objects and no new access.

### 3c. Where the new columns land

`PRNCPL_NM`, `ENTITY_ID` and `ACTV_IND` belong in **`authz.kim_principal`**
(V083), not in `archive.person`: they are authorization inputs, not archive
display data, and V083 already defines `prncpl_id`, `entity_id`,
`actv_ind`, `loaded_at`, `load_ref`.

`PRNCPL_NM` has no column in V083. Either add one in a new migration, or
load it into `authz.principal_crosswalk` as
`attribute_name='prncpl_nm'` — which is what that generic table is for, and
needs no schema change.

### 3d. What this still does not give us

**A verified eppn → `PRNCPL_ID` mapping.** `PRNCPL_NM` is the *likely*
counterpart to the eppn local part, but that is a hypothesis. It must be
confirmed by BU (question 3 below) and, if confirmed, loaded into
`principal_crosswalk` with `evidence_ref` recording the basis. **Do not
infer it by string-matching `PRNCPL_NM` against the eppn local part** — the
Negotiation `associated_document_id` collisions are the cautionary case:
6,073 of 10,775 identifiers matched more than one record kind, and every
one of those matches looked plausible.

---

## 4. Identity resolver gap analysis

| Required behaviour | Status | Evidence |
|---|---|---|
| Missing identity link denies | **Present** | `IdentityResolution.NotProvisioned` when no link |
| Ambiguous match denies | **Present, twice** | `>1` active link → `Ambiguous`; `>1` active person for one institutional id → `Ambiguous`. Never picks one |
| **Inactive KRIM principal denies** | **❌ MISSING** | `actv_ind` appears nowhere in `api/src/main/java`. `authz.kim_principal.actv_ind` is modelled, loaded by nothing, read by nothing |
| Revoked link denies immediately | **Present** | `IdentityResolution.Revoked`; resolution runs per request, nothing memoised |
| Suspended user denies | **Present** | `links.isSuspended(institutionalIdentifier)`, independent of link state |
| **Email/username change cannot silently transfer permissions** | **⚠️ UNDEFINED** | The link is keyed on `(cognito_issuer, cognito_subject)`. A changed email does not move the link — good. But nothing re-validates that the *evidence* the link was created from still holds, and no behaviour is specified |
| ARCHIVE_ADMIN cannot bypass identity verification | **Present** | `ArchiveAdminService` javadoc: administration "never grants the caller or the subject any archive record access"; an admin "with no `authz.access_grant` row sees no records". Test class `AdministrationIsNotDataAccess` exists |

### Required corrections

1. **Refuse inactive principals.** `IdentityResolver.resolve` must check
   `kim_principal.actv_ind = 'Y'` for the resolved `prncpl_id` and return a
   denial otherwise. Today a principal deactivated in KRIM keeps working
   until someone revokes the archive link by hand — the gap most likely to
   matter operationally, because deactivation is exactly what happens when
   somebody leaves.
2. **Define attribute-change behaviour.** Decide whether a change to the
   attribute a link was derived from invalidates the link. Recommendation:
   it does, and re-verification is required — the link records
   `evidence_ref`, and evidence that no longer holds should not keep
   granting access.
3. **Nothing loads `authz.kim_principal`.** The table is modelled and
   unreferenced. Correction 1 cannot be implemented until §3 lands.

---

## 5. Request to BU IAM

> **Research Archive — identity mapping questions**
>
> The BU Research Archive authenticates users through Shibboleth → AWS
> Cognito, and must map each authenticated user to their Kuali KRIM
> principal (`KRIM_PRNCPL_T.PRNCPL_ID`) to decide which archived records
> they may see. The archive is read-only and never writes to Kuali. We have
> built the mapping tables but have no data to populate them, and would
> rather ask than infer.
>
> 1. **What stable institutional identifier is available from BU
>    Shibboleth?** Today our Cognito provider receives `eppn`
>    (`urn:oid:1.3.6.1.4.1.5923.1.1.1.6`) and `mail`
>    (`urn:oid:0.9.2342.19200300.100.1.3`). Is there a more stable
>    identifier — a BUID or similar — that we should use instead?
> 2. **Is `eppn` guaranteed stable** across username and email changes, and
>    is it ever reassigned to a different person?
> 3. **Is there an authoritative source mapping `eppn` to
>    `KRIM_PRNCPL_T.PRNCPL_ID`?** We can see `PRNCPL_NM` and `ENTITY_ID`,
>    but we do not want to assume `PRNCPL_NM` equals the eppn local part.
>    If no such source exists, how should we establish the mapping?
> 4. **How are inactive, renamed and merged identities represented?** We
>    expect to honour `ACTV_IND='N'` as a denial. Can one person hold
>    several `PRNCPL_ID`s under one `ENTITY_ID` (for example affiliate and
>    employee), and if so which should authorization use?
> 5. **Can the identifier in (1) be released to our Cognito service
>    provider** as a SAML attribute? Our pool currently carries no custom
>    attributes.
> 6. **What is the approved process for verifying an identity when
>    automatic mapping is ambiguous** — who verifies, and what evidence is
>    recorded?
>
> We are not asking for a directory extract. The targeted read we already
> operate covers only the ~5,900 principals referenced by archived records,
> never the full BU population.

---

## 6. Recommended implementation sequence

Each step separately approvable; none starts before the one above lands.

1. **BU IAM answers questions 1–4.** If no eppn→`PRNCPL_ID` source exists,
   automatic mapping is not buildable and the work becomes
   administrator-verification only. **Stop here until answered.**
2. **Determine why only 467 of 1,127 driving ids resolved** (§2). The
   coverage estimate is not trustworthy until this is explained.
3. **Apply V082–V084 to dev** — migration approval.
4. **Extend `05_person.sql`** with `PRNCPL_NM`, `ENTITY_ID`, `ACTV_IND`;
   widen `_referenced_person_ids()` to the ten columns in §3a.
5. **Load `authz.kim_principal`** from that extraction.
6. **Add the inactive-principal check** to `IdentityResolver` (correction
   1) — independently valuable and testable with no eppn source.
7. **Load `authz.principal_crosswalk`** from the BU-supplied source, with
   `evidence_ref` per row. Only after step 1 confirms one exists.
8. **Implement `InstitutionalIdentityAttributeSource`** to read the
   identifier from the token.
9. **Automatic linking**, gated on proven uniqueness and validity; then
   administrator verification for the remainder.

Steps 2, 3, 4, 5 and 6 are useful regardless of how step 1 is answered.
Steps 7–9 depend entirely on it.

**Enforcement stays OFF throughout.**
