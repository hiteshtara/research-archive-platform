# Authorization decisions needing sign-off

For Anthony. Seven decisions, each written as *what a user would see differently*
rather than as a setting.

Nothing here is configured. DEV runs with record-level authorization **OFF** and
no `APP_AUTHORIZATION_*` value set at all.

**Already approved — not reopened here:** `department-match =
LEAD_UNIT_WITH_DESCENDANTS` (D4), grants do not expire unless an expiry is set
(D-ADM-3), no second approver for a grant (D-ADM-2), IO means the Award account
number and reaches Awards only (D-A), an administrator-verified link is never
revoked by historical KRIM status (D-ID-1).

---

## 1. Version scope — `PER_VERSION` or `FAMILY_WIDE`

**The question.** An Award has a history. If someone is authorized for one
version of it, do they get the rest of that Award's history?

| | A department administrator sees | A researcher listed on v2 sees | The AI summary button |
|---|---|---|---|
| `PER_VERSION` | only the versions whose lead unit is theirs | v2 only | **refuses** on most Awards with history, saying access is partial |
| `FAMILY_WIDE` | the whole Award once any version matches | **v2 only — unchanged** | works |

**Recommendation: `FAMILY_WIDE`.**

An Award's lead unit can change between versions. Under `PER_VERSION` a
department administrator sees a fragmentary history — version 1 and 4 but not
2 and 3 — which reads as missing data rather than as a rule. It also denies the
AI features whenever a family is partly visible, and that is most families.

**The cost, stated plainly:** someone authorized for one version sees versions
they were not individually granted. For a department administrator that is the
intent. It does not loosen research-staff access at all — see below.

**This does not weaken SEC-003.** Contact-derived access is version-specific
under *both* settings, by construction: the evaluator checks the contact rule
against the record being read, never the family, and the SQL builder ORs the
contact clause outside the family wrap. Pinned by
`Sec003VersionScopeAgreementIntegrationTest`, which runs both halves against
real rows under each setting and compares the results.

---

## 2. Contact derivation — `EXPLICIT_GRANT` or `VERIFIED_PRINCIPAL`

**The question.** Does a researcher get access to their own Awards
automatically, or only after an administrator grants it?

| | `EXPLICIT_GRANT` | `VERIFIED_PRINCIPAL` |
|---|---|---|
| Who gets access | only people an administrator has granted it to | anyone whose verified link carries a Kuali principal, while they are a listed contact |
| Administrative load | one grant per person | none |
| When someone joins an Award | nothing happens until an administrator acts | access appears on its own |
| When someone leaves | access persists until revoked | access disappears on its own |
| "Who could see this record last March?" | answerable from the grant rows | **not answerable** — there are no rows |

**Recommendation: `EXPLICIT_GRANT` for the pilot.**

A pilot exists to be reviewed afterwards. `VERIFIED_PRINCIPAL` grants access
with no grant row at all, so the pilot could not be audited. `VERIFIED_PRINCIPAL`
may well be right for steady state — it removes real administrative load — but
it is a decision to take after the pilot, not during it.

---

## 3. Which Kuali contact roles are Research Staff

**The question.** `archive.award_person.contact_role_code` carries values like
`PI`, `COI`, `KP`. Which of them should mean "this person may read this Award
version"?

**Engineering cannot answer this.** They are BU role definitions.

**Starting point: `PI` only.** Unambiguous, and it makes the pilot's Research
Staff role testable. The real question is `COI` (co-investigator) and `KP`
(key personnel), which are the ones most likely to be expected and most likely
to widen access materially.

**What changes with each addition:** every person holding that role code, on
every Award version, gains read access to that version once contact derivation
is on. There is no partial version of this — a role code is in the set or it
is not.

---

## 4. Maximum BU sign-in age

**The question.** How long after someone's last BU login should the archive
keep trusting it?

A Cognito refresh token lasts 30 days and keeps the *original* sign-in time.
Without this setting, somebody who signed in once could keep reading records
for a month with no further BU authentication and no Duo prompt.

| Value | Effect |
|---|---|
| `8h` | a working day needs one sign-in; strictest |
| `12h` | **recommended starting point** — one sign-in per working day, tolerant of a long day |
| `24h` | one sign-in per calendar day |
| `720h` | the current effective behaviour, i.e. the refresh token's full life |

**Recommendation: `12h`**, with no authority behind it beyond being a defensible
default — this is a security decision, not a technical one.

---

## 5. Parent Award hierarchy depth

**The question.** A `PARENT_AWARD` grant names one Award and reaches the Awards
beneath it. How far down?

| Depth | Reaches |
|---|---|
| `1` | **recommended** — the named Award and its immediate children |
| `2`–`10` | further down the hierarchy, a level at a time |
| above `10` | refused at startup; the traversal clamps at 10, so a larger number would silently mean 10 |

**Recommendation: `1`.** No `PARENT_AWARD` grant exists, so the pilot does not
exercise this. It can be raised later without touching code.

---

## 6. Subaward inheritance for Research Staff

**The question.** A researcher is listed on an Award version. Should they also
see the Subawards funded by that Award?

**Current behaviour: yes**, and it follows from how Subaward authorization
already works. A Subaward is reachable when *any* of its funding Awards is
reachable (decision D6a, FAMILY-level ANY-match). A research-staff contact who
can reach the funding Award therefore reaches its Subawards.

**The consequence worth deciding:** a Subaward may be funded by an Award version
from *before* the researcher was listed on it. Under the ANY-match rule they
reach it anyway. The stricter reading — the researcher reaches only Subawards
whose funding Award version they are personally listed on — is not implemented.

**Recommendation: leave as is for the pilot**, and record the decision. The
current behaviour is the more permissive of the two readings and should be an
explicit choice rather than an accident of the Subaward rule.

---

## 7. Parent Award inheritance into Subawards and Negotiations

**The question.** Should a `PARENT_AWARD` grant also reach the Subawards and
Negotiations hanging off the Awards it covers?

**Current behaviour: no, deliberately.** `PARENT_AWARD` decides Awards and only
Awards. When a Subaward or Negotiation consults its Award, the evaluator is
called with `DecisionContext.INHERITED` and parent-award grants are ignored.

**Why it was built that way.** A `PARENT_AWARD` grant is already an expansion —
one grant reaching many Awards. Letting it expand again into every Subaward and
Negotiation of every reached Award makes the blast radius of a single grant hard
to predict, and no business requirement asked for it.

**Recommendation: leave restricted.** If the business wants it, it is a code
change with its own tests, not a setting.

---

## Summary

| # | Decision | Recommendation | Who decides |
|---|---|---|---|
| 1 | Version scope | `FAMILY_WIDE` | Anthony |
| 2 | Contact derivation | `EXPLICIT_GRANT` for the pilot | Anthony |
| 3 | Research Staff roles | `PI` to start | Anthony / BU role owners |
| 4 | Max sign-in age | `12h` | Anthony / security |
| 5 | Parent Award depth | `1` | Anthony — not urgent |
| 6 | Subaward inheritance for Research Staff | leave as is, record it | Anthony |
| 7 | Parent Award into Subawards/Negotiations | leave restricted | Anthony — not urgent |

Decisions 1–4 block enforcement: with any of them unset the application now
**refuses to start** rather than starting and denying every record to everyone.
Decisions 5–7 do not block the pilot.
