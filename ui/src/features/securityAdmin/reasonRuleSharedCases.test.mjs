import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { isValidReason, normalizeReason } from "./reasonRule.mjs";

/*
 * The shared reason-rule cases. The API's ReasonsTest reads the same file and
 * asserts Reasons.require decides every case the same way, so the UI rule and
 * the server rule cannot drift: a case added for one side is a case for both.
 */
const cases = JSON.parse(
  readFileSync(new URL("../../../../docs/security/reason-rule-cases.json", import.meta.url), "utf8"),
);

test("the shared case file is present and not trivially small", () => {
  assert.ok(Array.isArray(cases));
  assert.ok(cases.length > 50, `only ${cases.length} cases`);
});

for (const [index, { input, valid, why }] of cases.entries()) {
  test(`shared case ${index}: ${valid ? "accepted" : "refused"} (${why})`, () => {
    assert.equal(isValidReason(input), valid, JSON.stringify(input));
  });
}

test("an accepted reason is sent normalised, as the server stores it", () => {
  for (const { input, valid } of cases) {
    if (valid) {
      assert.equal(normalizeReason(input), normalizeReason(normalizeReason(input)));
    }
  }
});
