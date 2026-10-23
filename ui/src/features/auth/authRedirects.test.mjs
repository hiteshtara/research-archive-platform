import assert from "node:assert/strict";
import test from "node:test";

import {
  originOf,
  parseAllowedOrigins,
  resolveOauthRedirects,
} from "./authRedirects.mjs";

const AMPLIFY = "https://main.d288p9gmoteftb.amplifyapp.com";
const CUSTOM = "https://nonprod.kuali-archive.bu.edu";
const LOCAL = "http://localhost:5173";

// The deployed build's configuration: the bundle is built with the Amplify URL
// as its redirect, and the allow-list mirrors the Cognito app client's
// callback URLs.
const deployed = {
  allowedOriginsRaw: `${LOCAL}/,${AMPLIFY}/,${CUSTOM}/`,
  configuredSignInUrl: `${AMPLIFY}/`,
  configuredSignOutUrl: `${AMPLIFY}/`,
};

test("login started at the Amplify URL returns to the Amplify URL", () => {
  const { signIn, signOut, originAware } = resolveOauthRedirects({
    ...deployed,
    currentOrigin: AMPLIFY,
  });

  assert.equal(signIn, `${AMPLIFY}/`);
  assert.equal(signOut, `${AMPLIFY}/`);
  assert.equal(originAware, true);
});

test("login started at the custom domain returns to the custom domain", () => {
  const { signIn, signOut, originAware } = resolveOauthRedirects({
    ...deployed,
    currentOrigin: CUSTOM,
  });

  assert.equal(signIn, `${CUSTOM}/`);
  assert.equal(signOut, `${CUSTOM}/`);
  assert.equal(originAware, true);
});

test("local Vite dev returns to localhost:5173", () => {
  // Local dev builds from ui/.env.local, where both configured URLs are
  // already localhost - so this must hold whether or not the allow-list
  // variable is set at all.
  for (const allowedOriginsRaw of [undefined, `${LOCAL}/`, deployed.allowedOriginsRaw]) {
    const { signIn, signOut } = resolveOauthRedirects({
      allowedOriginsRaw,
      configuredSignInUrl: `${LOCAL}/`,
      configuredSignOutUrl: `${LOCAL}/`,
      currentOrigin: LOCAL,
    });

    assert.equal(signIn, `${LOCAL}/`);
    assert.equal(signOut, `${LOCAL}/`);
  }
});

test("an origin outside the allow-list falls back to the configured URL", () => {
  const { signIn, signOut, originAware } = resolveOauthRedirects({
    ...deployed,
    currentOrigin: "https://attacker.example.com",
  });

  assert.equal(signIn, `${AMPLIFY}/`);
  assert.equal(signOut, `${AMPLIFY}/`);
  assert.equal(originAware, false);
});

test("a look-alike origin is not treated as approved", () => {
  for (const origin of [
    "https://nonprod.kuali-archive.bu.edu.attacker.example",
    "http://nonprod.kuali-archive.bu.edu",
    "https://evil.nonprod.kuali-archive.bu.edu",
  ]) {
    const { signIn, originAware } = resolveOauthRedirects({
      ...deployed,
      currentOrigin: origin,
    });

    assert.equal(originAware, false, `${origin} must not be origin-aware`);
    assert.equal(signIn, `${AMPLIFY}/`);
  }
});

test("the build-time sign-in origin is approved even without an allow-list", () => {
  const { signIn, allowedOrigins, originAware } = resolveOauthRedirects({
    allowedOriginsRaw: undefined,
    configuredSignInUrl: `${AMPLIFY}/`,
    configuredSignOutUrl: `${AMPLIFY}/`,
    currentOrigin: AMPLIFY,
  });

  assert.equal(signIn, `${AMPLIFY}/`);
  assert.equal(originAware, true);
  assert.deepEqual(allowedOrigins, [AMPLIFY]);
});

test("an unset allow-list leaves a second origin unapproved", () => {
  // Guards the deployment requirement: without VITE_COGNITO_ALLOWED_ORIGINS in
  // the Amplify build, the custom domain cannot become origin-aware.
  const { signIn, originAware } = resolveOauthRedirects({
    allowedOriginsRaw: undefined,
    configuredSignInUrl: `${AMPLIFY}/`,
    configuredSignOutUrl: `${AMPLIFY}/`,
    currentOrigin: CUSTOM,
  });

  assert.equal(originAware, false);
  assert.equal(signIn, `${AMPLIFY}/`);
});

test("an external identity-provider logout URL is preserved verbatim", () => {
  // PR #47 points VITE_COGNITO_LOGOUT_URL at Shibboleth's logout page on
  // purpose, to end the IdP session. Origin-aware sign-in must not override
  // it, or logout silently signs the user straight back in.
  const idpLogout = "https://shib-test.bu.edu/idp/logout.jsp";

  const { signIn, signOut } = resolveOauthRedirects({
    ...deployed,
    configuredSignOutUrl: idpLogout,
    currentOrigin: CUSTOM,
  });

  assert.equal(signIn, `${CUSTOM}/`);
  assert.equal(signOut, idpLogout);
});

test("a missing or empty current origin falls back to configured values", () => {
  for (const currentOrigin of [undefined, null, ""]) {
    const { signIn, signOut, originAware } = resolveOauthRedirects({
      ...deployed,
      currentOrigin,
    });

    assert.equal(signIn, `${AMPLIFY}/`);
    assert.equal(signOut, `${AMPLIFY}/`);
    assert.equal(originAware, false);
  }
});

test("redirect targets are never read from query parameters", () => {
  // A redirect target smuggled into the URL must be ignored entirely: the only
  // candidate this module ever considers is the current origin.
  const { signIn, signOut } = resolveOauthRedirects({
    ...deployed,
    currentOrigin: AMPLIFY,
  });

  assert.equal(signIn, `${AMPLIFY}/`);
  assert.equal(signOut, `${AMPLIFY}/`);
  assert.ok(!signIn.includes("?"), "sign-in URL carries no query string");
  assert.ok(!signOut.includes("?"), "sign-out URL carries no query string");
});

test("parses allow-lists given as URLs, bare origins, or mixed separators", () => {
  assert.deepEqual(parseAllowedOrigins(`${AMPLIFY}/, ${CUSTOM}/`), [AMPLIFY, CUSTOM]);
  assert.deepEqual(parseAllowedOrigins(`${AMPLIFY} ${CUSTOM}`), [AMPLIFY, CUSTOM]);
  assert.deepEqual(parseAllowedOrigins(`${AMPLIFY}/,${AMPLIFY}/`), [AMPLIFY]);
  assert.deepEqual(parseAllowedOrigins(""), []);
  assert.deepEqual(parseAllowedOrigins(undefined), []);
});

test("unparseable allow-list entries are skipped, not thrown", () => {
  assert.deepEqual(parseAllowedOrigins(`not-a-url, ${CUSTOM}/, ::::`), [CUSTOM]);
  assert.equal(originOf("not-a-url"), null);
  assert.equal(originOf(undefined), null);
  assert.equal(originOf(`${CUSTOM}/some/path?x=1`), CUSTOM);
});

// --- post-logout landing path -------------------------------------------
//
// Returning to "/" after logout is what made sign-out appear to do nothing:
// that route is behind the auth gate, so it started a fresh BU redirect and
// BU's still-valid SSO session signed the user straight back in. The
// application therefore sends Cognito to a public post-logout page.

test("sign-out goes to the post-logout path on the origin the user is on", () => {
  for (const origin of [AMPLIFY, CUSTOM, LOCAL]) {
    const { signOut } = resolveOauthRedirects({
      ...deployed,
      currentOrigin: origin,
      signOutPath: "/signed-out",
    });

    assert.equal(signOut, `${origin}/signed-out`);
  }
});

test("the post-logout path still applies when the origin is not approved", () => {
  // Falls back to the build-time origin, but must not fall back to "/" - that
  // would reintroduce the re-login loop for exactly the users already on an
  // unexpected origin.
  const { signOut, originAware } = resolveOauthRedirects({
    ...deployed,
    currentOrigin: "https://not-approved.example.com",
    signOutPath: "/signed-out",
  });

  assert.equal(originAware, false);
  assert.equal(signOut, `${AMPLIFY}/signed-out`);
});

test("an external identity-provider logout URL ignores the post-logout path", () => {
  // The path is ours; an IdP's logout page has its own. Appending to it would
  // produce a URL that does not exist at the provider.
  const idpLogout = "https://shib-test.bu.edu/idp/logout.jsp";

  const { signOut } = resolveOauthRedirects({
    ...deployed,
    configuredSignOutUrl: idpLogout,
    currentOrigin: CUSTOM,
    signOutPath: "/signed-out",
  });

  assert.equal(signOut, idpLogout);
});

test("omitting the post-logout path preserves the original root behaviour", () => {
  const { signOut } = resolveOauthRedirects({
    ...deployed,
    currentOrigin: CUSTOM,
  });

  assert.equal(signOut, `${CUSTOM}/`);
});

// --- BU Shibboleth IdP logout (Warren's PR #47 Step 11) ---------------------
//
// The deployed configuration these cover is ui_logout_url pointing at BU's
// Shibboleth logout page rather than at one of our own origins. Cognito's
// /logout cannot end the IdP session; handing the browser to BU's logout page
// is the only mechanism BU's test IdP offers, because its metadata advertises
// no SingleLogoutService.

const BU_LOGOUT = "https://shib-test.bu.edu/idp/logout.jsp";

test("the BU logout URL survives verbatim from every approved origin", () => {
  // The whole fix depends on this: if the resolver rewrote the external URL to
  // one of our own origins - which is what it does for an own-origin value -
  // Cognito would return the browser to us and BU would never be contacted.
  // That is exactly the defect being fixed, so it is asserted per origin.
  for (const currentOrigin of [CUSTOM, AMPLIFY, LOCAL]) {
    const { signOut, signOutEndsIdpSession } = resolveOauthRedirects({
      ...deployed,
      configuredSignOutUrl: BU_LOGOUT,
      currentOrigin,
      signOutPath: "/signed-out",
    });

    assert.equal(signOut, BU_LOGOUT, `rewritten while on ${currentOrigin}`);
    assert.equal(signOutEndsIdpSession, true);
  }
});

test("an unapproved origin still signs out through BU, not through us", () => {
  const { signOut, originAware, signOutEndsIdpSession } = resolveOauthRedirects({
    ...deployed,
    configuredSignOutUrl: BU_LOGOUT,
    currentOrigin: "https://not-approved.example.com",
    signOutPath: "/signed-out",
  });

  assert.equal(originAware, false);
  assert.equal(signOut, BU_LOGOUT);
  assert.equal(signOutEndsIdpSession, true);
});

test("an own-origin logout URL reports that the IdP session is NOT ended", () => {
  // The rollback configuration. /signed-out and the deliberate-sign-out marker
  // both stay in place for it, because in that configuration BU's session does
  // outlive sign-out and something has to hold the user off a gated route.
  for (const currentOrigin of [CUSTOM, AMPLIFY]) {
    const { signOut, signOutEndsIdpSession } = resolveOauthRedirects({
      ...deployed,
      currentOrigin,
      signOutPath: "/signed-out",
    });

    assert.equal(signOut, `${currentOrigin}/signed-out`);
    assert.equal(signOutEndsIdpSession, false);
  }
});

test("a malformed logout URL does not claim to end the IdP session", () => {
  // Fails towards keeping the marker, which is the defensive behaviour and
  // also the behaviour before any of this existed.
  const { signOutEndsIdpSession } = resolveOauthRedirects({
    ...deployed,
    configuredSignOutUrl: "not a url",
    currentOrigin: CUSTOM,
    signOutPath: "/signed-out",
  });

  assert.equal(signOutEndsIdpSession, false);
});

test("the BU logout origin is never added to the approved-origin list", () => {
  // It is an allowed Cognito logout target, never an origin this application
  // is served from. Were it to leak into the list, signOutIsOwnOrigin would
  // become true and the URL would be rewritten to shib-test.bu.edu/signed-out.
  const { allowedOrigins } = resolveOauthRedirects({
    ...deployed,
    configuredSignOutUrl: BU_LOGOUT,
    currentOrigin: CUSTOM,
    signOutPath: "/signed-out",
  });

  assert.ok(!allowedOrigins.includes("https://shib-test.bu.edu"));
});
