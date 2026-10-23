# Research Archive sign-out — what was wrong and what fixed it

**For:** Warren Hennemuth, Kevin Ruderman
**Date:** 2026-10-23 · **Environment:** development only
**Status:** fixed and verified in dev; production untested

## Summary

Signing out of Research Archive left the user able to return without
re-authenticating. It is fixed. The endpoint now in use is the one Warren
originally specified in PR #47:

```
https://shib-test.bu.edu/idp/profile/Logout
```

Warren's recipe was correct as written. Most of the delay was ours.

## Two faults, and they were not equal

**1. Sign-out never reached BU at all — our error, and the bigger one.**

PR #47 specified two settings together: redirect sign-in to the IdP, *and*
point the sign-out URL at the IdP. Only the first reached our deployed
configuration. Our sign-out URL stayed pointed at our own site, so AWS Cognito
ended its own session and handed the browser straight back to us. BU was never
contacted. We then added a "you have signed out" page, which hid the resulting
immediate re-login rather than fixing it.

No endpoint recommendation could have changed this outcome, because no BU
endpoint was ever being called.

**2. `/idp/logout.jsp` does not end the session — worth knowing, Kevin.**

When we asked for the logout endpoint (INC21002534), the answer was
`/idp/logout.jsp`. It looks right: BU-branded, returns HTTP 200, and says
"You have successfully logged out of shib-test.bu.edu."

It does not log the user out. It expires cookies named `shib_idp_session`,
`shib_idp_session_ss`, `_idp_session` and `JSESSIONID` at `Path=/idp`. The
cookie your IdP actually sets is **`__Host-shib_idp_session`** at `Path=/`.
Different names, so the deletions never match, and the session remains fully
usable. It appears to predate the `__Host-` prefix.

**This is worth checking for other BU services**, because anything else told
to use `logout.jsp` has the same silent failure, with a success message on
screen.

**We then made fault 2 worse ourselves.** We rewrote our runbook away from
Warren's `/idp/profile/Logout` on the strength of that recommendation plus our
own unauthenticated probe, in which the logout flow looked inert — it looked
inert only because there was no session to end. We also reported a failure of
`logout.jsp` from a browser that already held a BU session, and drew a further
wrong conclusion from it. Three of those four mistakes were ours.

## Evidence

Two clean browser profiles, interactive BU password and Duo on each, nothing
shared between them.

| | `/idp/profile/Logout` (Warren) | `/idp/logout.jsp` (Kevin) |
|---|---|---|
| Initial sign-in | password + Duo | password + Duo |
| Page shown | standard Shibboleth logout page | "You have successfully logged out" |
| `__Host-shib_idp_session` afterwards | **deleted** | **still present** |
| Returning to Research Archive | **BU password challenge** | **straight to the dashboard, no prompt** |
| Result | **PASS** | **FAIL** |

A 200 response and a confirmation message turned out to prove nothing. Only
the cookie state and the re-authentication result did.

## Deployed and verified

PR #60, merged as `dc24395`; Amplify build 137. The Terraform change was two
in-place updates, nothing created and nothing destroyed: the sign-out URL was
added to the Cognito allow-list, and the UI's sign-out target was changed.

End-to-end test against the deployed application, clean profile:

1. Open Research Archive → BU Web Login → **password + Duo** → dashboard.
2. Click Sign Out → application and Cognito sessions cleared.
3. Browser lands on BU's logout page at `/idp/profile/Logout`.
4. `__Host-shib_idp_session` is gone.
5. Return to Research Archive → **BU asks for a password again.**

No Cognito users, groups or attachment permissions were changed.

## Two open questions for BU

1. **Is `/idp/logout.jsp` meant to still be advertised?** It reports success
   while leaving the session live. If other BU applications were given the
   same endpoint, they have the same problem and no visible symptom.
2. **Does `https://shib.bu.edu/idp/profile/Logout` behave the same as test?**
   We will not assume it does. We will run this same authenticated test
   against production before switching it, and would welcome confirmation
   that the production IdP exposes the same logout flow.

Separately, and lower priority: `logout.jsp?return=<url>` redirects to any
off-site URL we pass it — `https://example.org/` was accepted. Worth a look as
an open-redirect, independent of everything above.
