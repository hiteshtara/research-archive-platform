# Sign-out and the BU Shibboleth IdP session

**Status: DEPLOYED AND VERIFIED in dev, 2026-10-23.** PR #60 merged as
`dc24395`, Amplify build 137, bundle `index-CcovNZ5H.js`. Warren's five-step
acceptance test passes end to end against the deployed application — see
[Deployment verification](#deployment-verification-2026-10-23).

**Dev logout endpoint: `https://shib-test.bu.edu/idp/profile/Logout`.**
`/idp/logout.jsp` is unsuitable for this configuration and must not be used.
Production is **not** covered — see [Production](#production-not-yet-verified).

How sign-out is configured, the evidence behind it, and how to re-prove it.
Companion to [RUNBOOK.md](RUNBOOK.md) Step 11.

## What went wrong, and whose part was whose

Two independent faults compounded. They are worth separating, because only one
of them involved anyone outside this repository.

**Fault 1 — ours, and the larger one. Sign-out never contacted BU at all.**
Warren's PR #47 specified two settings together: `VITE_COGNITO_IDENTITY_PROVIDER`
*and* `VITE_COGNITO_LOGOUT_URL` pointed at the IdP. Only the first was carried
into the deployed configuration. `ui_logout_url` stayed on
`https://main.d288p9gmoteftb.amplifyapp.com/` — one of our own origins — so
`resolveOauthRedirects` rewrote it to `{origin}/signed-out` and Cognito
returned the browser to us. **No endpoint recommendation could have mattered,
because no IdP endpoint was ever reached.** `/signed-out` was then added to
stop the resulting re-login loop being visible, which treated the symptom and
made the underlying gap harder to see. This was entirely our own doing: a
half-applied recipe, followed by a fix aimed at the wrong layer.

**Fault 2 — an endpoint recommendation that did not hold.** Asked for the
logout endpoint, Kevin Ruderman gave `/idp/logout.jsp` (INC21002534). It is a
reasonable answer: it is BU-branded, answers `200`, and says "You have
successfully logged out". It does not work for this configuration, because it
expires cookies named `shib_idp_session`, `shib_idp_session_ss`, `_idp_session`
and `JSESSIONID` at `Path=/idp`, while the live session cookie is
`__Host-shib_idp_session` at `Path=/`. Different names; the deletions cannot
match. It appears to predate the `__Host-` prefix.

**But we compounded fault 2 ourselves.** Commit `7634922` rewrote the runbook
away from Warren's `/idp/profile/Logout` on the strength of Kevin's reply plus
our own *unauthenticated* probe, in which the logout flow looked inert — it
looked inert only because there was no session for it to end. We then reported
a failure of `logout.jsp` from a browser profile that was not clean, and drew a
further wrong conclusion from it (a supposed "upstream BU credential session"
that does not exist). Three of those four errors were ours.

**Net:** the delay is substantially ours. Kevin gave one endpoint that turned
out not to suit this setup; we never asked him to verify it against an
authenticated session, and we overrode a working specification from Warren on
weak evidence. Warren's original recipe was correct as written.

## The problem

Three independent sessions exist. Ending one does not end the others.

| Session | Where it lives | Ended by |
|---|---|---|
| Application | Amplify tokens in `localStorage` | `signOut({ global: false })` |
| Cognito | `cognito` cookie on `*.auth.us-east-1.amazoncognito.com` | Hosted UI `/logout` |
| BU Shibboleth | **`__Host-shib_idp_session`** on `shib-test.bu.edu` | **only** `/idp/profile/Logout`, or closing the browser |

Before this change `ui_logout_url` was `https://main.d288p9gmoteftb.amplifyapp.com/`
— one of our own origins — so `resolveOauthRedirects` rewrote it to
`{origin}/signed-out`, Cognito returned the browser to us, and **BU was never
contacted**. The first two sessions ended; the third did not, so the next
sign-in was answered with no prompt. `/signed-out`, added later, hid the
re-login loop rather than removing it.

## The endpoint: measured, not assumed

Two independent **clean** Chrome profiles (fresh `--user-data-dir`), headed,
with interactive BU password + Duo on each. Both began with a full password
challenge, which is what establishes the profiles were genuinely clean.

| | **`/idp/profile/Logout`** (PR #47) | `/idp/logout.jsp` |
|---|---|---|
| Initial challenge | password + Duo | password + Duo |
| After app sign-out | `__Host-shib_idp_session` present | present |
| Page shown | stock Shibboleth logout template | BU-branded "You have successfully logged out" |
| **`__Host-shib_idp_session` after** | **deleted** | **survives** |
| Revisit in a new tab | `shib-test` SSO, **password field** | **dashboard, no challenge** |
| **Verdict** | **PASS** | **FAIL** |

**`logout.jsp`'s success message is false.** It expires cookies named
`shib_idp_session`, `shib_idp_session_ss`, `_idp_session` and `JSESSIONID` at
`Path=/idp`. The live session cookie is `__Host-shib_idp_session`, which the
`__Host-` prefix pins to `Path=/`. Those are different cookie names, so the
deletions cannot match. It is a legacy page predating the prefix.

Two earlier conclusions in this repo were wrong and are retracted:

- There is **no** "upstream BU credential session" surviving logout. That was
  inferred from a Duo-only sign-in in a profile that was not clean.
- Commit `7634922`, which rewrote the runbook from `/idp/profile/Logout` to
  `logout.jsp`, was a mistake. It relied on an unauthenticated probe where the
  logout flow looked inert because there was no session to end.

A `200` and a confirmation message prove nothing. Only the cookie state and
the re-authentication result do.

### Why not SAML single logout

Neither `shib-test.bu.edu` nor `shib.bu.edu` advertises a
`SingleLogoutService`, so `IDPSignout = "true"` has nothing to drive and there
is no cross-service logout. That absence says nothing about whether the IdP
can end its own browser session — it can, and `/idp/profile/Logout` does.

## Configuration

```hcl
cognito_logout_urls = [
  "https://main.d288p9gmoteftb.amplifyapp.com/",
  "http://localhost:5173/",
  "https://nonprod.kuali-archive.bu.edu/",
  "https://nonprod.kuali-archive.bu.edu/signed-out",   # rollback target
  "https://shib-test.bu.edu/idp/profile/Logout"        # sign-out target
]

ui_logout_url = "https://shib-test.bu.edu/idp/profile/Logout"
```

**Ordering is a hard dependency.** Cognito refuses a `logout_uri` that is not
on the app client's list, failing with `HTTP 400` at
`/error?error=Required+parameters+missing` rather than redirecting — confirmed
against the live client. Apply the Cognito change **before** deploying a UI
build that uses it.

## What the UI does

`signOut({ global: false })`, refresh-token revocation, local token cleanup
and the explicit cross-origin `oauth: { redirectUrl }` are all unchanged. No
new OAuth scopes.

Two safeguards:

**An allow-list in source, not in configuration.**
`APPROVED_IDP_LOGOUT_URLS` in `ui/src/features/auth/authRedirects.mjs` is the
only set of endpoints sign-out will redirect to. "Not one of our origins" is
not a sufficient test — `logout.jsp` is external, returns 200 and claims
success while doing nothing. The list is held in source deliberately: an
allow-list living in the same tfvars as the value it validates would be
defeated by the same typo. Anything unapproved **fails closed** to
`{origin}/signed-out`, so a mistaken or hostile value can never become a
sign-out redirect target. Comparison is by parsed URL, so a trailing slash or
host capitalisation does not defeat it.

`https://shib.bu.edu/idp/profile/Logout` is listed so production needs no code
change, but it is **unverified** — run the test below against it first.

**The marker tracks what we use, not what BU does.** `signOutUsesIdpLogout`
is named for the endpoint being used; whether the session actually ends is a
fact about BU, established by testing. The deliberate-sign-out marker is set
only when it is false. The marker compensates for a BU session that outlives
sign-out; when the session really ends, keeping it would show our page where
BU's login screen belongs.

| `ui_logout_url` | Sign-out lands on | Marker | Revisit |
|---|---|---|---|
| our own origin (rollback) | `/signed-out` | set | our signed-out page |
| approved BU endpoint | BU's logout page | not set | BU login screen |
| anything else | `/signed-out` (fail closed) | set | our signed-out page |

`/signed-out`, its route, the marker module and their tests are retained
untouched for the rollback path.

## Re-proving it

Use a **clean profile** — a fresh `--user-data-dir`, not a private window of a
browser already signed in to BU. Record cookie presence only, never values,
and never capture credentials.

1. Clean profile → `https://nonprod.kuali-archive.bu.edu`.
2. **Confirm BU asks for a password.** Straight to Duo means the profile is
   not clean; start over. This control is what the earlier failed test lacked.
3. Sign in. Confirm the dashboard.
4. Click **Sign out**. Expect `localStorage` Cognito keys 7 → 0 and the
   browser to come to rest on BU's logout page.
5. Confirm `__Host-shib_idp_session` on `shib-test.bu.edu` is **gone**.
6. Open the app in a **new tab**.
7. **PASS:** BU password challenge. **PARTIAL:** Duo only. **FAIL:** dashboard
   with no challenge.

Record both the initial and the post-logout challenge; the pair is what
distinguishes a successful logout from a profile that was never clean.

## Rollback

Set `ui_logout_url` back to `https://main.d288p9gmoteftb.amplifyapp.com/`,
apply, rebuild. The UI restores `/signed-out` and the marker by itself — no
code change and no Cognito change, because every URL stays on
`cognito_logout_urls`.

## Deployment verification (2026-10-23)

Applied and verified in **dev only**. Terraform apply was `0 to add, 2 to
change, 0 to destroy` — 117 resources, 115 no-op — after the tfvars
reconciliation described in
[TERRAFORM_DRIFT_RECONCILIATION.md](../operations/TERRAFORM_DRIFT_RECONCILIATION.md).

| Change | Result |
|---|---|
| `aws_cognito_user_pool_client.app.logout_urls` | 6 existing entries preserved, `…/idp/profile/Logout` added |
| `aws_amplify_app.ui` → `VITE_COGNITO_LOGOUT_URL` | → `https://shib-test.bu.edu/idp/profile/Logout` |
| PR #60 | merged as `dc24395`, all CI green |
| Amplify job 137 | BUILD / DEPLOY / VERIFY all SUCCEED |
| Bundle `index-CcovNZ5H.js` | contains `/idp/profile/Logout`, zero `logout.jsp` |

Acceptance test, fresh `--user-data-dir` Chrome profile, interactive sign-in,
profile destroyed afterwards. No passwords, tokens, cookie values or SAML
assertions were captured — only URLs, cookie *names*, and token-key counts.

| Step | Observed |
|---|---|
| Initial authentication | **BU password + Duo** (the clean-profile control) |
| Dashboard reached | 7 `CognitoIdentityServiceProvider` keys; `__Host-shib_idp_session` present |
| Sign Out clicked | Cognito token revocation succeeded; local keys 7 → 0; Cognito cookies gone |
| Redirect target | browser came to rest on **`shib-test.bu.edu/idp/profile/Logout`** |
| IdP session cookie | **`__Host-shib_idp_session` removed** |
| Revisit in a new tab | `shib-test` SSO with a **password field**; not signed in |
| **Verdict** | **PASS** |

Confirmed unchanged by reading live AWS state after the apply: the
`BUShibboleth` identity provider, the user pool, Cognito users and the
`ArchiveAttachmentViewer` group and its membership, callback URLs, OAuth
scopes, `VITE_COGNITO_IDENTITY_PROVIDER`, `VITE_CANONICAL_ORIGIN`, ECS, RDS,
DNS and the Subaward nightly synchronisation.

## Production — not yet verified

`https://shib.bu.edu/idp/profile/Logout` is on the code allow-list so a
production rollout needs no code change. **It has never been tested against an
authenticated production session, and must not be assumed to behave like the
test IdP.** The two IdPs are separately operated and separately configured;
`logout.jsp` is itself proof that a plausible BU logout URL can return `200`,
claim success, and leave the session usable.

Before any production deployment, run the full acceptance test above against
`shib.bu.edu` from a clean profile and record both the initial and the
post-logout challenge. Treat a `200`, a confirmation message, or the dev
result as evidence of nothing.

## Open with Kevin

1. `logout.jsp` does not end a session on his own IdP — its cookie names
   predate the `__Host-` prefix. He recommended it in good faith.
2. Confirm `shib.bu.edu/idp/profile/Logout` behaves identically before
   production.
3. Is a `SingleLogoutService` registration available for Cognito's
   `/saml2/logout`, or is the logout-flow redirect the supported pattern?
