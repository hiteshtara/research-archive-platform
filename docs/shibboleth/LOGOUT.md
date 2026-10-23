# Sign-out and the BU Shibboleth IdP session

How sign-out is configured, the evidence behind it, and how to re-prove it.
Companion to [RUNBOOK.md](RUNBOOK.md) Step 11.

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

## Open with Kevin

1. `logout.jsp` does not end a session on his own IdP — its cookie names
   predate the `__Host-` prefix. He recommended it in good faith.
2. Confirm `shib.bu.edu/idp/profile/Logout` behaves identically before
   production.
3. Is a `SingleLogoutService` registration available for Cognito's
   `/saml2/logout`, or is the logout-flow redirect the supported pattern?
