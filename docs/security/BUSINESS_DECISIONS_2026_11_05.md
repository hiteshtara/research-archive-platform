# Business decisions — Anthony Castellano, 2026-11-05

Answers to the Business Decisions section of the QA Status page, received by
email on **2026-11-05** from **Anthony Castellano, MBA — Product Owner, Huron
Grants and Agreements, BU Sponsored Programs | Operations, Analytics, and
Systems**, replying to the 2026-10-23 testing request.

Recorded here because these are **business rules**, not engineering choices.
`AUTHORIZATION_POLICY_DECISIONS_OUTSTANDING.md` says so in as many words about
the first one: *"No decision record exists and engineering cannot supply it."*
Email is not a decision record, so this is one.

---

## 1. SEC-003 — version scope: PER_VERSION. Decision 2.1 is CLOSED.

The rule put to him, as the QA page then stated it:

> Approved contact access extends to the entire Award family, not only the
> version a person is listed on

His answer:

> **"I don't believe this statement is true. Only if the staff is listed on the
> version should they have access. We would not want people not listed to
> access records"**

So `app.authorization.version-scope` = **`PER_VERSION`**, and `FAMILY_WIDE` is
rejected. This closes §2.1 of
[`AUTHORIZATION_POLICY_DECISIONS_OUTSTANDING.md`](AUTHORIZATION_POLICY_DECISIONS_OUTSTANDING.md),
which had been open with the question *"if someone is authorized for an Award's
current version, should they see that Award's entire history?"*

**Consequence to carry forward, not a defect.** Under `PER_VERSION` a family
that is only partly visible makes the AI summary and question endpoints refuse
with `AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS`, by the derived
family-completeness rule. That is the designed behaviour of this decision: an
AI summary synthesised from versions the reader may not see would be a wrong
answer dressed as a complete one. Expect it, and do not file it as a bug.

Real DEV data where this is visible: Awards `212137-00001` and `212137-00002`
each have three versions whose lead unit changes mid-family — sequence 1 sits
in CGS ADMINISTRATION (`1250400000`), sequences 2 and 3 in COLLEGE OF GENERAL
STUDIES (`1250000000`). A UNIT grant on `1250000000` therefore reaches
sequences 2 and 3 and **not** sequence 1.

---

## 2. Requirements for later, approved releases

**Not in scope for the first enforcement window**, recorded so they are not
lost. None of these is implemented by the authorization work released to date.

### SEC-002 — bulk department assignment at lead-unit level

> "Looks correct. Please note that department assignments change frequently, so
> we need the ability to update them in bulk at the lead unit level to manage
> them effectively."

Today a UNIT grant is issued one grantee at a time through the operator CLI.
There is no bulk path and no self-service way to re-point a cohort when a
department is reorganised. This is a **new capability**, not a configuration
change, and it needs its own design and approval.

### SEC-005 — grant levels

> "Should be able to add access at the following levels: Parent Award · IO
> (child) · Fund Center aka Lead Unit — department users are managed at this
> level"

All three already exist as grant types: `PARENT_AWARD`, `IO`, `UNIT`.

Note the naming: **"Fund Center" is the lead unit**, already verified against
the Kuali SAP integration source. A separate `FUND_CENTER` grant type would
duplicate `UNIT` and must not be added. See
It is recorded as verified against the Kuali SAP integration source.

### SEC-008 — archive activity auditing

> "Archive activity should be logged"

A clear business requirement for auditing to be ON. It is **OFF** today, and
deliberately so: the five recording points are wired but collection is
disabled, and switching it on without choosing a failure policy stops the
application from starting rather than silently picking one. Turning it on is a
separate approved release — see
[`SEC008_AUDIT_DECISION_MATRIX.md`](SEC008_AUDIT_DECISION_MATRIX.md) for the
failure-policy choice this still needs.

### SEC-001 — central users

> "You are correct in that we don't have roles for all central staff in Kuali.
> I've attached a list of users who would fall under this category"

Attachment: `CentralUsers_SP_Directory_10_2026.xlsx` (29 KB), held in the
email. **Not committed** — it is a roster of named staff, and the repository is
not where that belongs. Whoever issues `CENTRAL` grants should take the list
from the email directly.

### Confirmed without change

SEC-004, SEC-006, SEC-007 and SEC-009 — "Looks correct".

---

## 3. What this does not resolve

| Decision | Status |
|---|---|
| **2.1** version scope | **CLOSED — `PER_VERSION`** |
| 2.2 `contact-derivation` | still open. Moot for the first grant: the bootstrapped identity's link carries `kuali_person_id = NULL`, so contact derivation can never match. `EXPLICIT_GRANT` is the safe value for a one-user pilot |
| 2.3 `research-staff-roles` | still open — these are BU role names and engineering cannot supply them. `PI` is used as a real placeholder, valid under `EXPLICIT_GRANT` |
| D-ID-2(a) *who* the first administrators are | answered in practice: `ARP-FK7QY195-QW5HV7G0`, bootstrapped under the project-owner approval of 2026-10-28 and confirmed for the DEV pilot on 2026-11-11 |
