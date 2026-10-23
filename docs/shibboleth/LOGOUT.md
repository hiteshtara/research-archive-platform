# Sign-out and the BU Shibboleth IdP session

How sign-out is configured, why it is configured that way, what was measured,
and how to prove it works. Companion to [RUNBOOK.md](RUNBOOK.md) Step 11.

## The problem this solves

Three independent sessions exist. Ending one does not end the others.

| Session | Where it lives | Ended by |
|---|---|---|
| Application | Amplify tokens in `localStorage` | `signOut({ global: false })` |
| Cognito | cookie on `*.auth.us-east-1.amazoncognito.com` | Hosted UI `/logout` |
| BU Shibboleth | `shib_idp_session`, `shib_idp_session_ss`, `_idp_session`, `JSESSIONID` on `shib-test.bu.edu`, `Path=/idp` | **only** the IdP's own logout page, or closing the browser |

Before this change `ui_logout_url` was `https://main.d288p9gmoteftb.amplifyapp.com/`
— one of our own origins. `resolveOauthRedirects` therefore rewrote it to
`{origin}/signed-out`, Cognito returned the browser to us, and **BU was never
contacted**. The first two sessions ended; the third did not, so the next
sign-in was answered by BU with no prompt.

`/signed-out` was added earlier to stop that re-login loop being visible. It
treated the symptom. This change treats the cause.

## Why not SAML single logout

Neither `shib-test.bu.edu` nor `shib.bu.edu` advertises a
`SingleLogoutService` in its metadata (`https://shib-test.bu.edu/idp/shibboleth`,
re-verified 2026-10-23: zero occurrences). Cognito's `IDPSignout = "true"`
therefore has nothing to drive. A redirect to the IdP's logout page is the
mechanism BU actually offers.

**The absence of an SLO endpoint does not mean BU cannot end its browser
session.** It can, and does — see below.

## The two candidate endpoints

Both probed unauthenticated on 2026-10-23.

| | `/idp/logout.jsp` | `/idp/profile/Logout` |
|---|---|---|
| Status | `200` | `302` → `?execution=e1s1` → `200` |
| Expires `shib_idp_session` | **yes** (`Max-Age=0`, empty value, `Path=/idp`) | no |
| Expires `shib_idp_session_ss`, `_idp_session`, `JSESSIONID` | **yes** | no |
| Page text | "You have successfully logged out of shib-test.bu.edu. You must close your browser to complete the logout process." | "The logout operation is complete, and no other services appear to have been accessed during this session." |
| Accepts `?return=<url>` | **yes** — answers `302` carrying the same four deletion cookies *and* `Location: <url>` | ignored |
| Confirmed by BU | yes, by Kevin Ruderman on INC21002534 | no |

**`/idp/logout.jsp` is the chosen endpoint.** It is the one BU confirmed, and
it is the only one of the two observed to expire the session cookies.

Caveat, stated because it matters: the `/idp/profile/Logout` probe carried no
IdP session, so it had nothing to clear. Its lack of deletion cookies is
consistent with — but is not proof of — doing nothing with a real session. What
is established is that `logout.jsp` clears unconditionally, which is the
stronger guarantee and is the reason to prefer it. A `200` and a confirmation
message on either endpoint prove nothing on their own; only the cookie
directives and the re-authentication test below do.

### The `?return=` variant, not adopted

`logout.jsp?return=https://nonprod.kuali-archive.bu.edu/signed-out` would end
the BU session *and* land the user back on our page in a single hop. It is not
used, for two reasons: Warren's design (and the acceptance test) expects the
user to see BU's logout page, and BU does not appear to allow-list the return
target — `?return=https://example.org/` was honoured, which makes it an open
redirect. Worth raising with Kevin separately; not something to depend on.

## Configuration

```hcl
# terraform/environments/dev/terraform.tfvars
cognito_logout_urls = [
  "https://main.d288p9gmoteftb.amplifyapp.com/",
  "http://localhost:5173/",
  "https://nonprod.kuali-archive.bu.edu/",
  "https://nonprod.kuali-archive.bu.edu/signed-out",  # rollback target
  "https://shib-test.bu.edu/idp/logout.jsp"           # the new sign-out target
]

ui_logout_url = "https://shib-test.bu.edu/idp/logout.jsp"
```

**Ordering is a hard dependency.** Cognito refuses any `logout_uri` that is not
on the app client's list, and it fails with `HTTP 400` at
`/error?error=Required+parameters+missing` rather than redirecting. Verified
against the live app client on 2026-10-23: `logout.jsp` was rejected that way,
while `/` and `/signed-out` were accepted. **Apply the Cognito change before
deploying a UI build that uses it**, or sign-out breaks for everyone.

## What the UI does

`logout()` is unchanged in substance — `signOut({ global: false })`, refresh
token revocation and local token cleanup all stay exactly as they were, and
`oauth: { redirectUrl }` is still passed explicitly so Amplify does not reject
the cross-origin target with `InvalidOriginException`.

One behavioural change: the deliberate-sign-out marker is now set **only when
sign-out does not route through the IdP**. The marker exists to compensate for
a BU session that outlives sign-out — it holds the user on `/signed-out`
instead of letting the gate bounce them through BU and straight back in. When
the BU session really is ended, that compensation is not just unnecessary, it
is wrong: it would show our page where BU's login screen belongs, which is the
exact thing sign-out is being asked to demonstrate.

The two configurations therefore stay coherent, and the rollback is complete:

| `ui_logout_url` | Sign-out lands on | Marker | Revisit behaviour |
|---|---|---|---|
| our own origin (rollback) | `/signed-out` | set | our signed-out page |
| BU logout page | BU's logout page | not set | BU login screen |

`/signed-out`, its route, the marker module and all their tests are retained
untouched for the rollback path.

## Proving it — the acceptance test

Run in a browser profile with **no** existing BU session (a fresh private
window is easiest). Do not record credentials, cookies, tokens or
authorization codes; status codes and navigation URLs are all that is needed.

1. Open `https://nonprod.kuali-archive.bu.edu` → BU Web Login → Duo. Land on
   the dashboard.
2. Click **Sign Out**.
3. Expect the browser to pass through
   `…amazoncognito.com/logout?client_id=…&logout_uri=https%3A%2F%2Fshib-test.bu.edu%2Fidp%2Flogout.jsp`
   and come to rest on `https://shib-test.bu.edu/idp/logout.jsp`, showing BU's
   "You have successfully logged out" page. A Cognito error page here means
   the Terraform change was not applied first.
4. In DevTools → Application → Cookies, confirm `shib_idp_session` on
   `shib-test.bu.edu` is **gone**. Note its presence or absence only; never
   copy its value.
5. Navigate to `https://nonprod.kuali-archive.bu.edu` again.
6. **Pass:** BU's login screen appears. **Fail:** the dashboard appears with no
   prompt.

Step 6 is the only step that settles the question. Steps 3 and 4 are
supporting evidence.

### Testing the IdP hop before any Terraform is applied

The expensive unknown — whether BU's logout page ends a *real* session — can be
settled with no infrastructure change at all, because step 3 can be performed
by hand:

1. Sign in normally at `https://nonprod.kuali-archive.bu.edu`.
2. Click Sign Out on the **current** build. It ends the application and Cognito
   sessions and lands on `/signed-out`.
3. Manually visit `https://shib-test.bu.edu/idp/logout.jsp`.
4. Open `https://nonprod.kuali-archive.bu.edu` in a **new tab**.
5. If BU prompts, the design is proven and only the configuration remains. If
   BU does not prompt, the design does not work and nothing should be deployed.

Step 4 must be a new tab: the current build's marker is per-tab
`sessionStorage`, and in the same tab it would show `/signed-out` and mask the
result.

## Rollback

Set `ui_logout_url` back to `https://main.d288p9gmoteftb.amplifyapp.com/`,
apply, and rebuild the Amplify branch. The UI restores `/signed-out` and the
marker by itself — no code change and no Cognito change, because every URL
involved stays on `cognito_logout_urls`.

## Open with Kevin (after testing, not before)

1. `logout.jsp?return=` honours an arbitrary off-site target
   (`https://example.org/` was accepted) — is that intended?
2. Does `shib.bu.edu` expose the same `/idp/logout.jsp`, for when this reaches
   production?
3. Is a `SingleLogoutService` registration available for Cognito's
   `/saml2/logout`, or is the logout-page redirect the supported pattern?
