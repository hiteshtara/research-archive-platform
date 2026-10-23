import assert from "node:assert/strict";
import test from "node:test";

import {
  isLoopbackHostname,
  resolveCanonicalRedirect,
} from "./canonicalOrigin.mjs";

const OLD = "https://main.d288p9gmoteftb.amplifyapp.com";
const CANONICAL = "https://nonprod.kuali-archive.bu.edu";
const LOCAL = "http://localhost:5173";

test("the retired Amplify URL redirects to the canonical origin", () => {
  const target = resolveCanonicalRedirect({
    canonicalOrigin: CANONICAL,
    currentHref: `${OLD}/`,
  });

  assert.equal(target, `${CANONICAL}/`);
});

test("the path is preserved so deep links keep working", () => {
  for (const path of ["/awards/search", "/subawards", "/signed-out"]) {
    const target = resolveCanonicalRedirect({
      canonicalOrigin: CANONICAL,
      currentHref: `${OLD}${path}`,
    });

    assert.equal(target, `${CANONICAL}${path}`);
  }
});

test("safe query parameters are preserved", () => {
  const target = resolveCanonicalRedirect({
    canonicalOrigin: CANONICAL,
    currentHref: `${OLD}/awards/search?q=cancer&page=3`,
  });

  assert.equal(target, `${CANONICAL}/awards/search?q=cancer&page=3`);
});

test("the canonical origin is never redirected, which is the loop guard", () => {
  for (const href of [
    `${CANONICAL}/`,
    `${CANONICAL}/awards/search?q=x`,
    `${CANONICAL}/signed-out`,
  ]) {
    assert.equal(
      resolveCanonicalRedirect({ canonicalOrigin: CANONICAL, currentHref: href }),
      null,
    );
  }
});

test("a trailing slash or path on the configured origin still compares by origin", () => {
  // VITE_CANONICAL_ORIGIN may be pasted with a trailing slash; that must not
  // make the canonical origin fail its own equality check and redirect to
  // itself forever.
  for (const configured of [CANONICAL, `${CANONICAL}/`, `${CANONICAL}/ignored`]) {
    assert.equal(
      resolveCanonicalRedirect({
        canonicalOrigin: configured,
        currentHref: `${CANONICAL}/`,
      }),
      null,
    );
  }
});

test("localhost development is never redirected", () => {
  for (const href of [
    `${LOCAL}/`,
    `${LOCAL}/awards/search`,
    "http://127.0.0.1:5173/",
    "http://app.localhost:5173/",
  ]) {
    assert.equal(
      resolveCanonicalRedirect({ canonicalOrigin: CANONICAL, currentHref: href }),
      null,
    );
  }
});

test("an unset canonical origin disables the redirect entirely (rollback)", () => {
  for (const canonicalOrigin of [undefined, null, ""]) {
    assert.equal(
      resolveCanonicalRedirect({ canonicalOrigin, currentHref: `${OLD}/` }),
      null,
    );
  }
});

test("a malformed canonical origin fails open rather than breaking the app", () => {
  for (const canonicalOrigin of ["not a url", "://nope", "/relative"]) {
    assert.equal(
      resolveCanonicalRedirect({ canonicalOrigin, currentHref: `${OLD}/` }),
      null,
    );
  }
});

// --- OAuth artefacts must never cross origins ----------------------------

test("an OAuth authorization code and state are stripped, not forwarded", () => {
  const target = resolveCanonicalRedirect({
    canonicalOrigin: CANONICAL,
    currentHref: `${OLD}/?code=abc123&state=xyz789`,
  });

  assert.equal(target, `${CANONICAL}/`);
  assert.ok(!target.includes("code="), "no authorization code is carried over");
  assert.ok(!target.includes("state="), "no state value is carried over");
  assert.ok(!target.includes("abc123"));
  assert.ok(!target.includes("xyz789"));
});

test("safe parameters survive while auth parameters beside them are stripped", () => {
  const target = resolveCanonicalRedirect({
    canonicalOrigin: CANONICAL,
    currentHref: `${OLD}/awards/search?q=cancer&code=abc123&page=2&state=xyz`,
  });

  assert.equal(target, `${CANONICAL}/awards/search?q=cancer&page=2`);
});

test("every known auth parameter is stripped", () => {
  const sensitive = [
    "code",
    "state",
    "session_state",
    "nonce",
    "code_verifier",
    "code_challenge",
    "error",
    "error_description",
    "error_uri",
    "id_token",
    "access_token",
    "refresh_token",
    "token",
  ];

  for (const name of sensitive) {
    const target = resolveCanonicalRedirect({
      canonicalOrigin: CANONICAL,
      currentHref: `${OLD}/?${name}=secretvalue`,
    });

    assert.equal(target, `${CANONICAL}/`, `${name} must be stripped`);
    assert.ok(!target.includes("secretvalue"), `${name} value must not leak`);
  }
});

test("a fragment carrying tokens is dropped entirely", () => {
  for (const fragment of [
    "access_token=secretvalue&token_type=bearer",
    "id_token=secretvalue",
    "foo=1&access_token=secretvalue",
  ]) {
    const target = resolveCanonicalRedirect({
      canonicalOrigin: CANONICAL,
      currentHref: `${OLD}/#${fragment}`,
    });

    assert.equal(target, `${CANONICAL}/`);
    assert.ok(!target.includes("secretvalue"), "no token leaks via the fragment");
  }
});

test("an ordinary fragment is preserved", () => {
  const target = resolveCanonicalRedirect({
    canonicalOrigin: CANONICAL,
    currentHref: `${OLD}/awards/search#results`,
  });

  assert.equal(target, `${CANONICAL}/awards/search#results`);
});

test("isLoopbackHostname recognises loopback hosts only", () => {
  for (const hostname of ["localhost", "127.0.0.1", "::1", "app.localhost"]) {
    assert.equal(isLoopbackHostname(hostname), true, hostname);
  }

  for (const hostname of [
    "nonprod.kuali-archive.bu.edu",
    "main.d288p9gmoteftb.amplifyapp.com",
    "notlocalhost.example.com",
  ]) {
    assert.equal(isLoopbackHostname(hostname), false, hostname);
  }
});
