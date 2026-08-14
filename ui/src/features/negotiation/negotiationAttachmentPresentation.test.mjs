import assert from "node:assert/strict";
import test from "node:test";

import { resolveRestrictedLabel } from "./negotiationAttachmentPresentation.mjs";

test("resolveRestrictedLabel shows Y as marked restricted", () => {
  assert.equal(
    resolveRestrictedLabel("Y"),
    "Marked restricted in legacy Kuali",
  );
});

test("resolveRestrictedLabel shows N as not restricted", () => {
  assert.equal(
    resolveRestrictedLabel("N"),
    "Not restricted in legacy Kuali",
  );
});

test("resolveRestrictedLabel is honest about a null/missing value rather than guessing Y or N", () => {
  assert.equal(
    resolveRestrictedLabel(null),
    "Restricted status unknown in legacy Kuali",
  );
  assert.equal(
    resolveRestrictedLabel(undefined),
    "Restricted status unknown in legacy Kuali",
  );
  assert.equal(
    resolveRestrictedLabel(""),
    "Restricted status unknown in legacy Kuali",
  );
});

test("resolveRestrictedLabel surfaces an unexpected legacy value verbatim rather than coercing it to Y/N", () => {
  assert.equal(
    resolveRestrictedLabel("MAYBE"),
    "Legacy Kuali RESTRICTED value: MAYBE",
  );
});
