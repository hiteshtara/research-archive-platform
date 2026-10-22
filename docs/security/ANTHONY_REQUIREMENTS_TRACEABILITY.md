# Anthony's security requirements — traceability matrix

**Baseline document:** `Kuali Archive Enhancements and Requirements(Security Requirements) (1).csv`
(`~/Downloads`, 28 rows, cp1252 with non-breaking spaces).

**Status:** investigation output. No code, schema, Terraform or authorization
behaviour changed to produce it.

> ## Read this first: the baseline is NEWER than the one already reconciled
>
> `ui/src/features/qa/securityRequirements.json` on `main` already contains a
> reconciled analysis of this document — `sourceDated: 2026-10-01`,
> `reconciledOn: 2026-10-05`, covering **csvId 1–7** as SEC-001…SEC-007, each
> with audience, acceptance, decisions, conflicts and recommendations.
>
> The CSV supplied as the baseline is a **later revision**. Two concrete deltas:
>
> | Delta | Old revision | Baseline `(1).csv` |
> |---|---|---|
> | Requirements present | 1–7 | **1–9** (adds audit logging and read-only) |
> | Req 1 test user | `Username: antcast` | `Username: rochas@bu.edu` |
>
> So the work here is to **extend** the existing matrix with SEC-008 and
> SEC-009 and correct the req-1 fixture — not to rebuild it. Both added
> requirements are ones this investigation was explicitly asked to cover.
>
> Note also that the two CSVs in `~/Downloads` differ; `(1).csv` is the one
> with 9 requirements and is the correct baseline.

---

## Part A — User Group Definitions (CSV rows 1–13)

Verbatim from the source, including its own misspelling of "Currernt".

| Kuali User Role on Contacts Tab | User Group | Notes (verbatim) |
|---|---|---|
| PD/PI Contact | Research Staff | Currernt Kuali Role |
| PD/PI Multiple | Research Staff | Currernt Kuali Role |
| Co-Investigator | Research Staff | Currernt Kuali Role |
| Post-Award - Department Administrator | Department Users | Currernt Kuali Role |
| Other Authorized Viewer | Other Authorized Viewers | Currernt Kuali Role |
| PAFO Administrator | Central Users | Currernt Kuali Role |
| OSP Administrator | Central Users | Currernt Kuali Role |
| Subaward Team | Central Users | Not currently Kuali User Role ("Requisitioner User Name" on subaward) |
| Contracting Team | Central Users | Not currently Kuali User Role |
| Industry Team | Central Users | Not currently Kuali User Role ("Negotiator" on negotiation) |
| Foundation Relations Team | Central Users | Not currently Kuali User Role |
| Research Development Team | Central Users | Not currently Kuali User Role |

**Counts that matter:** 7 of the 12 mappings are Central Users, and **5 of
those 7 are explicitly "Not currently Kuali User Role"**. Central membership
therefore cannot be derived from Kuali role data for the majority of its own
audience. Two of the five name a *data field* instead of a role —
"Requisitioner User Name" on Subaward, "Negotiator" on Negotiation — which is
a different derivation mechanism from the other ten.

**The column header is load-bearing.** It says "Kuali User Role **on Contacts
Tab**", not "KRIM role". Verified against the Kuali source
(`~/kuali-project/kuali-research`, `bu-master`): of the names above, only
`OSP Administrator` (56 files) and `Co-Investigator` (42 files) appear at all
in stock Kuali. `PAFO Administrator`, `Other Authorized Viewer`,
`Post-Award - Department Administrator`, `PD/PI Contact`, `Subaward Team`,
`Contracting Team` and `Industry Team` return **zero** matches. They are BU
configuration and/or contact-role values held in business data, not stock KRIM
role names.

---

## Part B — Requirements traceability (CSV rows 19–27)

Requirement text is verbatim. `SEC-00n` ids follow the existing convention in
`securityRequirements.json`; `csvId` is Anthony's own identifier.

### SEC-001 / csvId 1 — Central Users

> Central users shall have unrestricted access to all system objects and functionality provided by the system.

- **Access Scope:** Entire system
- **Test Case:** Verify a Central User can access all system objects and functionality.
- **Test Object/Users:** `Username: rochas@bu.edu` *(changed from `antcast` in the earlier revision — the earlier fixture is recorded in `securityRequirements.json` and needs correcting)*
- **Mechanism:** explicit grant. Not derivable — see Part A.
- **KRIM fit:** role membership in a Central role, `KRIM_ROLE_MBR_T` with `MBR_TYP_CD='P'`. No qualifier needed (unrestricted).
- **Open conflict (already recorded):** five of seven Central teams have no Kuali role to map from. "All system objects" must also be read against a build that starts with Award.

### SEC-002 / csvId 2 — Department Users

> Department users shall have access to all system objects associated with their assigned department(s).

- **Access Scope:** All objects within assigned department(s)
- **Test Case:** Verify a Department User can access all objects within their assigned department and cannot access objects from unassigned departments.
- **Test Object/Users:** `Lead Unit = 2573180018` / `PAFO Administrator = Nancy Schindele`
- **KRIM fit — strong.** This is exactly what KRIM qualifiers do: a role membership qualified by `unitNumber`, with `subunits` controlling hierarchy descent. Those are the two dominant qualifier names in the Kuali codebase (`unitNumber` 234 references, `subunits` 162).
- **Already in the archive:** `archive.unit` carries `parent_unit_number`, so sub-unit descent is computable locally; `archive.unit_administrator(unit_number, person_id, unit_administrator_type_code)` already maps departments to administrators.
- **Open conflict (already recorded, unresolved):** the role table places PAFO Administrator in **Central Users**, yet the Department User fixture *is* a PAFO Administrator. The two readings give opposite expected results. A department-only test identity is needed, because an unrestricted user would pass the test for the wrong reason.

### SEC-003 / csvId 3 — Research Staff

> Research staff shall have access only to system objects on which they are directly listed as a contact.

- **Access Scope:** Objects directly assigned to the user
- **Test Case:** Verify Research Staff can access assigned objects and cannot access unassigned objects.
- **Test Object/Users:** `PI=LINDSAY A FARRER` can access Award ID/Account `212128-00002:9500316722`; cannot access `211144-00007:9500317253`
- **KRIM fit — weak, and that is correct.** "Directly listed as a contact" is a property of the *record*, not of a role grant. It resolves against archived business data, not KRIM membership.
- **Already in the archive:** `archive.award_person`, `archive.award_person_unit`, `archive.proposal_person`, `archive.proposal_person_unit`, `archive.award_unit_contact`, `archive.proposal_unit_contact`, `archive.subaward_contact`, `archive.contact_type`, and `archive.person` (sourced from `KRIM_PRNCPL_T` — see Part C).
- **Note:** the Award grain rules apply. A person listed on *any* version of an Award family needs an explicit decision about whether that grants the whole family.

### SEC-004 / csvId 4 — Other Authorized Viewers

> Other authorized users shall have access only to specifically authorized system objects identified by IO number.

- **Access Scope:** Specific IO number(s)
- **Test Case:** Verify an Authorized Viewer can access only the specifically authorized IO number(s).
- **Test Object/Users:** `OAV=ANUKRITIKA RAYAMAJHI` can access `209407-00002:9500312705`; cannot access `211144-00007:9500317253`
- **KRIM fit — partial.** Per-record grants are expressible as role-membership qualifiers, but KRIM's qualifier vocabulary is document-type and unit oriented; an IO-number qualifier would be new. This is the requirement most likely to need an archive-side grant table rather than KRIM.
- **Existing status:** the only requirement marked `"Not started"` in `securityRequirements.json`.
- **Terminology to settle:** "IO number" is not an archive column name. The fixtures pair an Award number with an Account number (`209407-00002:9500312705`), so "IO" needs mapping to a real identifier before this is testable.

### SEC-005 / csvId 5 — Granting access

> The system shall support adding a user's access at both the role level and the specific IO number level based on the user's authorized role, department assignment, object assignment, or specific IO authorization.

- **Access Scope:** Role or specific IO number(s) · **Test Object/Users:** TBD
- **Test Case:** Verify an Administrator can add access by assigning a role and/or specific IO number(s).
- **Implication:** requires an administrative surface. KRIM has one in Kuali, but the archive is a separate application and must not write back to Kuali.

### SEC-006 / csvId 6 — Revoking access

> The system shall support removing a user's access at both the role level and the specific IO number level when the user's role, department assignment, object assignment, or authorization changes.

- **Access Scope:** Role or specific IO number(s) · **Test Object/Users:** TBD
- **Test Case:** Verify an Administrator can remove a role and/or specific IO access and that access is revoked.
- **Directly engages the "historical ≠ current" rule** — see Part C.

### SEC-007 / csvId 7 — Enforcement

> The system shall enforce access restrictions when a user attempts to access a system object for which they are not authorized.

- **Access Scope:** Role or specific IO number(s) · **Test Object/Users:** TBD
- **Test Case:** Attempt to access an unauthorized object and verify that access is denied
- **Note:** must fail closed, and must hold at the API, not only in the UI. The existing `AttachmentAuthorizationService` pattern (group checked server-side on every request, UI flag used only to hide affordances) is the precedent to follow.

### SEC-008 / csvId 8 — Audit logging **(NEW in this baseline)**

> The system shall maintain an auditable record of all archive activity, including the user, date and time, and action performed, to support security investigations when necessary.

- **Access Scope:** Role or specific IO number(s)
- **User Story / Test Case / Test Object:** *none supplied in the CSV*
- **Not present in `securityRequirements.json`** — must be added.
- **Scope question to settle:** "all archive activity" — reads as well as writes? For a read-only archive, read logging is the substantive ask, and it is the expensive one.

### SEC-009 / csvId 9 — Read-only enforcement **(NEW in this baseline)**

> The system shall provide the archive in a read-only state to prevent historical records from being modified, updated, or deleted.

- **Access Scope:** Role or specific IO number(s)
- **User Story / Test Case / Test Object:** *none supplied in the CSV*
- **Not present in `securityRequirements.json`** — must be added.
- **Largely already satisfied by design**, and that is worth stating as evidence rather than assertion: the platform is a read-only archive by charter, the API uses `JdbcClient` for queries with no write path back to Oracle, and ETL is one-directional. What is *not* yet established is a **demonstrable control** — a test proving no write path exists, and a statement of what database grants the API role actually holds.

---

## Part C — KRIM investigation findings

Source: `~/kuali-project/kuali-research` at `bu-master` (production branch),
plus Rice bootstrap DDL at
`coeus-db/coeus-db-sql/src/main/resources/co/kuali/coeus/data/migration/sql/oracle/rice_server/bootstrap/V300_002__schema.sql`.

### The seven tables are all present and heavily used

| Table | Files referencing |
|---|---|
| `KRIM_ROLE_T` | 562 |
| `KRIM_PERM_T` | 479 |
| `KRIM_ROLE_PERM_T` | 389 |
| `KRIM_ROLE_MBR_T` | 121 |
| `KRIM_PRNCPL_T` | 110 |
| `KRIM_GRP_T` | 67 |
| `KRIM_GRP_MBR_T` | 50 |
| `KRIM_ROLE_MBR_ATTR_DATA_T` (qualifiers) | 69 |
| `KRIM_ATTR_DEFN_T` (qualifier names) | 242 |

### Real column structure (from DDL and `RoleDaoImpl`)

```
KRIM_ROLE_MBR_T   ROLE_MBR_ID, VER_NBR, OBJ_ID, ROLE_ID, MBR_ID,
                  MBR_TYP_CD, ACTV_FRM_DT, ACTV_TO_DT, LAST_UPDT_DT
KRIM_GRP_MBR_T    GRP_MBR_ID, VER_NBR, OBJ_ID, GRP_ID, MBR_ID,
                  MBR_TYP_CD default 'P', ACTV_FRM_DT, ACTV_TO_DT, LAST_UPDT_DT
KRIM_ROLE_T       ROLE_ID, ROLE_NM, NMSPC_CD, DESC_TXT, KIM_TYP_ID, ACTV_IND
KRIM_PERM_T       PERM_ID, PERM_TMPL_ID, NMSPC_CD, NM, DESC_TXT, ACTV_IND
KRIM_ROLE_PERM_T  ROLE_PERM_ID, ROLE_ID, PERM_ID, ACTV_IND
KRIM_ROLE_MBR_ATTR_DATA_T
                  ATTR_DATA_ID, ROLE_MBR_ID, KIM_TYP_ID,
                  KIM_ATTR_DEFN_ID, ATTR_VAL
```

### How Kuali evaluates authorization

1. **Principal** identified in `KRIM_PRNCPL_T` (`PRNCPL_ID`).
2. **Group membership** via `KRIM_GRP_MBR_T`, typed by `MBR_TYP_CD` and
   **bounded by `ACTV_FRM_DT`/`ACTV_TO_DT`**.
3. **Role membership** via `KRIM_ROLE_MBR_T`. `MBR_TYP_CD` distinguishes
   principal / group / role members, so **roles nest and resolution is
   recursive**. Also date-bounded.
4. **Qualifiers** attached per *membership* in
   `KRIM_ROLE_MBR_ATTR_DATA_T`, each naming a definition in
   `KRIM_ATTR_DEFN_T` and carrying `ATTR_VAL`. This is the record-level
   scoping mechanism. The dominant vocabulary is
   `documentTypeName` (808 references), **`unitNumber` (234)** and
   **`subunits` (162)**.
5. **Permissions** linked by `KRIM_ROLE_PERM_T` to `KRIM_PERM_T`, with
   `ACTV_IND` on the role, the link and the permission.

### "Historical role membership ≠ current authorization" — now evidenced, not assumed

This is not a cautious generality; it is visible in the schema:

- **Both** membership tables carry `ACTV_FRM_DT` and `ACTV_TO_DT`. A row is
  only currently authoritative if *now* falls inside that window.
- **Three** tables in the permission chain carry `ACTV_IND` (`KRIM_ROLE_T`,
  `KRIM_ROLE_PERM_T`, `KRIM_PERM_T`).
- Because `MBR_TYP_CD` permits role-in-role, a membership can be revoked
  several levels away from the principal.

A snapshot of `KRIM_ROLE_MBR_T` copied into the archive and queried without
re-evaluating those dates and flags would therefore **grant access that Kuali
has since revoked** — which is precisely what SEC-006 exists to prevent.

### KRIM is already a dependency of this archive — don't rebuild it

`database/migrations/V056__create_unit_rolodex_person_reference_data.sql`
already sources `archive.person` from KRIM, against a verified live Oracle
probe rather than the open-source reference:

- `KRIM_PRNCPL_T.PRNCPL_ID` = `person_id`
- `KRIM_PRNCPL_T.ENTITY_ID` joins `KRIM_ENTITY_NM_T` (`DFLT_IND='Y'`) for name,
  `KRIM_ENTITY_EMAIL_T` for email, `KRIM_ENTITY_PHONE_T` for phone

It also records an operational constraint to carry forward: **never
full-table-scan `KRIM_PRNCPL_T`** — it covers every BU affiliate. Targeted,
bind-variable reads only.

### What this means for the design

| Requirement | Right mechanism | Why |
|---|---|---|
| SEC-001 Central | Explicit grant, KRIM-shaped (unqualified role membership) | 5 of 7 teams have no Kuali role to derive from |
| SEC-002 Department | **KRIM qualifier pattern** (`unitNumber` + `subunits`) over `archive.unit`'s `parent_unit_number`, with `archive.unit_administrator` | Direct match to how Kuali already scopes by unit |
| SEC-003 Research Staff | **Archived business data** (`archive.award_person`, contact tables) | Contact listing is a record property, not a role grant |
| SEC-004 Other Authorized Viewers | Archive-side per-record grant table | No IO-number qualifier exists in KRIM's vocabulary |
| SEC-005/006 Grant & revoke | Archive-side administration | The archive must never write back to Kuali |
| SEC-007 Enforcement | Server-side, fail closed | Follow `AttachmentAuthorizationService` |
| SEC-008 Audit | New | Nothing exists yet |
| SEC-009 Read-only | Mostly inherent; needs a demonstrable control | Charter and architecture already forbid writes |

**The honest conclusion on "use KRIM wherever appropriate":** KRIM is the right
*model* for SEC-002 and the right *identity spine* throughout (already proven by
V056). It is **not** a sufficient *source of membership* for SEC-001 or SEC-004,
by the source document's own admission, and it is the wrong mechanism for
SEC-003. So the answer is neither "use KRIM for everything" nor "build a
parallel system" — it is a KRIM-shaped qualifier model, fed by archived Kuali
data, with an archive-side grant store for the cases Kuali cannot express.

---

## Part D — There is already an implementation in draft

Five branches carry prior record-authorization work, and
`securityRequirements.json` marks SEC-001/002/003/005/006/007 as
`"In draft, not merged"` with `enforcementState`:

> "Record-level access is switched OFF on the development website. Every
> signed-in user can still reach the whole archive, so none of these
> requirements can be verified yet."

| Branch | Commit subject |
|---|---|
| `feat/authz-identity-policy-core` | identity and policy core for record authorization (stage 1, not enforced) |
| `feat/authz-enforcement` | scope Award and Proposal documents and files for restricted users |
| `feat/authz-enrollment` | — |
| `feat/authz-demo` | — |
| `feat/identity-lab` | — |

**These branches predate current `main` substantially** — the same hazard that
made PR #47 unmergeable. Any revival must be rebased and re-reviewed against
current `main`, not merged as-is.

So "do not create a parallel role-management system" has a second edge: there
is already an authorization stack in this repository, and the first design
question is whether to extend it rather than start again.

---

## Open questions blocking design

1. **PAFO Administrator: Central or Department?** The source contradicts
   itself (Part A vs the SEC-002 fixture). Opposite expected test results.
2. **What is an "IO number"?** Not an archive column. Fixtures pair Award
   number with Account number.
3. **SEC-008 scope:** does "all archive activity" include reads?
4. **SEC-003 grain:** does being a contact on one Award version grant the
   whole family?
5. **The five non-Kuali Central teams:** who maintains that membership, and
   with what approval?
6. **Extend or restart** the existing authz branches.
7. **KRIM data source** — production KRIM, archived KRIM, or a controlled
   copy. *The instruction covering this was truncated; see the note in the
   session report.*

---
---

# Steps 3–7 — investigation report

Investigation only. No database, permission, AWS, Cognito or Shibboleth change;
no authorization code implemented, merged or deployed.

## Part E — Identity mapping: eduPersonPrincipalName → Cognito → KRIM

### The chain, with the real artefacts at each hop

| Hop | Mechanism | Source location |
|---|---|---|
| BU IdP → Cognito | SAML attribute mapping `username ← urn:oid:1.3.6.1.4.1.5923.1.1.1.6` (eduPersonPrincipalName), `email ← urn:oid:0.9.2342.19200300.100.1.3` | `terraform/modules/cognito/main.tf` (PR #55) |
| Cognito identity | JIT user, username `BUShibboleth_<mapped username>`, `UserStatus=EXTERNAL_PROVIDER` | pool `us-east-1_VJ4ekQ27c`, `UsernameAttributes:["email"]` |
| Cognito → API | `cognito:groups` → `ROLE_<group>` | `AttachmentAuthorizationService` line 24–26 |
| Archive person spine | `person_id` = `KRIM_PRNCPL_T.PRNCPL_ID` | `database/migrations/V056__create_unit_rolodex_person_reference_data.sql` lines 30–33 |

### Verified KRIM identity columns

`KRIM_PRNCPL_T` (DDL: `coeus-db/coeus-db-sql/src/main/resources/co/kuali/coeus/data/migration/sql/oracle/rice_server/bootstrap/V300_002__schema.sql`):

```
PRNCPL_ID    VARCHAR2(40)
PRNCPL_NM    VARCHAR2(100) NOT NULL   -- UNIQUE via KRIM_PRNCPL_TC1
ENTITY_ID    VARCHAR2(40)
ACTV_IND     VARCHAR2(1) default 'Y'
```

`KRIM_ENTITY_NM_T` additionally carries its own `DFLT_IND` and `ACTV_IND`.

### Four concrete identity risks, each evidenced

1. **Username changes.** `PRNCPL_NM` is `UNIQUE` but **mutable**; `PRNCPL_ID` is
   the stable key. Any mapping keyed on login name breaks silently on a
   username change and could re-point at a different human if the name is
   later reissued. **Map on `PRNCPL_ID`, never on `PRNCPL_NM`.**
2. **Inactive users.** `KRIM_PRNCPL_T.ACTV_IND` marks deactivated principals.
   A principal row persists after deactivation, so presence ≠ authorization.
3. **Duplicate identities.** `ENTITY_ID` is a separate column from
   `PRNCPL_ID`; multiple principals can share one `ENTITY_ID`, i.e. one human
   with several login identities. `KRIM_ENTITY_NM_T.DFLT_IND='Y'` exists
   precisely because an entity can carry several names.
4. **Missing principals.** `archive.person` is **not** a full principal
   population. `etl/archive_etl/reference_data.py` (lines ~430–432) loads it
   from a targeted set — `SELECT DISTINCT person_id FROM
   archive.unit_administrator` UNION `archive.award_unit_contact`. A
   Shibboleth user who is neither a unit administrator nor an award unit
   contact **does not exist in `archive.person` at all**. The same file
   records the standing constraint: never full-table-scan `KRIM_PRNCPL_T`
   (it covers every BU affiliate).

### The join does not currently exist

`archive.person` columns are `person_id, first_name, middle_name, last_name,
full_name, email_address, phone_number, loaded_at, load_id`. There is **no
username/principal-name column**, and a repository-wide search for
`prncpl_nm` / `principal_name` / `user_name` / `username` in
`database/migrations/` and `etl/sql/extract/` returns **nothing**.

So today a Shibboleth `eduPersonPrincipalName` has **no archive column to join
to**. The only candidate is `email_address`, which is a weak key (sourced from
`KRIM_ENTITY_EMAIL_T` where `DFLT_IND='Y'`, and not guaranteed unique or
stable).

There is also a **format mismatch to resolve, visible in Anthony's own
fixtures**: the earlier CSV revision used `Username: antcast` (bare login
name, the `PRNCPL_NM` shape) and the current baseline uses
`Username: rochas@bu.edu` (scoped, the eppn shape). Whether eppn equals
`PRNCPL_NM`, `PRNCPL_NM + '@bu.edu'`, or neither is **unverified** and cannot
be verified from source alone — it needs a read of real BU KRIM data.

### What the existing draft already got right

`authz.identity_link` (branch `feat/authz-identity-policy-core`,
`database/migrations/V082__create_authorization_identity_and_grants.sql`) keys
on `(cognito_issuer, cognito_subject)` → `institutional_identifier`, carries
`kuali_person_id` as a separate nullable column, and states in-schema that
`login_name` is **"display/lookup only"**. A partial unique index enforces at
most one `ACTIVE` link per Cognito identity, and the comment notes the
resolver denies ambiguity anyway. That is the correct shape against all four
risks above. The same file records that **which BU attribute supplies the
institutional identifier is not yet decided (awaiting BU IAM)**.

## Part F — Authorization source comparison

### A. Direct read-only evaluation of current Kuali KRIM

- **Correctness:** best. Revocation is immediate by construction; `ACTV_IND`
  and `ACTV_FRM_DT`/`ACTV_TO_DT` are evaluated at request time.
- **Blockers:** the archive is a *retirement* platform for a system being
  decommissioned — a runtime dependency on Kuali Production contradicts its
  charter, and direct runtime access must not be assumed permitted. Oracle is
  reachable only from a BU-VPN machine or the ECS peering path, neither of
  which is an acceptable synchronous dependency for every page load. Latency
  and blast radius are both unacceptable: Kuali downtime would deny all
  archive access.
- **Verdict:** rejected on availability and charter, not on correctness.

### B. Controlled KRIM snapshot with revocation synchronisation

- **Correctness:** good *if* the refresh is frequent and revocation-aware. The
  schema makes this tractable: a refresh must re-evaluate `ACTV_IND` on
  `KRIM_ROLE_T`/`KRIM_ROLE_PERM_T`/`KRIM_PERM_T`, the date windows on
  `KRIM_ROLE_MBR_T`/`KRIM_GRP_MBR_T`, and recurse through `MBR_TYP_CD='R'`.
- **Risks:** a stale snapshot grants revoked access — the precise failure
  SEC-006 exists to prevent. Requires a *deletion-aware* sync (absence must
  revoke, which naive upserts do not do). Also inherits KRIM's gaps: it cannot
  express SEC-001 for the five non-Kuali Central teams, nor SEC-004's IO
  grants, because no such qualifier exists.
- **Verdict:** necessary for the parts KRIM genuinely owns, insufficient alone.

### C. Archive-managed grants using KRIM concepts and archived relationships

- Already designed and partially built, rev 3.6a, in `authz` schema:
  `identity_link`, `person_status`, `access_grant`, `access_audit`.
- `access_grant.grant_type ∈ (CENTRAL, UNIT, IO, CONTACT_DERIVATION)` maps
  one-to-one onto SEC-001/002/004/003.
- **It already borrows KRIM's semantics rather than inventing new ones:**
  `unit_number` + `include_descendants` is KRIM's `unitNumber` + `subunits`;
  `valid_from`/`expires_at`/`revoked_at` mirror `ACTV_FRM_DT`/`ACTV_TO_DT`;
  `approved_by` adds the second-approver control SEC-001 needs.
- **Risks:** grants must be maintained, so it can drift from Kuali reality;
  `CONTACT_DERIVATION` depends on archived business data being complete.
- **Verdict:** the only option that can express all nine requirements.

### Recommendation — C as the enforcement store, B as a feed, never A

Enforce from `authz` (option C). Populate and *re-validate* the
KRIM-derivable parts from a controlled, scheduled snapshot (option B), run on
the existing ECS Fargate ETL path that already reaches Oracle — not from the
API request path. Treat the snapshot as **evidence that a grant is still
warranted**, not as the grant itself:

- A UNIT grant whose KRIM backing disappears is **flagged and expired**, not
  silently kept.
- CENTRAL grants for the five non-Kuali teams are **explicit and
  human-approved** (`granted_by` + `approved_by`), because nothing can derive
  them.
- CONTACT_DERIVATION resolves at query time against archived contact tables.
- `person_status.suspended` overrides everything, giving a single immediate
  kill switch independent of any refresh cycle.

This keeps correctness where KRIM is authoritative, keeps availability
independent of Kuali, and never treats historical membership as current
authorization.

## Part G — Record-level relationship map

| Module | Parent record | Department key (SEC-002) | Contact key (SEC-003) |
|---|---|---|---|
| Awards / Historical Awards | `archive.award_version` | **`lead_unit_number`** (+`lead_unit_name`) | `archive.award_person`, `archive.award_person_unit`, `archive.award_unit_contact` |
| Proposals | **`archive.proposal_version`** (not `archive.proposal` — it does not exist) | **`lead_unit_number`** (+`lead_unit_name`) | `archive.proposal_person`, `archive.proposal_person_unit`, `archive.proposal_unit_contact` |
| Subawards | `archive.subaward` | `requisitioner_unit`, `organization_id` | `archive.subaward_contact` |
| Negotiations | `archive.negotiation` | **none** | — |
| IRB | `archive.irb_*` | `archive.protocol_unit` | `archive.protocol_person` |
| Attachments | `archive.award_attachment`, `proposal_attachment`, `subaward_attachment`(`_archive`), `archived_attachment`, `attachment_object` | inherit from parent record | inherit from parent record |

**Unit hierarchy:** `archive.unit(unit_number PK, parent_unit_number,
organization_id)` — so `include_descendants` is computable locally with a
recursive CTE. `archive.unit_administrator(unit_number, person_id,
unit_administrator_type_code)` already maps departments to administrators.

### Three gaps this exposes

1. **Negotiations have no unit column**, so SEC-002 cannot be enforced for
   Negotiation by lead unit. The CSV's own note is the clue: Industry Team
   maps to *"Negotiator" on negotiation* — a person field, not a unit. Either
   Negotiation scoping is contact-derived, or a unit must be derived through
   the associated Award/Proposal.
2. **Type mismatch on the join column.** `archive.unit.unit_number` is
   `VARCHAR(20)`, `award_version.lead_unit_number` is `VARCHAR(30)`,
   `proposal_version.lead_unit_number` is `VARCHAR(50)`, and
   `subaward.requisitioner_unit` is `VARCHAR(100)`. No FK ties them. Any unit
   scoping must normalise, and should be tested for unmatched values.
3. **Award family grain is unresolved.** `lead_unit_number` lives on
   `award_version`, so *different versions of one Award can have different
   lead units*. Per the project's own grain rules, a unit that led any version
   would then see the whole family — which is a business decision, not a
   technical one (see Part J).

### Global Search, counts, reports

These are the hardest to scope and currently the most exposed. `DashboardController`
returns archive-wide counts; `GlobalSearchController`, `DocumentSearchController`,
`AwardEvidenceSearchController` and `AttachmentSearchController` search across
modules. Each must apply the same predicate as record endpoints, **and counts
must be computed post-filter** — an unfiltered count discloses the existence
and volume of records the user cannot open.

## Part H — Existing application security gaps

Inspected, not changed.

### Every `/api/**` endpoint is open to any authenticated user

`api/src/main/java/edu/bu/archive/config/SecurityConfiguration.java` lines 36–48:

```java
.csrf(csrf -> csrf.disable())
.cors(Customizer.withDefaults())
.authorizeHttpRequests(authorize -> authorize
        .requestMatchers("/actuator/health", "/actuator/info",
                "/v3/api-docs/**", "/swagger-ui/**",
                "/swagger-ui.html", "/error").permitAll()
        .requestMatchers("/api/**").authenticated()
        .anyRequest().denyAll()
)
```

- **106 endpoints across 26 controllers**, gated only by *authenticated*.
- **Zero** `@PreAuthorize`, `@Secured` or `@RolesAllowed` anywhere in
  `api/src/main/java`.
- The **only** authorization differentiation in the product is
  `AttachmentAuthorizationService.requireAttachmentAccess`, which checks
  `ROLE_ArchiveAttachmentViewer` — and it is invoked per-endpoint, not
  controller-wide: `AwardV1Controller` has 33 endpoints and 7
  attachment-auth references.

This matches `securityRequirements.json`'s own `enforcementState`: *"Record-level
access is switched OFF on the development website. Every signed-in user can
still reach the whole archive."*

### Exposure by family — all `authenticated`-only unless noted

| Controller | Endpoints | Gate |
|---|---|---|
| `AwardV1Controller` | 33 | 7 attachment refs; record data ungated |
| `SubawardArchiveController` | 14 | attachment refs only |
| `ExplorerController` | 11 | attachment refs only (dev-only tool) |
| `ProposalV1Controller` | 9 | attachment refs only |
| `NegotiationArchiveController` | 9 | attachment refs only |
| `AwardArchiveController` | 9 | **none** |
| `ProposalArchiveController` | 5 | **none** |
| `IrbController` / `IrbArchiveController` / `IrbWorkspaceController` | 3 / 3 / 1 | **none** |
| `GlobalSearchController` | 1 | **none** |
| `DashboardController` (counts) | 1 | **none** |
| `DocumentSearchController` / `DocumentExplorerController` | 1 / 1 | **none** |
| `AwardEvidenceSearchController` | 1 | **none** |
| `AwardAiController` / `AwardAiQuestionController` | 1 / 1 | **none** |
| `InvestigatorController` | 1 | **none** |
| `AttachmentSearchController` | 1 | attachment-gated |

So **search, counts, exports, reports, attachments metadata and direct record
URLs are all reachable by any signed-in user today.** Direct record URLs are
the sharpest case: `/api/.../awards/{awardNumber}` style endpoints have no
ownership predicate, so knowing an identifier is sufficient.

### Other observations

- `csrf` disabled — defensible for a stateless bearer-token API, but it should
  be a recorded decision rather than incidental.
- `/v3/api-docs/**` and `/swagger-ui/**` are `permitAll`, publishing the full
  endpoint inventory unauthenticated. Low severity, but it hands an attacker
  the map.
- No audit logging of any kind exists (SEC-008 is unstarted).

### Recommended enforcement shape

1. **Fail closed at the repository boundary, not the controller.** A predicate
   applied in `*ArchiveRepository` (JdbcClient `WHERE` composition) cannot be
   bypassed by a new controller that forgets the check. Controller-level
   annotations alone would repeat the attachment gate's weakness — 26
   controllers, each a chance to omit it.
2. **One authorization decision object per request**, resolved once from
   `authz` (`IdentityResolver` already exists in draft) and passed down.
3. **Counts and search filter before aggregating.**
4. **Deny on ambiguity** — unresolvable identity, multiple active links, or
   suspended status denies, as the draft resolver already does.
5. **Audit append-only in `authz.access_audit`**, outside the archive schema so
   it can never be exported by a record endpoint.

## Part I — Proposed architecture and phased plan

### Architecture

```
BU Shibboleth ──SAML──► Cognito (us-east-1_VJ4ekQ27c, BUShibboleth IdP)
                           │  JWT: sub, iss, cognito:groups, email, eppn
                           ▼
                    Spring Boot API
                           │
          ┌────────────────┴─────────────────┐
          ▼                                  ▼
  authz.identity_link              authz.access_grant
  (cognito sub+iss → institutional  (CENTRAL | UNIT+descendants |
   identifier → kuali_person_id)     IO | CONTACT_DERIVATION,
          │                           time-bounded, approved_by)
          │                                  │
          └──────────► AuthorizationPolicy ◄─┘
                           │
                  repository-level predicate
                           ▼
         archive.* (award_version.lead_unit_number,
                    proposal_version.lead_unit_number,
                    award_person / proposal_person / contacts,
                    unit.parent_unit_number recursion)
                           │
                           ▼
                 authz.access_audit (append-only)

  Controlled refresh (ECS Fargate ETL, NOT request path):
      Oracle KRIM ──► re-validate UNIT grants, expire unbacked ones
```

### Phases

| Phase | Content | Exit criterion |
|---|---|---|
| 0 | Decide the five business questions in Part J; confirm eppn↔`PRNCPL_NM`; rebase the authz branches onto current `main` | Decisions recorded; branches build and test green |
| 1 | Add `principal_name`, `entity_id`, `active_indicator` to `archive.person` and its extraction; identity resolution only, enforcement off | A Shibboleth login resolves to one `PRNCPL_ID`, or denies with a reason |
| 2 | `authz` schema + admin grant/revoke + `access_audit` (SEC-005, SEC-006, SEC-008) | Grant and revoke demonstrably change effective access in a test |
| 3 | Repository-level predicate for Award + Historical Award, behind a flag, default off (SEC-002, SEC-003, SEC-007) | Fixtures in Part K pass with the flag on; all pass with it off |
| 4 | Extend to Proposal, Subaward, Attachments, then Negotiation (needs the Part G gap resolved) | Per-module fixtures pass |
| 5 | Search, counts, reports filtered pre-aggregation | Count equals the number of openable records |
| 6 | SEC-009 demonstrable read-only control; SEC-001 central grants with second approver | A test proves no write path; grants require `approved_by` |

Nothing in phases 1–6 is started here; this is the plan only.

## Part J — Open business questions for Anthony (not decided here)

1. **PAFO Administrator — Central or Department?** The role table (Part A)
   places PAFO Administrator in **Central Users**, but the SEC-002 Department
   User fixture is *"PAFO Administrator = Nancy Schindele"*. The two readings
   give opposite expected results, so SEC-002 is untestable until this is
   settled. A **department-only test identity** is needed regardless, because
   an unrestricted user would pass the department test for the wrong reason.
2. **"IO number" — exact definition and source column.** Not an archive column
   name. The SEC-004 fixtures pair an Award number with an Account number
   (`209407-00002:9500312705`), and SEC-003's pairs are labelled
   *"Award ID: Account:"*. Candidates include Award number, Account number, or
   a Kuali "Internal Order". Until confirmed, `authz.access_grant.io_value`
   has no defined semantics.
3. **Contact access — one version or the whole Award family?**
   `lead_unit_number` and contact rows attach to *versions*. Does being a
   contact on version 3 grant versions 1–7?
4. **Which archive activities require audit logging?** SEC-008 says "all
   archive activity". Reads as well as writes? Searches and counts? Export and
   report generation specifically? Retention period?
5. **Who approves membership in the five non-Kuali Central teams?** Subaward,
   Contracting, Industry, Foundation Relations and Research Development have
   no Kuali role to derive from, so membership is manual and standing. Who
   grants, who approves, and how often is it reviewed?

Additional questions this investigation surfaced, same status:

6. **Which BU attribute is the institutional identifier?** V082 records this as
   undecided, awaiting BU IAM. INC21002534 question 5 asks BU whether
   `eduPersonEntitlement` is releasable, which bears directly on it.
7. **Negotiation department scope** — no unit column exists (Part G).
8. **Extend or restart** the existing authz branches.

## Part K — Test cases

Derived from Anthony's own fixtures where supplied.

| ID | From | Test | Expected |
|---|---|---|---|
| T-001 | SEC-001 | `rochas@bu.edu` opens records across several units and all modules | All permitted |
| T-002 | SEC-002 | A **department-only** identity for `Lead Unit = 2573180018` | Permitted in-unit; denied out-of-unit |
| T-002b | SEC-002 | Same identity, a child unit of 2573180018 | Follows the grant's `include_descendants` |
| T-003 | SEC-003 | `PI=LINDSAY A FARRER` → Award `212128-00002:9500316722` | Permitted |
| T-004 | SEC-003 | Same identity → `211144-00007:9500317253` | **Denied** |
| T-005 | SEC-004 | `OAV=ANUKRITIKA RAYAMAJHI` → `209407-00002:9500312705` | Permitted |
| T-006 | SEC-004 | Same identity → `211144-00007:9500317253` | **Denied** |
| T-007 | SEC-005 | Admin adds a UNIT grant | Access appears without re-login, or re-login documented |
| T-008 | SEC-006 | Admin revokes it | Access removed; `access_audit` row written |
| T-009 | SEC-006 | KRIM backing for a UNIT grant disappears at refresh | Grant flagged/expired, not silently retained |
| T-010 | SEC-007 | Denied record requested directly by URL, bypassing the UI | 403, no record data in the body |
| T-011 | SEC-007 | Denied record's attachment requested directly | 403 |
| T-012 | SEC-007 | Global Search as a restricted identity | Only openable records returned |
| T-013 | SEC-007 | Dashboard counts as a restricted identity | Counts equal openable records |
| T-014 | SEC-008 | Perform a read, a search and an export | Audit rows with user, timestamp, action |
| T-015 | SEC-009 | Attempt a write through every API surface | No write path; proven by test |
| T-016 | identity | Suspend via `person_status` | Immediate denial regardless of grants |
| T-017 | identity | Two ACTIVE `identity_link` rows for one Cognito sub | Denied as ambiguous; index prevents the write |
| T-018 | identity | Shibboleth user absent from `archive.person` | Denied with a distinguishable reason, not a 500 |
| T-019 | identity | `KRIM_PRNCPL_T.ACTV_IND='N'` principal | Denied |
| T-020 | regression | All of the above with enforcement flag **off** | Current behaviour unchanged |

---
---

# Part L — Business decisions recorded by the project owner

Recorded 2026-10-22. These **supersede** the correspondingly numbered open
questions in Part J. Still design/investigation only — nothing implemented,
migrated, merged or deployed.

| # | Decision | Supersedes |
|---|---|---|
| D1 | **PAFO Administrator is DEPARTMENT-scoped**, not Central. Access only to assigned departments and authorized subordinate units. | J1 |
| D2 | **IO means the SAP account number.** Use existing Award-to-account relationships. Do **not** invent an IO field, and do **not** treat the Award number as the IO. | J2 |
| D3 | **Research Staff contact access is AWARD-FAMILY-scoped.** A contact on *any* version may access the whole family, subject to current identity and access status. | J3 |
| D4 | **No comprehensive audit logging of routine searches or record views** — a deliberate scope decision. Keep minimal security-administration audit records for grants, revocations and access changes, subject to confirmation against SEC-008. | J4 |
| D5 | **Dedicated `ARCHIVE_ADMIN` role** (revised; replaces the earlier single-designated-superuser decision). No automatic Central access for the five non-Kuali teams. | J5 |

## D1 — PAFO Administrator is department-scoped

**This resolves the blocking contradiction**, and it resolves it *against* the
source document's own role table. Part A of this matrix lists PAFO
Administrator under **Central Users**; the SEC-002 fixture names a PAFO
Administrator as the **Department User**. The owner's decision selects the
fixture's reading.

**The source CSV therefore remains internally inconsistent and should be
corrected at source**, so a future reader does not re-derive the Central
reading from Anthony's own table. Until the CSV is updated, this document is
the authority.

Consequences:

- `Lead Unit = 2573180018` / `PAFO Administrator = Nancy Schindele` becomes a
  *valid* SEC-002 fixture rather than a contradictory one. The separate
  department-only test identity noted in Part B is **no longer required for
  correctness** — though a PAFO identity with a *single* assigned unit is
  still needed, so the test can prove exclusion as well as inclusion.
- Central Users loses one of its two Kuali-backed roles. Of the seven listed
  Central teams, **only OSP Administrator remains Kuali-derivable**; the other
  six require explicit grants (five were already non-Kuali, PAFO now moves to
  Department). That strengthens, not weakens, the case for explicit
  administered CENTRAL grants.
- "authorized subordinate units" maps directly onto
  `access_grant.include_descendants` and `archive.unit.parent_unit_number`
  recursion. Note the wording is *authorized* subordinate units — so descent
  is per-grant, not automatic.

## D2 — IO is the SAP account number

**Verified source columns** (do not invent a field):

| Table | Column | Type |
|---|---|---|
| `archive.award_version` | `account_number` | `VARCHAR(50)`, nullable |
| `archive.award_extension` | `account_number` | `VARCHAR(20)` |
| `archive.subaward` | `account_number` | `VARCHAR(100)` |

This matches Anthony's fixtures exactly: `209407-00002:9500312705` and
`212128-00002:9500316722` are `award_number` : `account_number`, and the CSV
labels them *"Award ID: Account:"*.

Three technical consequences that must shape the implementation:

1. **`account_number` lives on the *version*, not the family.** `award_version`
   is keyed `award_id` with `UNIQUE (award_number, sequence_number)`. So one
   Award family can carry several account numbers across its versions, and an
   IO grant naming one account number resolves to a *set of versions*, not to a
   whole family. **This is a different grain from D3** — D3 grants the family,
   an IO grant grants whatever the account number touches. Both must be
   expressible, and they are not the same scope.
2. **`account_number` is nullable** on `award_version`. Versions with no
   account number can never be reached by an IO grant; they must be reachable
   by CENTRAL, UNIT or CONTACT paths or they are invisible to everyone but
   Central.
3. **Width is inconsistent** (`50` / `20` / `100`) and there is no FK or shared
   domain. Matching must normalise (trim, and decide on case and leading
   zeros), and unmatched grant values should be surfaced rather than silently
   matching nothing.

`authz.access_grant.io_value` now has defined semantics: **it holds an SAP
account number**, matched against `account_number`. Renaming it to
`account_number` in the schema would be clearer than keeping "IO", and the
column is still unused, so renaming costs nothing.

## D3 — Research Staff access is Award-family-scoped

Resolution rule: a person listed as an authorized contact on **any** row of
`archive.award_person` / `archive.award_unit_contact` for any
`award_version` sharing an `award_number` may access **every** version in that
`award_number` family — "subject to current identity and access status".

That last clause is load-bearing, because of the next finding.

### The archive cannot time-bound contact membership

Checked every relevant table. `archive.award_person`,
`archive.award_unit_contact`, `archive.proposal_person` and
`archive.subaward_contact` carry **only `source_update_timestamp`** — no
`active` flag, no `status`, and no start/end dates.

So the archive holds a **point-in-time snapshot of contacts with no validity
period**. It cannot distinguish "is currently a contact" from "was a contact
in 2019". Contact-derived access is therefore **inherently historical**, which
is exactly the pattern the KRIM investigation warned against (Part C).

D3 is still implementable, but only because the owner attached the
qualifier. The mitigation has to carry the whole weight:

- **Identity status gates it.** `authz.person_status.suspended` and
  `identity_link.status = 'REVOKED'` deny regardless of contact rows, and
  `KRIM_PRNCPL_T.ACTV_IND = 'N'` should too.
- **Contact derivation must be a grant, not an implicit rule.**
  `access_grant.grant_type = 'CONTACT_DERIVATION'` with `valid_from` /
  `expires_at` / `revoked_at` lets an administrator switch a person's
  contact-derived access off without altering archived records — which the
  archive's read-only charter requires.
- **This should be stated to Anthony**, because it changes what SEC-003 can
  promise: the archive can enforce "was a designated contact", and the
  *currency* of that relationship is supplied by the identity and grant
  layers, not by the archived data.

## D4 — No routine search / record-view audit logging

Recorded as a deliberate scope decision. Minimal security-administration
audit is retained: grants, revocations and access changes.

**This is in tension with SEC-008 as written** — *"an auditable record of all
archive activity, including the user, date and time, and action performed"* —
and the owner has flagged it as subject to confirmation against SEC-008. It is
preserved here as an open conflict rather than resolved:

- **What D4 keeps** satisfies the "security investigations" purpose clause for
  *administrative* actions: who granted or revoked what, when.
- **What D4 drops** is the literal "all archive activity": reads, searches,
  counts, exports and report generation.
- **The residual risk to state plainly:** after an incident, there will be no
  record of *what a user looked at* — only of what access they held and when
  it changed. If the question is ever "did this person view award X", D4 makes
  it unanswerable.
- **SEC-008 supplied no user story, test case or test object** (it is one of
  only two such requirements), so its intent is less constrained than its
  wording. That is the gap to close with Anthony.

A middle option worth putting to him, not adopted here: log **attachment
downloads and report/export generation** only — the activities that remove
data from the platform — while leaving searches and page views unlogged. That
preserves most investigative value at a small fraction of the volume.

## D5 (revised) — the `ARCHIVE_ADMIN` role

Replaces the earlier single-designated-superuser decision. Nine requirements,
each mapped to a concrete control.

| # | Requirement | Design |
|---|---|---|
| 1 | Any `ARCHIVE_ADMIN` holder may grant, modify and revoke archive access | Administration endpoints require the role; every write goes through one service that records audit |
| 2 | Multiple simultaneous holders | Role membership is a table, not a singleton column |
| 3 | Separate from CENTRAL / DEPARTMENT / CONTACT / IO grants | A **distinct store**, not a `grant_type` value — see below |
| 4 | Holding it does **not** grant archive data access | The record-level predicate ignores `ARCHIVE_ADMIN` entirely; an admin with no data grant sees no records |
| 5 | May assign and revoke CENTRAL, UNIT, IO and other supported grants | The existing `access_grant.grant_type` set |
| 6 | Every change records acting admin, affected user, action, timestamp | `access_audit`, append-only, written in the same transaction as the change |
| 7 | A revoked permission stops authorizing **immediately** | Evaluate per request; never cache a decision across requests. Note the JWT itself cannot be revoked mid-life, so authorization must not be carried in the token |
| 8 | Only an existing admin may assign/revoke the role; controlled bootstrap; cannot remove the last active admin | Bootstrap + last-admin guard, below |
| 9 | Enforced in the backend, not merely the UI | Repository/service boundary, per Part H |

### Why requirement 3 means a separate store

Putting `ARCHIVE_ADMIN` into `access_grant.grant_type` would make it one value
among CENTRAL/UNIT/IO/CONTACT_DERIVATION — and anything that reads "does this
user hold a grant" would then treat administration as data access, breaking
requirement 4. A separate table keeps the two questions apart:

```
authz.admin_role_member
    institutional_identifier  (PK part)
    granted_by                -- institutional identifier of the acting admin
    granted_at
    revoked_by, revoked_at
    status                    ACTIVE | REVOKED
    -- partial unique index on (institutional_identifier) WHERE status='ACTIVE'
```

Requirement 8's **last-admin guard** is a correctness constraint that cannot be
expressed as a simple table constraint, because it is a condition over the set
of rows. It must be enforced in the revoking transaction:

```
-- inside the same transaction as the revoke, with the rows locked
SELECT count(*) FROM authz.admin_role_member WHERE status = 'ACTIVE';
-- refuse if revoking this member would take the count to zero
```

Use `SELECT ... FOR UPDATE` (or `SERIALIZABLE`) so two concurrent revokes
cannot each see "two active admins" and both proceed, leaving zero. **This is a
real race, and a plain `CHECK` constraint cannot prevent it.**

**Bootstrap** — the first admin cannot be granted by an existing admin:

- Seed exactly one `ARCHIVE_ADMIN` by a **migration or an operator-run script
  that records who ran it**, not through the application's own API.
- Make the bootstrap path refuse to run when any `ACTIVE` admin already
  exists, so it cannot be replayed to inject an admin later.
- Write the bootstrap to `access_audit` with an explicit
  `actor = 'BOOTSTRAP'` and the operator's identity, so the chain of custody
  starts at a recorded event rather than an unexplained row.
- Suggested first holder: an existing, already-audited identity — the two
  `ArchiveAttachmentViewer` members are the only identities this project has
  previously granted anything to, and both are `CONFIRMED` and enabled.

### Interaction with requirement 7 and Cognito

A revoked grant must stop authorizing **immediately**, but a Cognito JWT lives
up to **1 hour** (`AccessTokenValidity: 1`, confirmed on the live pool). So:

- Authorization must be resolved **per request from `authz`**, never read from
  a `cognito:groups` claim or any other token content, or a revocation waits up
  to an hour.
- This is a deliberate divergence from the existing attachment gate, which
  *does* read `cognito:groups` → `ROLE_ArchiveAttachmentViewer`. That gate
  cannot satisfy requirement 7 as written, and whether to migrate it is a
  follow-up decision (see Part N).

## Part M — Revised traceability deltas

| Req | Change from the decisions |
|---|---|
| SEC-001 | Central audience shrinks: PAFO moves out (D1). **Only OSP Administrator remains Kuali-derivable** of the seven. Six need explicit `ARCHIVE_ADMIN`-granted CENTRAL grants (D5). Conflict "five of seven not Kuali roles" becomes **six of seven not derivable**. |
| SEC-002 | **Blocking contradiction resolved** (D1): PAFO is a Department User. Fixture `Lead Unit = 2573180018` / Nancy Schindele is now valid. Still needs a PAFO identity with a single assigned unit so exclusion is provable. Source CSV remains inconsistent and should be corrected. |
| SEC-003 | Scope fixed at **Award family** (D3). New, recorded limitation: the archive cannot time-bound contact membership — currency comes from identity status and a `CONTACT_DERIVATION` grant, not from archived data. |
| SEC-004 | **"IO" defined** (D2) as SAP `account_number` on `award_version` / `award_extension` / `subaward`. `io_value` gains semantics; rename to `account_number` recommended. New sub-issues: version-grain, nullability, width inconsistency. |
| SEC-005 | Now explicitly an `ARCHIVE_ADMIN` capability (D5 §1, §5), backend-enforced (§9). |
| SEC-006 | Revocation must be **immediate** (D5 §7) — rules out token-carried authorization. Adds the last-admin guard and bootstrap (D5 §8). |
| SEC-007 | Unchanged in intent; D5 §9 reinforces backend enforcement. `ARCHIVE_ADMIN` must **not** bypass it (D5 §4). |
| SEC-008 | **Deliberately narrowed** (D4) to security-administration events. Conflict with the requirement's literal "all archive activity" preserved and flagged for confirmation. D5 §6 fixes the minimum fields: acting admin, affected user, action, timestamp. |
| SEC-009 | Unchanged. Still needs a demonstrable control, not an assertion. |

## Part N — Remaining technical investigation

### 1. BU Shibboleth → KRIM principal mapping — still blocked on data

Unchanged from Part E and still the **hardest blocker**: `archive.person` has
no username column, the eppn-vs-`PRNCPL_NM` format question
(`rochas@bu.edu` vs `antcast`) cannot be settled from source, and
`archive.person` is only a *targeted* population (unit administrators ∪ award
unit contacts), so many legitimate users are absent from it entirely.

What the decisions change: nothing technical, but D5 gives a **workaround that
unblocks phase 1**. Because `ARCHIVE_ADMIN` administers grants against an
`institutional_identifier`, the system does not need a reliable automatic
eppn→`PRNCPL_ID` join to *start*. An administrator can link an identity
explicitly (`identity_link.method = 'ADMIN_VERIFIED'`, already in V082). The
automatic path (`AUTO_VERIFIED`) can follow once BU IAM confirms the attribute.

Still required before implementation: a decision on which BU attribute is the
institutional identifier (V082 records it as undecided), and
`archive.person` needs `principal_name`, `entity_id` and `active_indicator`
added to support lookup, duplicate detection and inactive-user denial.

### 2. Negotiation authorization where no unit exists — resolved, two paths

The earlier finding ("Negotiations have no unit column") was **incomplete**.
`archive.negotiation` itself has no unit, but:

- **Unassociated negotiations:**
  `archive.negotiation_unassociated_detail` carries `lead_unit VARCHAR(100)`
  (indexed: `ix_negotiation_unassociated_unit`), plus `pi_person_id`,
  `contact_admin_person_id`, `pi_rolodex_id`. So both a UNIT and a CONTACT path
  exist for these.
- **Associated negotiations:** `archive.negotiation` carries
  `negotiation_association_type_id` / `_code` / `_description` and
  `document_number`. The unit must be **derived from the associated Award or
  Proposal** (`award_version.lead_unit_number` /
  `proposal_version.lead_unit_number`).

So Negotiation is enforceable, with a **union** predicate: unit from
`negotiation_unassociated_detail.lead_unit` when unassociated, else the
associated record's lead unit; contact from `pi_person_id` /
`contact_admin_person_id`.

Two residual questions: (a) the exact association-type code values that mean
"associated to an Award" vs "to a Proposal" vs "unassociated" are not seeded in
the migration and need confirming against data; (b) a negotiation whose
association resolves to nothing has **no unit and no contact**, and so would be
visible only to CENTRAL — which should be a conscious choice, not an accident.

### 3. Current versus historical contact membership — answered, and it is a limitation

Covered under D3. The archive's contact tables carry no validity period at
all. Access derived from them is historical by construction; currency must come
from the identity and grant layers. This is a **product limitation to state to
Anthony**, not a bug to fix in the archive — adding validity dates would mean
re-extracting from Oracle with columns that may not exist in the source.

### 4. Enforcement across search, counts, exports, direct URLs, attachments

The design follows Part H's recommendation, now with the decisions applied:

| Surface | Enforcement |
|---|---|
| Direct record URLs | Repository-level predicate. **Sharpest current gap** — knowing an identifier is sufficient today |
| Search (`GlobalSearchController`, `DocumentSearchController`, `AwardEvidenceSearchController`) | Same predicate inside the query; never post-filter a page of results, or page sizes leak |
| Counts (`DashboardController`) | Compute **after** filtering. An unfiltered count discloses existence and volume |
| Exports / reports | Same predicate; per D4 these are the strongest candidates for the one audit event worth keeping |
| Attachments | Parent-record predicate **and** the existing `ArchiveAttachmentViewer` check — the group gates the *capability*, the predicate gates the *record*. Both, not either |
| AI endpoints (`AwardAiController`, `AwardAiQuestionController`) | Must apply the predicate **before** context building. `AwardContextBuilder` would otherwise summarise records the user cannot open |

The AI row matters more than it looks: `account_number` is now the IO
identifier (D2), and the AI context deliberately excludes
`accountNumber`/`sponsorAwardNumber`. That exclusion is consistent with D2 and
should stay — but it means the AI path cannot be used to check IO-based access,
and must rely on the same repository predicate as everything else.

## Part O — Revised acceptance tests

Supersedes the Part K rows that the decisions change; unchanged rows still apply.

| ID | From | Test | Expected |
|---|---|---|---|
| T-002r | D1 / SEC-002 | PAFO Administrator *Nancy Schindele* with assigned `Lead Unit = 2573180018` opens an in-unit Award | Permitted |
| T-002s | D1 / SEC-002 | Same identity opens an Award in an **unassigned** unit | **Denied** — proves PAFO is not Central |
| T-002t | D1 | Same identity, Award in an authorized **subordinate** unit | Permitted only when that grant has `include_descendants` |
| T-003r | D3 / SEC-003 | `PI=LINDSAY A FARRER`, contact on one version of `212128-00002`, opens **every** version of that family | All permitted |
| T-003s | D3 | Same identity opens `211144-00007` (not a contact) | **Denied** |
| T-003t | D3 | Same identity after `person_status.suspended = true` | **Denied**, despite contact rows |
| T-004r | D2 / SEC-004 | IO grant `account_number = 9500312705` → Award `209407-00002` | Permitted |
| T-004s | D2 | Same grant → `211144-00007:9500317253` | **Denied** |
| T-004t | D2 | IO grant for an account on only *some* versions of a family | Only those versions permitted — **not** the whole family (contrast T-003r) |
| T-004u | D2 | Award version with `account_number IS NULL` | Not reachable by any IO grant; reachable only via CENTRAL/UNIT/CONTACT |
| T-005r | D5 §1,§5 | `ARCHIVE_ADMIN` grants a UNIT grant to another user | Succeeds; `access_audit` records admin, subject, action, timestamp |
| T-005s | D5 §4 | `ARCHIVE_ADMIN` holder with **no** data grant opens any record | **Denied** — administration is not data access |
| T-005t | D5 §9 | Call the admin API directly with a non-admin token, bypassing the UI | **403** |
| T-006r | D5 §7 | Revoke a grant, then immediately re-request with the **same unexpired JWT** | **Denied immediately** — proves authorization is not token-carried |
| T-008r | D5 §2 | Two users hold `ARCHIVE_ADMIN` concurrently; both administer | Both succeed |
| T-008s | D5 §8 | Non-admin attempts to assign `ARCHIVE_ADMIN` | **403** |
| T-008t | D5 §8 | Revoke the **last** active `ARCHIVE_ADMIN` | **Refused**, admin count stays ≥ 1 |
| T-008u | D5 §8 | Two concurrent revokes of the final two admins | Exactly one succeeds; ≥ 1 admin remains (serialisation test) |
| T-008v | D5 §8 | Re-run the bootstrap while an active admin exists | **Refused** |
| T-014r | D4 | Perform a search, a record view, a grant and a revoke | Audit rows for the **grant and revoke only**; none for search or view |
| T-021 | Part N2 | Unassociated negotiation with `lead_unit` in the user's unit | Permitted |
| T-022 | Part N2 | Associated negotiation whose Award's lead unit is **not** the user's | **Denied** |
| T-023 | Part N4 | Dashboard counts as a restricted identity | Equal to openable records |
| T-024 | Part N4 | AI summary/question for a denied Award | **Denied before** context building |
| T-025 | Part N4 | Attachment on a permitted record, user **not** in `ArchiveAttachmentViewer` | **Denied** — both gates apply |

## Part P — What still prevents implementation

**Resolved by the decisions:** PAFO scope, IO definition, contact grain,
audit scope, and the administration model. Four of the five original blockers
are closed.

**Still blocking, in priority order:**

1. **Which BU attribute is the institutional identifier.** V082 deliberately
   encodes no column for this choice, and `authz` keys everything on it.
   Nothing can be populated until it is fixed. INC21002534 question 5 bears on
   it. *Decision needed — BU IAM.*
2. **eppn ↔ `PRNCPL_NM` format**, and the missing `principal_name` /
   `entity_id` / `active_indicator` columns on `archive.person`. Requires a
   read of real BU KRIM data; cannot be settled from source. *Investigation
   needed — Oracle access.*
3. **SEC-008 confirmation.** D4 narrows a requirement whose literal wording is
   broader, and SEC-008 shipped with no user story or test case. *Confirmation
   needed — Anthony.*
4. **Negotiation association-type code values**, to distinguish
   Award-associated / Proposal-associated / unassociated. *Investigation
   needed — data.*
5. **Extend or restart the authz branches.** They carry V082 and the identity
   resolver but sit far behind `main`. *Decision needed — engineering.*
6. **Bootstrap holder** for the first `ARCHIVE_ADMIN`, and whether the existing
   `cognito:groups`-based attachment gate migrates to per-request `authz`
   evaluation (it cannot satisfy D5 §7 as it stands). *Decision needed.*

Note on the CSV: **D1 contradicts the source document's own role table.** The
CSV should be corrected at source so the Central reading is not re-derived
later. Until then, this document is the authority.

---
---

# Part Q — Phase 1 implementation status

Branch `feat/authz-phase1` from `origin/main` (`240cb8c`). **Nothing applied,
migrated, merged or deployed. Enforcement flag OFF by default. No administrator
bootstrapped and no live permission granted.**

## What was reused, and what was deliberately not

The five existing branches carry a mature authorization core — ~79 classes, and
`feat/authz-enrollment` is the content superset. The core was ported
selectively and **compiles and passes against current `main`**, which was the
main risk going in: the branches sit 87 commits behind.

**Reused:** the V082 identity/grant schema, V083 KIM crosswalk, the identity
model and resolver, `AccessScopeResolver`, `RecordAccessEvaluator`,
`RecordAuthorizationGate`/`Service`, `RecordScopeSqlBuilder`/`SqlFragment`
(the repository predicates), `JdbcUnitHierarchy`, `JdbcContactRelationships`,
`AwardAccountNumberIoResolver` (D2's `account_number` semantics, already
implemented on the branch), `AuthorizationProperties`, plus their unit tests
and fixtures.

**Deliberately not reused, with reasons:**

| Omitted | Why |
|---|---|
| AWS Cognito reader (`AwsCognitoProfileReader`) and its SDK dependency | It exists to *auto-link* identities, which must stay disabled until BU confirms the institutional identifier. Phase 1 ships **no** `CognitoProfileReader` bean, so enabling enrollment fails fast at startup rather than auto-linking — automatic linking is structurally impossible, not merely switched off. |
| Demo / identity-lab scaffolding (`api/src/authz-demo`, `ui/src/demo`, `scripts/authz-demo`) | Demo-only surface, not production code. |
| End-to-end enforcement wiring | Measured: **76 files, ~3,597 insertions** across `AwardArchiveService` (+389), `ProposalArchiveService`, `GlobalSearchService`, `AttachmentSearchService`, `AwardEvidenceSearchService`, `DocumentSearchRepository`, `ProposalArchiveRepository` and others — all files `main` has changed substantially since. Porting that wholesale would risk the "preserve all current functionality" constraint. **Deferred to Phase 2.** |
| 6 end-to-end integration test classes | They assert the wiring above: `RecordAuthorizationEnforcementIntegrationTest`, `RecordAuthorizationApprovedPolicyIntegrationTest`, the three `NameIdEnrollment*` classes and `IdentityEnrollmentIntegrationTest`. They remain on `feat/authz-enforcement`/`-enrollment` and are **Phase 2's acceptance suite**, not discarded work. |

## New in Phase 1 — the ARCHIVE_ADMIN role

`ARCHIVE_ADMIN` did not exist on any branch (verified: zero case-sensitive
matches across all five). Administration existed only as an out-of-band Python
script, `scripts/authz-admin/authz_admin.py`. Phase 1 adds it as a
first-class, backend-enforced role.

- **`V084__create_authz_archive_admin_role.sql`** — `authz.admin_role_member`,
  a **separate table** from `authz.access_grant`, not another `grant_type`.
  That is what makes "holding ARCHIVE_ADMIN grants no data access" structural
  rather than a rule every future query must remember. Partial unique index
  `ux_admin_role_member_active` makes a second active membership impossible.
- **`ArchiveAdminService`** — the single choke point. Every mutating method
  requires an active administrator and writes an audit row in the same
  transaction. Deliberately in the service, not controller annotations: the
  attachment gate is enforced per endpoint across 26 controllers, which is 26
  chances to forget it.
- **`JdbcAdminRoleRepository`** — `SELECT ... FOR UPDATE` on the active rows
  inside the revoking transaction. `Propagation.MANDATORY` on the mutating
  methods and on `JdbcAdminAuditSink`, so an administrative change can never
  commit without its audit row.
- **Bootstrap** refuses when any active administrator exists, so it cannot be
  replayed to inject one; records `BOOTSTRAP` plus the operator identity.

### The last-administrator guard is proven, not asserted

The rule is a condition over the *set* of rows, so no `CHECK` constraint can
enforce it. `ArchiveAdminConcurrencyIntegrationTest` releases two threads that
each revoke one of the final two administrators.

**Verified by temporarily removing the lock:** with `FOR UPDATE` deleted the
test fails with *"outcomes were SYN-ADMIN-A succeeded and SYN-ADMIN-B
succeeded"* — both revokes commit, leaving zero administrators and no way back
in through the application. With the lock, exactly one succeeds and an active
administrator always remains. The lock was restored and the suite re-run green.

## Requirements coverage after Phase 1

| Req | State | Note |
|---|---|---|
| SEC-001 CENTRAL | **Foundation** | `GrantType.CENTRAL` + evaluator; grants administered, never derived (D1 moved PAFO out, leaving only OSP Administrator Kuali-derivable) |
| SEC-002 UNIT | **Foundation** | `UnitGrant`, `JdbcUnitHierarchy` descent over `archive.unit.parent_unit_number`; D1 resolved the PAFO contradiction |
| SEC-003 CONTACT | **Foundation** | `JdbcContactRelationships`, family scope per D3; the no-validity-dates limitation stands |
| SEC-004 IO | **Foundation** | `AwardAccountNumberIoResolver` over `account_number` per D2 |
| SEC-005 Grant | **Implemented (admin side)** | `ArchiveAdminService` + audit; no UI |
| SEC-006 Revoke | **Implemented (admin side)** | Immediate — resolved per request, never from a token claim; last-admin guard proven |
| SEC-007 Enforce | **Foundation only** | Predicates exist (`RecordScopeSqlBuilder`); wiring into 76 service/repository files is Phase 2 |
| SEC-008 Audit | **Partial, by decision** | `AdminAuditAction` covers administrative events only, per D4; still awaiting confirmation against SEC-008's literal wording |
| SEC-009 Read-only | **Unchanged** | No write path added; still needs a demonstrable control |

## Unresolved questions — unchanged, not silently resolved

1. **BU institutional identifier** — still undecided. Phase 1 ships no
   automatic linking precisely because of this; `AwaitingIamConfirmationAttributeSource`
   is the placeholder and no column encodes the choice.
2. **KRIM principal mapping** — eppn vs `PRNCPL_NM` unverified;
   `archive.person` still has no `principal_name`, `entity_id` or
   `active_indicator`. Administrator-verified linking is the Phase 1 workaround.
3. **Contact history vs current authorization** — archived contact tables carry
   only `source_update_timestamp`; currency comes from identity status and a
   revocable `CONTACT_DERIVATION` grant.
4. **Negotiation association-type mappings** — code values still unconfirmed
   against data; the two-path predicate (unassociated `lead_unit` vs derived
   from the associated Award/Proposal) is designed, not wired.
5. **SEC-008 audit scope** — D4's narrowing still needs Anthony's confirmation.
