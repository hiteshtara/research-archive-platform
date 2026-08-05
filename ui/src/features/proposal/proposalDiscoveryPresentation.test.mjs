import assert from "node:assert/strict";
import test from "node:test";

import {
  SAVED_SEARCHES,
  effectiveAwardAmount,
  effectiveAwardAmountBasis,
  formatCompactCurrency,
} from "./proposalDiscoveryPresentation.mjs";

test("formatCompactCurrency formats millions as $X.XM", () => {
  assert.equal(formatCompactCurrency(19399383), "$19.4M");
});

test("formatCompactCurrency formats hundred-thousands as $XK", () => {
  assert.equal(formatCompactCurrency(501092.34), "$501K");
});

test("formatCompactCurrency formats small amounts as a plain currency string", () => {
  assert.equal(formatCompactCurrency(50000), "$50,000");
});

test("formatCompactCurrency returns null for null/undefined, never a fake $0", () => {
  assert.equal(formatCompactCurrency(null), null);
  assert.equal(formatCompactCurrency(undefined), null);
});

test("effectiveAwardAmount prefers obligated over anticipated", () => {
  const row = { obligatedAmount: 1500000, anticipatedAmount: 1200000 };
  assert.equal(effectiveAwardAmount(row), 1500000);
});

test("effectiveAwardAmount falls back to anticipated when obligated is null", () => {
  const row = { obligatedAmount: null, anticipatedAmount: 1200000 };
  assert.equal(effectiveAwardAmount(row), 1200000);
});

test("effectiveAwardAmount returns null when neither is present - never 0", () => {
  const row = { obligatedAmount: null, anticipatedAmount: null };
  assert.equal(effectiveAwardAmount(row), null);
});

test("effectiveAwardAmountBasis reports which field actually backed the number", () => {
  assert.equal(
    effectiveAwardAmountBasis({ obligatedAmount: 1, anticipatedAmount: 2 }),
    "obligated",
  );
  assert.equal(
    effectiveAwardAmountBasis({ obligatedAmount: null, anticipatedAmount: 2 }),
    "anticipated",
  );
  assert.equal(
    effectiveAwardAmountBasis({ obligatedAmount: null, anticipatedAmount: null }),
    null,
  );
});

test("every saved search has a unique key and non-empty filters", () => {
  const keys = new Set(SAVED_SEARCHES.map((preset) => preset.key));
  assert.equal(keys.size, SAVED_SEARCHES.length);
  for (const preset of SAVED_SEARCHES) {
    assert.ok(preset.label.length > 0);
    assert.ok(Object.keys(preset.filters).length > 0);
  }
});

test("the NIH saved search uses a sponsorName substring match, not a single code", () => {
  // Real data: NIH is fragmented across ~15 institute-specific
  // sponsor_codes (NCI, NHLBI, NIAID, ...) - a single exact sponsorCode
  // would silently miss almost all of them.
  const nih = SAVED_SEARCHES.find((preset) => preset.key === "nih");
  assert.ok(nih.filters.sponsorName);
  assert.ok(!nih.filters.sponsorCode);
});

test("the NSF saved search uses the real, single, live-verified sponsor_code", () => {
  const nsf = SAVED_SEARCHES.find((preset) => preset.key === "nsf");
  assert.equal(nsf.filters.sponsorCode, "301573");
});
