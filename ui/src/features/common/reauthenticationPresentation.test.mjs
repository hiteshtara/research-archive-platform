import { test } from "node:test";
import assert from "node:assert/strict";

import {
  REAUTHENTICATION_COOLDOWN_MS,
  isReauthenticationRequired,
  shouldRedirectToSignIn,
} from "./reauthenticationPresentation.mjs";

test("only a 401 carrying REAUTHENTICATION_REQUIRED asks for a new sign-in", () => {
  assert.equal(isReauthenticationRequired(401, "REAUTHENTICATION_REQUIRED"), true);
  assert.equal(isReauthenticationRequired(401, undefined), false);
  assert.equal(isReauthenticationRequired(403, "REAUTHENTICATION_REQUIRED"), false);
  assert.equal(isReauthenticationRequired(403, "ACCESS_DENIED"), false);
  assert.equal(isReauthenticationRequired(404, undefined), false);
});

test("the first refusal redirects to sign-in", () => {
  assert.equal(shouldRedirectToSignIn(null, 1_000_000), true);
  assert.equal(shouldRedirectToSignIn(Number.NaN, 1_000_000), true);
});

test("a refusal soon after a redirect does not redirect again", () => {
  const last = 1_000_000;
  assert.equal(shouldRedirectToSignIn(last, last + 1), false);
  assert.equal(shouldRedirectToSignIn(last, last + REAUTHENTICATION_COOLDOWN_MS - 1), false);
  assert.equal(shouldRedirectToSignIn(last, last + REAUTHENTICATION_COOLDOWN_MS), true);
});

test("a clock that moved backwards does not block the redirect forever", () => {
  assert.equal(shouldRedirectToSignIn(2_000_000, 1_000_000), true);
});
