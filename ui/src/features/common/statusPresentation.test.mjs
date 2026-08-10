import assert from "node:assert/strict";
import test from "node:test";

import {
  resolveAwardStyleStatusVariant,
  resolveNegotiationStatusVariant,
  resolveStatusVariant,
  resolveSubawardStatusVariant,
} from "./statusPresentation.mjs";

// --- Award/Proposal (shared, unchanged keyword rule) --------------------

test("resolveAwardStyleStatusVariant treats a real Approved Award status as success", () => {
  assert.equal(resolveAwardStyleStatusVariant("Approved Award"), "success");
});

test("resolveAwardStyleStatusVariant treats a real Closed status as default", () => {
  assert.equal(resolveAwardStyleStatusVariant("Closed"), "default");
});

test("resolveAwardStyleStatusVariant treats a real PAFO/OSP (Closing) status as success, not default - 'closing' does not contain the substring 'closed'", () => {
  // A second, real quirk of the original keyword rule, preserved
  // unchanged alongside the "Cancelled" one above - not fixed here.
  assert.equal(
    resolveAwardStyleStatusVariant("PAFO/OSP (Closing)"),
    "success",
  );
});

test("resolveAwardStyleStatusVariant treats a real Compliance Hold status as success (no closed/pending match)", () => {
  assert.equal(resolveAwardStyleStatusVariant("Compliance Hold"), "success");
});

test("resolveAwardStyleStatusVariant preserves the original component's known bug: Cancelled has no closed/pending substring, so it renders success, not error", () => {
  // Real Award data has 1,213 rows with this exact status. Step 1 of
  // this migration is a pure move - fixing this misclassification is a
  // separate, deliberate decision, never a silent side effect of
  // sharing the component.
  assert.equal(resolveAwardStyleStatusVariant("Cancelled"), "success");
});

test("resolveAwardStyleStatusVariant applies the same rule to Proposal's real proposal_sequence_status values", () => {
  assert.equal(resolveAwardStyleStatusVariant("ARCHIVED"), "success");
  assert.equal(resolveAwardStyleStatusVariant("ACTIVE"), "success");
  assert.equal(resolveAwardStyleStatusVariant("PENDING"), "warning");
  // Same known bug as Award's "Cancelled" - "CANCELED" has no
  // closed/pending substring.
  assert.equal(resolveAwardStyleStatusVariant("CANCELED"), "success");
});

test("resolveAwardStyleStatusVariant treats a null status as success (the keyword rule's own fallback, unchanged)", () => {
  assert.equal(resolveAwardStyleStatusVariant(null), "success");
});

// --- Negotiation (explicit map from real archive.negotiation values) ----

test("resolveNegotiationStatusVariant categorizes all 5 real negotiation_status_description values", () => {
  assert.equal(resolveNegotiationStatusVariant("Fully Executed"), "success");
  assert.equal(resolveNegotiationStatusVariant("In Negotiation"), "warning");
  assert.equal(resolveNegotiationStatusVariant("Under Review"), "warning");
  assert.equal(
    resolveNegotiationStatusVariant("Signature In Process"),
    "warning",
  );
  assert.equal(resolveNegotiationStatusVariant("Abandoned"), "error");
});

test("resolveNegotiationStatusVariant renders neutral for an unrecognized status rather than guessing", () => {
  assert.equal(
    resolveNegotiationStatusVariant("Some New Status Never Seen Live"),
    "neutral",
  );
});

test("resolveNegotiationStatusVariant renders neutral for a null status", () => {
  assert.equal(resolveNegotiationStatusVariant(null), "neutral");
});

// --- Subaward (explicit map from real archive.subaward values) ----------

test("resolveSubawardStatusVariant categorizes the confidently-classifiable real status_description values", () => {
  assert.equal(resolveSubawardStatusVariant("01. RA Review"), "warning");
  assert.equal(
    resolveSubawardStatusVariant("02. Subaward RA initial review"),
    "warning",
  );
  assert.equal(
    resolveSubawardStatusVariant("03. Pending new or updated FRN"),
    "warning",
  );
  assert.equal(
    resolveSubawardStatusVariant("05. Sent to subrecipient"),
    "warning",
  );
  assert.equal(
    resolveSubawardStatusVariant("06. Subrecipient requesting revision"),
    "warning",
  );
  assert.equal(resolveSubawardStatusVariant("07. Executed"), "success");
  assert.equal(resolveSubawardStatusVariant("08. Closed"), "default");
  assert.equal(resolveSubawardStatusVariant("10. Cancelled"), "error");
});

test("resolveSubawardStatusVariant renders neutral for the two real statuses that can't be confidently categorized from text alone", () => {
  // "PI/DA" names no clear state; "Temporarily Cancelled" isn't the
  // same thing as a real terminal cancellation - neither is guessed
  // into warning or error.
  assert.equal(resolveSubawardStatusVariant("04. PI/DA"), "neutral");
  assert.equal(
    resolveSubawardStatusVariant("09. Temporarily Cancelled"),
    "neutral",
  );
});

test("resolveSubawardStatusVariant renders neutral for an unrecognized status rather than guessing", () => {
  assert.equal(
    resolveSubawardStatusVariant("99. Some New Status Never Seen Live"),
    "neutral",
  );
});

test("resolveSubawardStatusVariant renders neutral for a null status", () => {
  assert.equal(resolveSubawardStatusVariant(null), "neutral");
});

// --- Dispatcher -----------------------------------------------------------

test("resolveStatusVariant dispatches to the right domain-specific resolver", () => {
  assert.equal(resolveStatusVariant("award", "Closed"), "default");
  assert.equal(resolveStatusVariant("proposal", "PENDING"), "warning");
  assert.equal(
    resolveStatusVariant("negotiation", "Abandoned"),
    "error",
  );
  assert.equal(
    resolveStatusVariant("subaward", "10. Cancelled"),
    "error",
  );
});

test("resolveStatusVariant renders neutral for an unknown domain rather than throwing", () => {
  assert.equal(resolveStatusVariant("irb", "Anything"), "neutral");
});
