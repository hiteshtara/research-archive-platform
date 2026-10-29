# Authorization acceptance matrix — DEV

Built from the deployed code (API revision 84, image `@sha256:c9f3bc99…`)
and from **real DEV data**, read-only, 2026-10-29. No grants exist and
enforcement is OFF, so every "expected" below is a prediction to be
verified, not an observation.

**Structural reachability is not permission.** A subaward family is
reachable through *any* funding Award; it is authorized only if one of
those Awards is itself in the user's scope. Every case below names the
predicate that decides it.

## Real records selected

| Purpose | Record | Why this one |
|---|---|---|
| UNIT / descendants | parent unit **1250000000** | 2 awards at the parent (`212137-00001/2`), 8 at child **1250400000** (`114471`, `200613`, `204518`, `212137`). Award `212137` sits at **both**, so it separates "unit only" from "unit + descendants" cleanly |
| Second-level child | **1252060000** | holds `200613` — proves depth, not just one level |
| Subaward ANY-match | subaward code **1753** | **40** distinct funding Awards on one family — the widest ANY-match case in DEV |
| Negotiation, lead unit | negotiation **1**, lead unit `1511960000`, type `Award` | exercises the direct path and the Award path together |
| Negotiation, no lead unit | the 54 described below | the inheritance-gap cases |
| IO | award_id **2** (`100004-00002`, account `9500234095`) | 113,144 versions carry an account number, so IO is broadly applicable |
| Research Staff | roles `PI` (267,462), `COI` (25,535), `KP` (17,105), `MPI` (15,366) | the only contact roles present |

## Cases

Identities are synthetic ARPs in disposable fixtures. **No Cognito user is
created and no group is modified.**

| # | Identity / grant | Record | Expected | Deciding predicate | API |
|---|---|---|---|---|---|
| 1 | `ARP-TEST-CENTRAL`, CENTRAL | any | allow | `central = true` short-circuits scope | 200 |
| 2 | `ARP-TEST-UNIT`, UNIT `1250000000`, **no** descendants | `212137-00001` | allow | `{row}.lead_unit_number IN (:az_units)` | 200 |
| 3 | same | `114471-00001` (child unit) | **deny** | unit not in `:az_units` | **404** |
| 4 | `ARP-TEST-DESC`, UNIT `1250000000` **+ descendants** | `114471-00001` | allow | expanded units include `1250400000` | 200 |
| 5 | same | `200613-00001` (2nd level, `1252060000`) | allow | descendant expansion is transitive | 200 |
| 6 | `ARP-TEST-UNIT` | Proposal in another unit | deny | `proposalRow` same shape over `proposal_version` | 404 |
| 7 | `ARP-TEST-FUND`, UNIT of **one** funding Award of subaward `1753` | subaward `1753` | **allow** | `EXISTS` over `subaward_funding` → any qualifying Award | 200 |
| 8 | `ARP-TEST-NOFUND`, UNIT matching **none** of the 40 | subaward `1753` | deny | `EXISTS` false | 404 |
| 9 | `ARP-TEST-NEGUNIT`, UNIT `1511960000` | negotiation `1` | allow | `negotiation_search_attribute.lead_unit_number IN (:az_units)` | 200 |
| 10 | UNIT of the Award behind negotiation `1` | negotiation `1` | allow | Award path, gated on `association_type_description = 'Award'` | 200 |
| 11 | UNIT of an **unrelated** Award | a `Subaward`-type negotiation | deny | no Subaward inheritance path exists | 404 |
| 12 | `ARP-TEST-STAFF`, CONTACT_DERIVATION + Kuali PERSON_ID with a `PI` row | that Award | allow | `EXISTS` over `award_person` with role in `:az_roles` | 200 |
| 13 | CONTACT_DERIVATION but **no** PERSON_ID | any | deny | `mapped.kualiPersonId()` empty → contact path never added | 404 |
| 14 | `ARP-TEST-IO`, IO `9500234095` | award_id `2` | allow | `AwardAccountNumberIoResolver`, exact trimmed match | 200 |
| 15 | same | any Proposal / Subaward / Negotiation | deny | resolver returns `Set.of()` for non-AWARD modules | 404 |
| 16 | `ARP-TEST-NONE`, no grant | any | deny | `AccessOutcome.NotProvisioned / NO_ACTIVE_GRANTS` | 404 |
| 17 | grant revoked | previously visible record | deny | `grant.isActiveAt(now)` filters it | 404 |
| 18 | identity suspended | any | deny | `AccessOutcome.Denied / SUSPENDED_IDENTITY` | 404 |
| 19 | no identity link | any | deny | `NotProvisioned / NO_IDENTITY_LINK` | 404 |
| 20 | **`ARP-FK7QY195-QW5HV7G0`** (the real ADMIN), no data grants | any | deny | ADMIN confers no scope; 0 `access_grant` rows | 404 |

### Denied versus missing

Cases 3, 8, 11, 15–20 must be **indistinguishable** from a record that does
not exist. Test them as a pair — same status, same body, no timing or
header difference — not separately. The service throws
`NoSuchElementException`, which `GlobalExceptionHandler` answers 404.

### Global Search and Dashboard

| Check | Expected |
|---|---|
| Global Search as case 2 | only in-scope modules contribute; totals drop from the unrestricted 105 |
| Global Search as case 16 | 0 results, **200 not 404** — an empty result set is not a denial |
| Dashboard as case 2 | all four module counts scoped; **page and count must agree** (same WHERE) |
| Dashboard IRB fields | absent, not zero — D9 removes them for every caller once enforcement is on |
| Attachment metadata | inherits the Award/Proposal decision; see the gap below |

## Findings from the real data

**54 negotiations are reachable only by CENTRAL.** Of 10,775: 38 of type
`None` and all **16** of type `Subaward` have no `lead_unit_number` **and**
no inheritance path — the rule gates only on `'Award'` and
`'Institutional Proposal'`. No UNIT, IO or Research-Staff grant can ever
reach them. Whether that is correct is a **business decision**, not a bug;
it is not written down anywhere.

**The Proposal inheritance path is nearly untestable in DEV.** Only **3**
negotiations are of type `Institutional Proposal`. Real-data coverage of
that branch is effectively anecdotal; it needs a fixture test.

**Association types are dominated by `None`** (8,533 of 10,775). Most
negotiations depend entirely on the lead-unit path.
