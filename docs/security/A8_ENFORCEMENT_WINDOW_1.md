# A8 enforcement window 1: the eight code-fixed criteria

**Status: proposed, for the owner's separate approval. Nothing has been run.**

**Scope:** a short, time-boxed DEV window that turns on record-level
enforcement and proves live the eight BU criteria whose fixes are already on
`main`. **CENTRAL and scenario S1 are deferred:** CENTRAL grants are refused
until phase 2, V090 and a second administrator are in place.

**Unchanged throughout the window:**
- activity auditing **OFF**;
- administrator writes **OFF**;
- V089 and V090 **unapplied**;
- Explorer **OFF**;
- IRB exclusion as left by A2, or untouched if A2 is not approved;
- Production untouched.

**Prerequisites:**
- **A1 complete:** current `main` deployed to DEV and verified, giving the
  "A1 revision". The image in revision 105 predates every fix and must not be
  the one tested.
- **A6 sub-steps A6-0 to A6-3 complete** (`A6_SYNTHETIC_IDENTITIES.md`).

---

## 1. The eight criteria and how each is probed

Records are BU's own: A `212128-00002` and B `211144-00007` (unit
`2573180018`), and C `209407-00002` (unit `1330420000`, IO `9500312705`).

| Criterion | Wording | Identity | Probe (each asserts the response body, not only the status) | Relies on |
|---|---|---|---|---|
| **US-DEP-001 #1** | User can only see records within assigned lead units | S1 `dept` | Award search finds A and B | record gate |
| **US-DEP-001 #2** | User cannot see records from unauthorized departments | S1 `dept` | C is absent from search. C summary and C by-number return 404 with no C number or title. C's Time-and-Money transaction or document requested through A returns 404. A's SAP transmissions do not name C | H1, H3 (#164) |
| **US-DEP-005 #1** | Unauthorized records are not visible | S1 `dept` | A's related Proposals, Subawards and Negotiations do not name C. Negotiation list/search and associated-record do not reveal C. The AI evidence search on A does not surface C | #159, H2 (#164), H5 (#165) |
| **US-DEP-005 #2** | Search results only return authorized records | S1 `dept` | Award search and Global Search for C's number return nothing naming C | record scoping |
| **US-PI-001 #2** | PI cannot access records belonging to other investigators | S2 `pi` (**A6 §3 option (a) only**) | A found, B absent from search and Global Search, B summary 404 | contact derivation, SEC-003 |
| **US-OAV-001 #2** | Unauthorized records are not displayed | S3 `oav` | C found, B absent, B summary 404. B's T&M transaction requested through C returns 404. C's SAP transmissions do not name B | H1, H3 (#164) |
| **US-SEC-003 #1** | Unauthorized records cannot be opened | S1, S2, S3 | Every out-of-scope summary or by-number request returns a 404 indistinguishable from a missing record | record gate, H1 |
| **US-SEC-003 #2** | Unauthorized searches do not return restricted records | S1, S2, S3 | Every out-of-scope number is absent from Award search, Global Search and Negotiation search | H2, #159 |

The controls apply to every identity:
- **No-Grant Control (S4):** returns 403 `ACCESS_NOT_PROVISIONED` with no record
  data. A silent empty list is a failure.
- **Capabilities:** every identity reports `enforcementEnabled=true` and
  `administrationWritesAvailable=false`.

**Under option (b)**, with no synthetic PI:
- US-PI-001 #2 moves to the BU session with the real T-PI account;
- the automated window then covers **7** criteria;
- US-SEC-003 is still covered by S1 and S3.

**N-161 is not probed here.** It concerns administrator relinking, which needs
writes, and writes stay off. The live guard is that any write returns
`ADMINISTRATION_WRITES_DISABLED`.

---

## 2. The revision to register

It is registered from the **A1 revision**, copied exactly, with only these
variables added. No `terraform apply`, and the image pin is not reconciled.

```
APP_AUTHORIZATION_ENFORCEMENT_ENABLED=true
APP_AUTHORIZATION_DEPARTMENT_MATCH=LEAD_UNIT_WITH_DESCENDANTS
APP_AUTHORIZATION_RESEARCH_STAFF_ROLES=PI,MPI,COI
APP_AUTHORIZATION_VERSION_SCOPE=<owner decision; recommended FAMILY_WIDE>
APP_AUTHORIZATION_CONTACT_DERIVATION=<owner decision; recommended EXPLICIT_GRANT>
APP_AUTHORIZATION_MAX_SIGN_IN_AGE=<owner decision; recommended 12h>
```

These are left exactly as in the A1 revision:
- `APP_AUTHORIZATION_ADMINISTRATION_ENABLED=true` (read-only administration);
- `APP_EXPLORER_ENABLED=false`.

These are **not** set:
- any administration-writes variable;
- any activity-audit variable;
- any enrollment variable.

**The names bind.**
- `AuthorizationProperties` is `@ConfigurationProperties("app.authorization")`.
  Spring maps `APP_AUTHORIZATION_ENFORCEMENT_ENABLED` to `enforcement-enabled`
  through its documented dash-to-underscore environment form.
- That is the same form by which `APP_AUTHORIZATION_ADMINISTRATION_ENABLED`
  already binds `administrationEnabled` live on revision 105 (administration
  reads are on in DEV today).
- The first probe confirms the binding from outside:
  `/api/v1/me/capabilities` must report `enforcementEnabled=true`.

**The three owner decisions** (recommendations from `PHASE2_PILOT_PLAN.md` §2):

| Setting | Recommended | Consequence for this window |
|---|---|---|
| `version-scope` | `FAMILY_WIDE` | `PER_VERSION` makes the AI endpoints refuse partly visible families. The eight criteria are unaffected either way |
| `contact-derivation` | `EXPLICIT_GRANT` | Every access is a reviewable grant row. Needed for S2 under option (a) |
| `max-sign-in-age` | `12h` | Longer than the window; the probes sign in fresh at the start |

`PI,MPI,COI` is the owner's 2026-11-17 decision. Co-PI = MPI still awaits BU
confirmation (decision D-7), which affects only US-PI-001 #1, not #2.

**Startup safeguard.** If any required value is missing or invalid (empty role
set, non-positive sign-in age), the application **refuses to start** and names
every problem (`AuthorizationEnforcementStartupTest`). The rolling deployment
then never replaces the A1 task (100% minimum healthy), and DEV stays on the A1
revision. That is a failed window, not an outage.

---

## 3. Running order (about 2 hours)

| Time | Step | Who |
|---|---|---|
| T−1 day | Notice to DEV users (text below) | owner |
| T−30 min | `capture-baseline` with enforcement **still OFF**: resolves A/B/C ids, confirms their units, records a T&M transaction or document for B and C and a Negotiation for C. **Refuses** if any record is missing or in the wrong unit. Record the Dashboard totals | operator |
| T0 | Register the window revision and `update-service`. Wait for the deployment to complete | operator |
| T+10 | Probe 1, capabilities: `enforcementEnabled=true`, `writes=false` for all four identities. **No-go if not** | probe tool |
| T+15 | Full probe run (30 probes; 26 under option (b)). Evidence written | probe tool |
| T+30 | Go / no-go (section 4) | owner |
| T+30 to T+100 | Optional: BU session (Lalitha) for S2–S6 with BU's named T-accounts, if they have been provisioned. Otherwise automation only | BU |
| T+110 | **Close:** `update-service` back to the A1 revision. Confirm `enforcementEnabled=false` | operator |
| T+120 | A6 close-out: revoke, unlink, disable the synthetic users | operator |

Commands:

```bash
python3 -I scripts/a8_enforcement_probe.py self-test                       # no network
python3 -I scripts/a8_enforcement_probe.py plan [--no-pi]                  # no network
RAP_PROBE_TOKEN_BASELINE=... python3 -I scripts/a8_enforcement_probe.py capture-baseline \
    --execute --base-url https://<DEV API origin> --fixture a8-baseline.json
RAP_PROBE_TOKEN_DEPT=... RAP_PROBE_TOKEN_PI=... RAP_PROBE_TOKEN_OAV=... RAP_PROBE_TOKEN_NONE=... \
  python3 -I scripts/a8_enforcement_probe.py run --execute \
    --base-url https://<DEV API origin> --fixture a8-baseline.json --evidence a8-evidence.json [--no-pi]
```

How the tool handles tokens and evidence:
- **Getting tokens:** each one comes from
  `aws cognito-idp admin-initiate-auth --auth-flow ADMIN_USER_PASSWORD_AUTH`
  using that synthetic user's Secrets Manager entry.
- **Never exposed:** tokens are passed only through environment variables. The
  tool never prints, logs or writes them.
- **Refusals:** it refuses a non-https URL and any host that looks like
  production.
- **The baseline token** is any signed-in DEV identity able to read A/B/C while
  enforcement is still OFF. S4 works: it has no grant, and with enforcement off
  it reads everything.

### Probe design: lessons already learned here

- **Check the page identity first.** Every response must be JSON. A relative
  `/api` on the UI origin returns `index.html` with 200, which would make every
  negative assertion pass vacuously. A non-JSON response therefore fails the
  probe outright.
- **Assert on the body, not only the status.** A denial must be a 404 *and*
  must not contain the hidden record's number or title.
- **Positive controls sit beside the negative ones.** Each identity must find
  its own record, or its negative results mean nothing.
- **Evidence:** each probe records its role, method, path, status, verdict,
  failure reasons and a SHA-256 of the body. Bodies are hashed, not stored, so
  the evidence file contains no record data.

---

## 4. Go / no-go and abort

**Abort immediately** (`update-service` to the A1 revision, before T+110) if
any of these happens:

- a capabilities probe reports `enforcementEnabled=false` or
  `administrationWritesAvailable=true`;
- **any** `LEAK` in a negative probe: a hidden record's number or title appears
  in a body;
- the No-Grant Control receives record data or a 200 list;
- DEV becomes unusable for reasons other than intended scoping, such as 5xx on
  in-scope reads;
- the deployment does not reach steady state within 15 minutes. In that case
  the safeguard has held and DEV is still on A1.

**Go (criterion evidence accepted)** requires all of these:

- every probe mapped to a criterion is PASS;
- every positive control is PASS;
- the H5 probe may be `NOT TESTABLE (AI off)`. In that case H5 is recorded as
  untested, not passed.

**A FAIL is not fixed inside the window.** Close the window, raise a PR, then
schedule another window.

---

## 5. What the result can and cannot change

- **The tracker moves only on PASS evidence from this window,** recorded with
  the evidence file and the A1/window revision numbers.
  - Without the BU session: at most the 8 criteria (7 under option (b))
    become **automated-verified live**.
  - Business acceptance stays a separate column. The BU count of 25/47 changes
    only as the owner directs on that evidence.
- **The Central User criteria** (already counted on synthetic evidence) are
  **not** re-verified in this window, because S1 is deferred.
- **US-SEC-004 is untouched,** because auditing is off.

---

## 6. Notice to DEV users (send the day before)

> On **<date>**, from **<start>** to **<end>** (Eastern), record-level access
> control will be switched on in the DEV Research Archive for a short test.
> During that time, accounts that have not been set up for the test will see
> "No archive access has been provisioned for this account" instead of
> records. Nothing is deleted or changed; access returns automatically when
> the test ends. Production is not affected. Questions: <contact>.

---

## 7. Rollback

| What | How | Time |
|---|---|---|
| Enforcement | `aws ecs update-service --cluster research-archive-platform-dev-api --service research-archive-platform-dev-api --task-definition research-archive-platform-dev-api:<A1 revision>` | one rolling deployment |
| Synthetic access | A6 §5: revoke grants, unlink identities, disable Cognito users | minutes |
| Further back | The A1 revision's own rollback, to `:105` | one deployment |

Enforcement writes nothing. With auditing off, the window leaves no rows behind
apart from the A6 provisioning history, which is retained by design.

---

## 8. Approval

**A8 window 1. Each line is approved separately.**

| # | Item | Value | Approved |
|---|---|---|---|
| 1 | A1 revision this window is built from | `research-archive-platform-dev-api:____` (image `sha256:________`) | ☐ |
| 2 | A6 S2 (Research Staff) | ☐ option (a) scope-only link ☐ option (b) no synthetic PI | ☐ |
| 3 | `version-scope` | ☐ FAMILY_WIDE ☐ PER_VERSION | ☐ |
| 4 | `contact-derivation` | ☐ EXPLICIT_GRANT ☐ VERIFIED_PRINCIPAL | ☐ |
| 5 | `max-sign-in-age` | ______ (recommended 12h) | ☐ |
| 6 | Window date and time (≤ 2 h) | ____-__-__  __:__–__:__ ET | ☐ |
| 7 | DEV-user notice sent | date ______ | ☐ |
| 8 | BU session in the window | ☐ yes (T-accounts provisioned) ☐ no, automation only | ☐ |

**Stays off:** auditing, administrator writes, V089, V090, Explorer, CENTRAL/S1.
**Production:** untouched.

Approved by: ______________________ Date: __________ Approval reference: __________
