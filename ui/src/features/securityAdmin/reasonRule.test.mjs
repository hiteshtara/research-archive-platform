import test from "node:test";
import assert from "node:assert/strict";

import {
  PLACEHOLDER_PHRASES,
  REASON_MAX_LENGTH,
  REASON_MIN_LENGTH,
  isPlaceholderReason,
  isValidReason,
  normalizeReason,
  reasonProblem,
} from "./reasonRule.mjs";

const VALID = "Department chair approved access for the audit";

test("a real reason is accepted", () => {
  assert.equal(reasonProblem(VALID), null);
  assert.equal(isValidReason(VALID), true);
  assert.equal(isValidReason("Left BU on 2026-11-01"), true);
});

test("the reason is trimmed and whitespace collapsed before it is sent", () => {
  assert.equal(normalizeReason("  Left   the\tdepartment \n "), "Left the department");
  assert.equal(normalizeReason(null), "");
});

test("a blank reason is refused", () => {
  for (const blank of ["", "   ", "\n\t", null, undefined]) {
    assert.equal(reasonProblem(blank), "A reason is required.");
  }
});

test("a 9-character reason is refused and a 10-character one accepted", () => {
  assert.equal(REASON_MIN_LENGTH, 10);
  assert.equal(reasonProblem("Left dept"), "A reason must be at least 10 characters.");
  assert.equal(reasonProblem("Left depts"), null);
  // Collapsing happens first, so padding cannot reach the minimum.
  assert.equal(reasonProblem("Left      dept"), "A reason must be at least 10 characters.");
});

test("a 501-character reason is refused and a 500-character one accepted", () => {
  assert.equal(REASON_MAX_LENGTH, 500);
  const base = "Access approved by chair ";
  const exactly = (n) => (base.repeat(Math.ceil(n / base.length))).slice(0, n - 1) + "x";
  assert.equal(exactly(500).length, 500);
  assert.equal(reasonProblem(exactly(500)), null);
  assert.equal(reasonProblem(exactly(501)), "A reason must be at most 500 characters.");
});

test("every placeholder is refused, in any case and with punctuation", () => {
  const placeholders = [
    "test", "testing", "n/a", "na", "none", "null", "reason", "no reason",
    "asdf", "qwerty", "xxx", "tbd", "todo", "placeholder", "see above",
    "as discussed", "per request", "lorem ipsum",
  ];
  for (const phrase of placeholders) {
    assert.equal(isPlaceholderReason(phrase), true, phrase);
    assert.equal(isPlaceholderReason(phrase.toUpperCase() + "."), true, phrase);
    assert.equal(
      reasonProblem(phrase),
      "Describe the actual reason; placeholder text is not accepted.",
      phrase,
    );
  }
  assert.equal(isPlaceholderReason("N / A"), true);
  assert.equal(isPlaceholderReason("  See   above!! "), true);
  // The exported list and the rule agree.
  for (const phrase of PLACEHOLDER_PHRASES) {
    assert.equal(isPlaceholderReason(phrase), true, phrase);
  }
});

test("repeats and combinations of placeholders are refused", () => {
  for (const combo of [
    "test test test",
    "testing, testing, testing",
    "TBD - TODO",
    "n/a; none; null",
    "see above as discussed",
    "Lorem ipsum lorem ipsum",
    "no reason, per request",
  ]) {
    assert.equal(isPlaceholderReason(combo), true, combo);
    assert.notEqual(reasonProblem(combo), null, combo);
  }
});

test("a sentence that merely contains a placeholder word is accepted", () => {
  assert.equal(isPlaceholderReason("Testing the new unit grant for audit"), false);
  assert.equal(reasonProblem("Per request of the department chair"), null);
});

test("one character repeated is refused", () => {
  assert.equal(reasonProblem("aaaaaaaaaaaa"), "A reason cannot be one character repeated.");
  assert.equal(reasonProblem("a a a a a a a"), "A reason cannot be one character repeated.");
  assert.equal(reasonProblem("zzzzzzzzzzzzzzz!"), "A reason cannot be one character repeated.");
});

test("one word repeated is refused", () => {
  assert.equal(reasonProblem("access access access"), "A reason cannot be one word repeated.");
  assert.equal(reasonProblem("Grant, grant. GRANT!"), "A reason cannot be one word repeated.");
});

test("a single word is refused", () => {
  assert.equal(reasonProblem("Reorganisation"), "A reason must be at least two words.");
  // Numbers alone are not words.
  assert.equal(reasonProblem("INC21002534 12345"), "A reason must be at least two words.");
  assert.equal(reasonProblem("... !!! ???"), "A reason must be at least two words.");
  assert.equal(reasonProblem("Per INC21002534"), null);
});
