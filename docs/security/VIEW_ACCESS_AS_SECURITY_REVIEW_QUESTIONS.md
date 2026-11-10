# View Access As — questions for BU security

Companion to [`VIEW_ACCESS_AS_DESIGN.md`](VIEW_ACCESS_AS_DESIGN.md). Nothing is
built; the production API is not started until these are answered.

Each question states what we propose, why, and **what we would do differently
if the answer is no** — so a "no" is actionable rather than a dead end.

---

## A. The decision that gates everything

### A1. Is the existence-disclosure residual risk acceptable?

**The risk, stated without softening.** An Archive Administrator can supply a
record identifier and learn whether *some* user can reach it. Where the answer
is PERMIT, they learn that a record with that identifier exists — even though
`ARCHIVE_ADMIN` gives them no right to read it.

They cannot learn its title, lead unit, principal investigator, status, dates,
amounts or contents. The response is a verdict, a reason code and a count of
versions considered.

**Why it cannot be designed away.** The feature answers "can this person see
this record?". Any truthful answer about a record that exists differs from the
answer about one that does not, whenever the subject can reach it. Masking
PERMIT as DENY would make the tool lie, which defeats its purpose.

> **If the answer is no**, the feature cannot be built as designed. The
> fallbacks, in order of our preference:
> 1. Restrict previews to records the administrator can independently access —
>    honest, and useless for the main case (checking someone else's access to a
>    record you do not hold).
> 2. Require administrators to hold `CENTRAL` — removes the inversion entirely,
>    but forces a data grant on every administrator in order to use a tool.
> 3. Drop the feature and answer access questions by reading grants, which is
>    what happens today.

### A2. Who may hold `ARCHIVE_ADMIN`, and is that population small enough?

The risk in A1 scales with the number of administrators. We do not set that
policy.

> **If the population is or may become large**, we would add a second
> authorization: a distinct `ACCESS_PREVIEW` role, granted separately from
> grant administration, so that previewing is not automatic with the ability to
> administer.

---

## B. The oracle

### B1. Are the proposed rate limits the right shape?

Proposed: 10 identifiers per request, 30 requests/minute, 300/hour, 2,000
decisions/day, then refuse. Exceeding returns 429 **and writes an audit row**.

A patient attacker inside those limits still enumerates ~2,000 identifiers a
day. We judge that acceptable because every one is attributed and audited, and
because an administrator can already read every grant.

> **If not acceptable**, the levers are a lower daily ceiling, a requirement
> that previews name a *subject* the administrator has a stated reason to check,
> or alerting on volume rather than a hard cap.

### B2. Should bulk or scripted use be blocked outright, or detected?

We propose detection — audit plus the limits above — not prevention. Blocking
scripted use reliably means proof-of-work or interactive challenges, which we
think is disproportionate.

> **If prevention is required**, say so now: it changes the API shape, not just
> a constant.

### B3. Is identifier-shaped probing in scope for monitoring?

A sequential sweep (`105698-00001`, `-00002`, `-00003`…) is the signature worth
alerting on. We can emit the metric; we do not own the alerting destination.

> **Question:** where should such an alert go, and who acts on it?

---

## C. Audit

### C1. Is SEC-008 the right audit channel, and does this change its approval?

Preview activity would be recorded through the SEC-008 activity audit, which is
**not yet approved** and whose migration (V089) is deliberately unapplied. That
makes this feature depend on SEC-008 rather than give a reason to bypass it.

> **Question:** does adding preview events change the retention decision (90
> days proposed) or the privacy defaults already outstanding for SEC-008?

### C2. Is FAIL_CLOSED right here?

We propose that a failed audit write **refuses the preview**, as for attachment
downloads: an unrecorded preview is the event the audit exists for.

> **If FAIL_OPEN is preferred** — on the argument that a preview discloses less
> than a download — say so; it is a one-line policy change, but it should be a
> decision rather than a default.

### C3. Is attributing to the administrator sufficient, or must the subject consent?

The audit records which administrator previewed which subject. The subject is
not notified.

> **Question:** does BU policy require notifying a person when their access is
> inspected? If yes, we need a channel for it, which does not exist today.

---

## D. Non-impersonation

### D1. Are the structural guarantees the right ones?

We propose asserting, by scanning compiled classes rather than by review, that
no code path constructs a `ValidatedCognitoIdentity` for the subject and no
code path writes `SecurityContextHolder`.

> **Question:** is there a BU standard for demonstrating non-impersonation that
> we should meet instead of or in addition to this?

### D2. Does "preview" need a different name in user-facing text?

The business asked for "impersonation testing". We have deliberately not used
that word anywhere in the UI, because the feature is not that and the name
would set the wrong expectation.

> **Question:** is there a BU-preferred term for this class of tool?

---

## E. Scope boundaries

### E1. Do you agree that count and search previews must not be built?

Dashboard and Global Search visibility preview were requested. Both require
returning counts or result sets, and a count *is* an enumeration of an archive
the administrator may hold no grant over. We propose a plain-English scope
description plus explicit-identifier testing instead.

> **If count preview is required**, it needs a separate decision — *that
> administrators may see archive-wide counts* — which is a grant question, not
> an API question, and should be recorded as such.

### E2. Is the comparison column acceptable?

The screen shows the subject's decision beside the administrator's own. For an
administrator with no data grant, their column is mostly DENY — which is
correct and is the clearest demonstration that administering access is not
having it.

It also tells the administrator, precisely, which records they cannot reach.
We judge that harmless: they already know their own grants.

> **Question:** agreed?

---

## F. Operational

### F1. Who may approve this design, and is a separate penetration test required?

### F2. Must the feature be disabled by default behind a flag, as authorization enforcement is?

We propose yes — shipped off, enabled deliberately — consistent with how every
other authorization capability in this platform has been released.

### F3. Is a time limit or review date wanted?

The temporary QA harness (PR #113) carries a removal checklist. This feature is
proposed as permanent, so it has none.

> **Question:** should it instead be approved for a fixed period and re-reviewed?

---

## What we need back

1. **A1** — accept or reject the residual risk. Everything else is moot until this is answered.
2. **A2, C1, C2, C3** — before any code is written.
3. **B1, B2, B3, E1, E2** — before the API shape is fixed.
4. **D1, D2, F1, F2, F3** — before release.

Engineering recommendation: answer A1 first and alone. If it is a no, the rest
does not need reading.
