# User acceptance testing — plan and participant assessment

Drafted 2026-11-11. **Nothing here is approved, scheduled or provisioned.**

## 1. The headline: nobody else can test yet

Under the permission configuration as it stands, **none of the eight notified
BU users can meaningfully take part in authorization testing.**

`authz.access_grant` holds exactly one row — grant 2, `UNIT 1250000000`, issued
to the designated test identity. Every other identity has **no grant**, and
under record-level enforcement no grant means no archive at all. Note the
shape of that denial, because it is **not** a 404: `requireProvisioned()` runs
before any record is read, so the Dashboard itself returns **403
`ACCESS_NOT_PROVISIONED`** and the UI renders "Your access has not been set up
yet" (`AccessNotice`, reached from `DashboardPage`). An ungranted user does not
see a zeroed dashboard and does not see a not-found page.

So the Thursday 12 November window is a **technical** acceptance test of the
authorization engine, run by one tester against one grant. It is not, and
cannot be, user acceptance testing. UAT needs its own window, its own grants
and its own approval — see §8.

## 2. Who is actually a tester

The eight were notified because enforcement affects them, not because they
were all asked to test. Two different groups, from the record:

### Invited testers — asked to test, in writing

The 2026-10-23 email *"Research Archive Platform – New URL and Testing
Request"* asked these people to test specific modules. Five of the six have
DEV accounts:

| User | Name | Evidence |
|---|---|---|
| `lsreddy@bu.edu` | Sudhireddy, Lalitha | **filed the TC-040 / TC-041 Global Search findings** — the most active tester on record |
| `lhuval@bu.edu` | Huval, Shari | invited; project correspondence on BU data migration |
| `dhaywood@bu.edu` | Haywood, Dean | invited; authored the Grants & Agreements solution architecture proposal |
| `wrh@bu.edu` | Hennemuth, Warren | invited |
| `jfnick@bu.edu` | Nickerson, John | invited |

*(Samantha McGarrigle, `smcgarri@bu.edu`, was invited but has never signed in
and has no Cognito account. Not currently a participant.)*

### Business approver — not a functional tester

| User | Name | Role | Why not a tester |
|---|---|---|---|
| `antcast@bu.edu` | Castellano, Anthony | Senior Business Analyst for Proposal Development, Operations Analytics and Systems; signs as Product Owner, Huron Grants and Agreements | He answers **business decisions** — he closed SEC-003/version-scope on 2026-11-05. The right person to *accept* SEC-001…009, not to run test scripts |

### Notification-only — affected, no evidence of testing

| User | Name | Role | Why notification-only |
|---|---|---|---|
| `dbaldwin@bu.edu` | Baldwin, Diane | **Associate Vice President of Sponsored Programs** | Native-only account from 2026-08-11; has **never signed in through BU SSO**; not on the test invite |
| `rolilly@bu.edu` | Lilly, Renna | **Assistant VP, Pre-Award** | First signed in 2026-11-09; not on the test invite |

Both are senior stakeholders. They should keep receiving notifications and
should not be handed test scripts unless they ask.

**Do not read this as clearance.** All eight still require notification before
any enforcement window — being "notification-only" is a statement about their
test role, not about whether enforcement affects them.

## 3. Role, department and scope — what is verified and what is not

Roles and departments below come from **Research Administration All Staff
Directory, October 2026**, attached by the Product Owner to his 2026-11-05
email as the people who "fall under" SEC-001 Central Users.

| User | Department (verified) | Scope the business rule implies |
|---|---|---|
| `dbaldwin` | Sponsored Programs | **CENTRAL** |
| `antcast` | Operations, Analytics, and Systems | **CENTRAL** |
| `rolilly` | Pre-Award | **CENTRAL** |
| `lsreddy` | **not in the directory** | **unknown — must be asked** |
| `lhuval` | **not in the directory** | **unknown — must be asked** |
| `dhaywood` | **not in the directory** | **unknown — must be asked** |
| `wrh` | **not in the directory** | **unknown — must be asked** |
| `jfnick` | **not in the directory** | **unknown — must be asked** |

Two things follow, and both matter.

**For central staff, CENTRAL is the correct scope, not an over-grant.** The
Product Owner's SEC-001 answer is that BU has no Kuali roles for central staff
and that everyone in that directory is a central user. Giving `dbaldwin`,
`antcast` or `rolilly` CENTRAL access reflects the business rule. It is not the
same as handing CENTRAL to a tester for convenience.

**For the five invited testers, scope cannot be determined from any record.**
They are not in the Sponsored Programs directory, which suggests project or IT
roles rather than central research administration — but absence from a
directory is not a department. Their lead unit must be **asked**, not inferred,
and certainly not inferred from an email address. Until then no UNIT grant can
be proposed for them.

## 4. Tester guide

*Written for the BU testers, not for engineers.*

### Signing in

1. Go to **https://nonprod.kuali-archive.bu.edu**
2. Choose BU Web Login and sign in with your BU credentials and Duo.
3. **Sign in fresh at the start of your testing session.** See §5 — a session
   older than 12 hours stops working, and it looks exactly like a permissions
   fault.

### What you should be able to see

You will be told your own scope before you start — a department (lead unit), a
specific Award family, or central access. Check that:

- the records inside your scope open normally, and
- the Dashboard counts look like *your* share of the archive rather than all
  of it. Seeing smaller numbers than before is expected.

### What you should **not** be able to see

There are **two different denials**, and telling them apart is the single most
useful thing a tester can do.

**Outside your scope — 404, "not found".** A record that exists but is not
yours behaves as if it does not exist. That is deliberate: a denial that
announced itself would confirm the record exists and hint whose it is.

**No access provisioned at all — 403, "Your access has not been set up yet."**
This is about *your account*, not about any record, so it is safe to say
plainly, and saying it is what tells you to ask for access rather than go
hunting for a bug.

So:

- *"Not found for a record I think I should see"* — worth reporting.
- *"Not found for a record outside my department"* — the system working.
- *"Your access has not been set up yet"* — you have no grant yet. Report it,
  but as a provisioning request, not as a defect.

### Checking department-level access

If your access is by department:

1. Open an Award whose Lead Unit is your department — it should open.
2. Open an Award in a **different** department — it should be not-found.
3. If your access is meant to include **sub-departments**, check one of those
   too. If it is not, a sub-department record should be not-found.

A wrinkle worth knowing: an Award's lead unit **can change between versions**.
Under the approved rule you see only the versions listed under your
department, so an Award's history may legitimately appear incomplete. The AI
summary will also decline on such a record — that is expected, not a bug.

### How to report

Send to the project contact, with:

- the **page URL**
- what you expected and what you got
- the **time** (so it can be matched to logs)
- a screenshot if you have one

Report in particular: a record you **can** see that you believe you should not
(the most important finding of all), a record you cannot see that you should,
and any error page or spinner that never resolves.

Do **not** report "Your access has not been set up yet" as a wrong-wording
defect — that 403 is the designed response for an account with no grant. What
*would* be a defect is the opposite: wording that names a record, a department
or a person you were denied, or an "access denied" message where a plain **not
found** was expected for an out-of-scope record.

## 5. The 12-hour sign-in age, and why it matters to testers

`app.authorization.max-sign-in-age = 12h`.

A Cognito refresh keeps the **original** `auth_time`, so without this setting a
refresh token could keep a session alive indefinitely with no new BU login.
With it, a session whose original login is more than 12 hours old is refused.

For testers this has one practical consequence: **sign out and sign in again at
the start of the session.** Otherwise a stale morning session can start
refusing part-way through, and the symptom — records disappearing — is
indistinguishable from an authorization defect. Any such report should be
re-tested after a fresh login before being filed.

## 6. Test matrix

To be completed **once each participant's scope is confirmed and granted**. The
scope column is deliberately empty for the five testers: it is not knowable
from any record available today.

| User | Role in testing | Assigned scope | Expected accessible | Expected denied | Pass criteria |
|---|---|---|---|---|---|
| `lsreddy` | tester | *TBC — ask* | records in scope | everything outside it | every in-scope record opens; every out-of-scope record returns **404**; the Dashboard never returns 403 once a grant is active |
| `lhuval` | tester | *TBC — ask* | " | " | " |
| `dhaywood` | tester | *TBC — ask* | " | " | " |
| `wrh` | tester | *TBC — ask* | " | " | " |
| `jfnick` | tester | *TBC — ask* | " | " | " |
| `antcast` | business acceptance | CENTRAL (per SEC-001) | all records | none | confirms SEC-001…009 meet the business rules |
| `dbaldwin` | notification only | none | — | — | no test assigned |
| `rolilly` | notification only | none | — | — | no test assigned |
| **No-Grant Control** | control | **none, deliberately** | nothing | everything | sees no records and is told access is not provisioned |

The No-Grant Control must stay unprovisioned. It is the only case that proves
denial is the default rather than an accident of configuration.

## 7. Defects that must be corrected before wider UAT

### UAT-BLOCK-1 — Global Search tells an ungranted user to "try again shortly"

**Found** 2026-11-11, while verifying the authorization response codes. Not
fixed: no application code is being changed during window preparation.

**Behaviour.** Global Search fans out to one leg per module and joins them in
`joinOrRecordFailure`, which catches `RuntimeException` per leg, logs a
warning, and adds the module to `failedModules`. Under enforcement each leg
calls `requireProvisioned()`, so for a caller with no grant **every** leg
throws `AccessNotProvisionedException` and every module is recorded as failed.

The response is therefore **HTTP 200 with every module in `failedModules`** —
not the 403 the same caller receives everywhere else, and not "0 results". The
UI then renders its partial-failure warning:

> "… could not be searched just now, so this is not the whole archive. Try
> again shortly before concluding there is nothing to find."

**Why it blocks wider UAT.** A permissions problem is described to the user as
a transient outage. The message invites the tester to retry indefinitely
instead of asking for access, and it is the opposite of the advice in §4: the
tester is told to expect "Your access has not been set up yet", and Global
Search alone will not say it. With several ungranted testers this would
generate retry reports rather than provisioning requests.

**Severity.** Medium — misleading, not unsafe. Nothing is disclosed: the
caller learns only that a module failed, which is what a genuine outage also
reports. There is no leak and no incorrect authorization decision.

**What a correction looks like** (not implemented, for the fixing PR to
decide): distinguish an authorization failure from a transient leg failure
inside `joinOrRecordFailure` — either let the provisioning exception propagate
so the request answers 403 consistently with every other endpoint, or classify
it so the UI can show the access notice instead of the retry warning. The
first is more consistent; the second keeps partial results working for a
*scoped* caller whose single module legitimately fails.

**Not in scope for the 12 November technical window.** That window runs with
one provisioned tester, who will not hit this path.

## 8. UAT needs its own window

Keep this separate from the 13 technical acceptance tests.

| | Technical acceptance (13 tests) | User acceptance |
|---|---|---|
| Who | one designated test identity | the five invited testers, plus business acceptance |
| Proves | the authorization engine decides correctly | the rules match how BU actually works |
| Grants needed | grant 2 only — **exists** | one per participant — **none exist** |
| Window | Thu 12 Nov, 4:00–5:30 pm ET, approved | **not scheduled** |

UAT cannot run inside the technical window, for a simple reason: the only
grant belongs to the test identity, so every participant would see nothing and
every test would "fail" for a reason that has nothing to do with the software.

**Prerequisites before a UAT window can even be proposed:**

1. Each participant's lead unit or required scope, **asked and confirmed** —
   not inferred.
2. An identity link per participant (each already has a Cognito identity; none
   has an archive identity).
3. A grant per participant, scoped and time-limited, each individually
   approved.
4. A decision on whether central staff receive CENTRAL, which is the widest
   scope in the system and per SEC-001 is the correct one for them.
5. Its own notification, since enforcement would again be on.

Each of those is a separate approval. **Nothing in this document authorizes
any of them**, and no permission should be granted to anyone merely to make a
test pass — a test that passes because the tester was over-granted has
measured nothing.
