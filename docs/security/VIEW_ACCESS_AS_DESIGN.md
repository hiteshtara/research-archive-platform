# View Access As — administrator authorization preview

**Design only. Nothing here is implemented.** No endpoint, no UI route, no
grant, no identity link, no deployment. The prototype in
`ui/src/features/viewAccessAsPreview/` renders against fixtures and calls
nothing.

Stop point, as instructed: **the production API is not built until this design
and its threat model are approved.**

---

## 1. What this is, and what it is not

A verified Archive Administrator selects an already-enrolled BU user and asks:
*what would this person see?* The answer comes from the real authorization
evaluator, so it is an answer about the system rather than about a document.

It is **not** impersonation. No Cognito token is minted, substituted, borrowed
or replayed. The administrator stays themselves for the whole interaction; the
selected user's identity is an *input* to a decision function, never a session.

That distinction is the whole security design, and it is worth stating in those
terms because "user impersonation testing" was the phrase the business used.
What they want — *is this person's access right?* — is served better by a
preview than by impersonation, and a preview cannot do the things impersonation
would make possible.

### The constraint that shapes everything

**`ARCHIVE_ADMIN` confers no archive record access.** It is the right to
administer grants, not to read records. The existing administrator has no
`CENTRAL` grant and should not need one.

So an administrator previewing a user's access is asking about records the
administrator may not themselves be authorized to read — and that is the
feature's central hazard, not an edge case. It is why §4 is the longest
section here.

---

## 2. Architecture

### Reuse, not reimplementation

```
AdminPreviewController  (new, admin-gated)
        │
        ▼
AccessPreviewService    (new, thin)
        │
        ├──► IdentityResolver          (existing, unchanged)
        ├──► AccessScopeResolver       (existing, unchanged)
        ├──► RecordAccessEvaluator     (existing, unchanged)
        ├──► RecordFactsRepository     (existing, unchanged)
        └──► ArchiveActivityRecorder   (existing — SEC-008 audit)
```

`RecordAccessEvaluator` is a pure function — `decide(outcome, record,
familyVersions)` with no I/O, no identity and no HTTP, taking its policy as a
constructor argument. `AccessScopeResolver` is pure apart from three injected
ports. **Reusing the real engine needs no refactoring at all**, and any
proposal to extract an interface, add a `simulate()` method or introduce a
third decision value should be rejected: a second code path is a second set of
bugs, and the one thing this feature must be is *faithful*.

### What must not be the entry point

`RecordAuthorizationGate` must **not** be used. With enforcement off it returns
`allow(Set.of())` unconditionally, so a preview built on it would report
PERMIT for everything and look like it was working. The preview calls the
evaluator directly with an explicitly constructed policy, and renders the
policy it used alongside every answer.

### Simulated versus enforced

Enforcement is OFF in DEV and will be OFF for most of this feature's life. Every
response therefore carries:

| Field | Meaning |
|---|---|
| `enforcementEnabled` | what `app.authorization.enforcement-enabled` actually is |
| `policySource` | `CONFIGURED` when the deployment has a complete policy, `SIMULATED` when the preview supplied one because the deployment has none |
| `policy` | the four values actually used |

The UI states this above the results, not in a footnote: **"Enforcement is OFF.
These are simulated decisions — live behaviour today is that every signed-in
user sees every record."** A preview that let someone believe they were
observing live behaviour would be worse than no preview.

---

## 3. API

Four endpoints, all `POST` (they are audited queries, not cacheable reads), all
behind the existing administrator check, all refusing when the caller is not a
verified Archive Administrator.

### 3.1 Find an enrolled identity

```
POST /api/admin/access-preview/identities/search
{ "loginName": "exact-login-name" }
```

Returns **only** identities that already have an `authz.identity_link` row —
never a BU directory search, never Cognito enumeration. Exact match on login
name; no prefix, no wildcard, no listing.

```json
{ "found": true,
  "institutionalIdentifier": "ARP-…", "loginName": "…",
  "linkStatus": "ACTIVE", "linkedOn": "2026-…",
  "hasKualiPrincipal": true }
```

`found: false` for anything unknown. The archive cannot say whether a person
exists at BU — only whether it has enrolled them.

### 3.2 Show the user's grants

```
POST /api/admin/access-preview/grants
{ "institutionalIdentifier": "ARP-…" }
```

Grants, scopes and lifecycle — including **revoked and expired** rows, with
why each is or is not in force. This is administrative data the administrator
is already entitled to; it names no archive record.

### 3.3 Evaluate explicitly supplied records

```
POST /api/admin/access-preview/decisions
{ "institutionalIdentifier": "ARP-…",
  "records": [ { "module": "AWARD", "identifier": "105698-00002" } ] }
```

At most **10 identifiers per request** (§4.3). The administrator types or
pastes them; there is no picker, because a picker is enumeration.

```json
{ "decisions": [
    { "module": "AWARD", "identifier": "105698-00002",
      "decision": "DENY",
      "reasonCode": "NO_MATCHING_GRANT",
      "matchedRules": [],
      "versionsEvaluated": 0 } ] }
```

**No title. No status. No unit. No PI. No dates. No counts.** A decision and a
reason code, nothing that describes the record.

### 3.4 Compare with the administrator's own access

```
POST /api/admin/access-preview/compare
```

Same shape, two columns. The administrator's own column is computed the same
way — through *their* grants, not through their admin role — so the usual
result is that an administrator with no `CENTRAL` grant sees `DENY` in their
own column too. That is correct and worth seeing: it is the clearest possible
demonstration that administering access is not having it.

### What has no endpoint, on purpose

- No dashboard-count preview. A count *is* an enumeration; "this user would see
  40,926 Awards" discloses the archive's shape to someone who may not be
  entitled to it. The UI explains the user's scope in words instead
  ("every Award in unit 7777 and its sub-units") and lets the administrator
  test specific identifiers.
- No global-search preview, for the same reason.
- No "list the records this user can see".

This is the one place where the requested functionality is deliberately not
delivered as asked. Preview of Dashboard and Global Search *visibility* cannot
be built without returning either counts or result sets, and both are
enumeration of records the administrator may not independently access. The
substitute is a plain-English scope description plus explicit-identifier
testing. **If the business needs true count preview, it needs a different
decision: that administrators may see archive-wide counts.** That is a grant
question, not an API question.

---

## 4. Threat model

### 4.1 The oracle problem — the central threat

The feature is, by construction, an authorization oracle: supply an identifier,
learn something. Two leaks follow.

**Existence.** If a non-existent identifier answers differently from a real one
the caller is denied, the endpoint confirms which records exist.

> **Mitigation.** `NOT_FOUND` is never returned. A record that does not exist
> and a record the subject cannot reach both return
> `decision: "DENY"` with `reasonCode: "NO_MATCHING_GRANT"`, identical in every
> field including `versionsEvaluated: 0`. The administrator cannot tell them
> apart — which is the same guarantee `RecordNotAccessibleException`'s 404
> already gives ordinary callers.
>
> **Abuse-case test:** `aNonExistentIdentifierIsIndistinguishableFromADeniedOne`
> — byte-compare the two responses.

**Scope inference.** Repeated queries map the boundary of a user's access, and
with it the archive's structure.

> **Mitigation.** Rate limits (§4.3) and audit (§4.4). Not prevention —
> deterrence and detectability. An administrator can already read every grant;
> what they must not get is a cheap way to enumerate records.

### 4.2 Privilege inversion

An administrator without `CENTRAL` can ask about records they cannot read.
A `DENY` tells them little; a `PERMIT` tells them the record exists.

Three options were considered:

| Option | Verdict |
|---|---|
| Require the administrator to hold `CENTRAL` | **Rejected** — the instruction is explicit, and it would force a data grant on every administrator just to use a tool |
| Return decisions only, never record data | **Adopted** |
| Restrict previews to records the administrator can independently access | **Rejected** — it makes the tool useless for exactly the case it exists for: checking someone else's access to a record you do not have |

The residual risk is stated plainly rather than engineered away: **an
administrator can learn that a record with a given identifier exists, if some
user can reach it.** They cannot learn its title, unit, PI, status, dates or
contents. Acceptance of that residual risk is part of approving this design.

### 4.3 Rate limiting

| Control | Value | Why |
|---|---|---|
| Identifiers per request | 10 | a paste of a handful, not a file |
| Requests per administrator | 30/minute, 300/hour | ~3,000 identifiers an hour is a person working, not a script |
| Decisions per administrator per day | 2,000, then refuse | a ceiling that a real session never reaches |
| Burst | token bucket, refill 1/2s | smooths without blocking normal use |

Exceeding a limit returns `429` and **writes an audit row**. A limit that is
silently hit is a limit nobody learns about.

### 4.4 Audit

Every preview is audited through the existing SEC-008 recorder — which means
this feature **depends on SEC-008 being approved and V089 applied**. It is not
a reason to bypass audit; it is a sequencing constraint.

| Recorded | Not recorded |
|---|---|
| administrator identifier | record titles or contents |
| subject institutional identifier | the subject's name |
| module and identifier queried | anything about a record beyond its identifier |
| decision and reason code | |
| policy used, and whether simulated | |
| correlation id, timestamp | |

Two properties the audit must have: it is **append-only** (V089's trigger), and
a failed audit write **refuses the preview** — `FAIL_CLOSED`, like attachment
downloads. A preview that cannot be recorded does not happen.

### 4.5 Impersonation drift

The risk that this grows into impersonation later: a "view as" session, a
token swap, a `X-Act-As` header.

> **Mitigation, structural.** The service never constructs a
> `ValidatedCognitoIdentity` for the subject and never touches
> `SecurityContextHolder`. An abuse-case test asserts both by scanning the
> compiled class, in the same style as the existing read-only surface tests.

### 4.6 Administrative side effects

> **Mitigation.** The service depends on read-only ports only. It is wired
> without `ArchiveAdminService`, so there is no object in scope through which a
> grant could be created, modified or revoked. A test asserts the dependency
> set.

### 4.7 Stale-decision confusion

An administrator previews, a grant changes, the screen still shows the old
answer.

> **Mitigation.** Every response carries `evaluatedAt`, and the UI invalidates
> results when the subject or policy changes. Decisions are never cached
> server-side.

---

## 5. Security review checklist

To be completed by a reviewer who did not write the code.

**Authorization**
- [ ] Every endpoint refuses a caller who is not a verified Archive Administrator
- [ ] Refusal is tested for: no link, revoked link, suspended, non-administrator
- [ ] `ARCHIVE_ADMIN` is required; `CENTRAL` is *not* required
- [ ] Administrator status is re-checked per request, never cached in a session

**Non-impersonation**
- [ ] No code path constructs a `ValidatedCognitoIdentity` for the subject
- [ ] No code path writes `SecurityContextHolder`
- [ ] No token is minted, exchanged or forwarded
- [ ] Asserted by a compiled-class scan, not only by review

**Non-disclosure**
- [ ] No response carries a record title, status, unit, person, date or amount
- [ ] A non-existent identifier is byte-identical to a denied one
- [ ] No endpoint returns a count, a list or a page of records
- [ ] Denial reason codes are the engine's own codes, never prose

**Non-mutation**
- [ ] No grant, identity link, admin role or person status can be written
- [ ] `ArchiveAdminService` is not a dependency
- [ ] Asserted by the dependency-set test

**Audit**
- [ ] Every preview writes exactly one audit row
- [ ] A failed audit write refuses the preview (FAIL_CLOSED)
- [ ] No search term, title or content reaches an audit row
- [ ] Rate-limit refusals are audited too

**Rate limiting**
- [ ] Per-request, per-minute, per-hour and per-day limits enforced
- [ ] Limits are per administrator, not per IP
- [ ] Exceeding returns 429 and audits

**Honesty**
- [ ] Every response states whether enforcement is on
- [ ] Simulated decisions are labelled in the response *and* in the UI
- [ ] The policy used is shown with the result

**Build hygiene**
- [ ] The temporary QA harness (PR #113) is not reused as the production path
- [ ] No test-scope class reaches `BOOT-INF/classes` — asserted on the jar with a positive control

---

## 6. Phases and effort

Engineering estimates for one developer, excluding review and approval time.

| Phase | Scope | Effort |
|---|---|---|
| **0. Approval** | this design and threat model signed off; residual risk in §4.2 accepted | — |
| **1. Read-only service** | `AccessPreviewService`, identity search, grant display; no decisions yet | 2–3 days |
| **2. Decisions** | explicit-identifier evaluation, existence masking, comparison column | 3–4 days |
| **3. Audit + limits** | SEC-008 wiring, FAIL_CLOSED, token bucket, 429 auditing | 2–3 days |
| **4. UI** | production React page behind the admin route | 3–4 days |
| **5. Abuse-case suite** | the §4 tests plus the checklist's asserted items | 2–3 days |
| **6. Review** | independent security review against §5 | — |

**12–17 days** of engineering, plus review. Phase 3 depends on SEC-008
approval and V089; phases 1–2 can proceed in parallel with that decision but
**must not ship** without it, since an unaudited preview is not acceptable.

---

## 7. Prerequisites

| # | Prerequisite | Status |
|---|---|---|
| 1 | This design and threat model approved | **outstanding** |
| 2 | Residual risk in §4.2 explicitly accepted | **outstanding** |
| 3 | SEC-008 approved and V089 applied | outstanding — blocks phase 3 |
| 4 | The four policy values decided | outstanding — a preview with no policy can only simulate |
| 5 | At least one enrolled identity with grants | outstanding — the pilot provides it |

Nothing in phases 1–5 touches enforcement, grants, identity links or
Production.
