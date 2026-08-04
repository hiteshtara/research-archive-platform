import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  budgetScopeNote,
  hasAnyBudgetVersions,
  owningSequenceLabel,
  personnelEmptyStateMessage,
  selectedBudgetLabel,
} from "./budgetPresentation.mjs";
import { formatCurrencyAmount } from "./awardSectionsPresentation.mjs";

test("the shared currency formatter renders exact cents for real Budget totals (17551.63, not $17,552)", () => {
  assert.equal(formatCurrencyAmount(17551.63), "$17,551.63");
  assert.equal(formatCurrencyAmount(0), "$0.00");
  assert.equal(formatCurrencyAmount(-1250.5), "-$1,250.50");
  assert.equal(formatCurrencyAmount(null), "—");
  assert.equal(formatCurrencyAmount(1000000.01), "$1,000,000.01");
});

test("AwardBudgetSection imports the shared currency formatter rather than redefining its own", () => {
  const sourcePath = fileURLToPath(
    new URL(
      "../../components/award/AwardBudgetSection.tsx",
      import.meta.url,
    ),
  );
  const source = readFileSync(sourcePath, "utf8");

  assert.match(
    source,
    /from\s+"..\/..\/features\/award\/awardSectionsPresentation\.mjs"/,
    "AwardBudgetSection must import its currency formatter from the shared module",
  );
  assert.equal(
    /maximumFractionDigits/.test(source),
    false,
    "AwardBudgetSection must not redefine its own currency formatting",
  );
});

test("budgetScopeNote states the family-wide, sequence-bounded scope explicitly", () => {
  const note = budgetScopeNote({
    awardNumber: "103692-00002",
    viewedSequenceNumber: 46,
  });

  assert.equal(
    note,
    "Budget versions include Award 103692-00002 through sequence 46.",
  );
});

test("budgetScopeNote returns null without a summary", () => {
  assert.equal(budgetScopeNote(null), null);
  assert.equal(budgetScopeNote(undefined), null);
});

test("selectedBudgetLabel reports the selected version, not \"current\"", () => {
  const label = selectedBudgetLabel({
    selectedBudgetId: 213641,
    selectedBudgetVersionNumber: 37,
  });

  assert.equal(label, "Budget Version 37");
});

test("selectedBudgetLabel reports no Budget recorded when nothing is selected", () => {
  assert.equal(
    selectedBudgetLabel({ selectedBudgetId: null }),
    "No Budget recorded for this Award.",
  );
  assert.equal(
    selectedBudgetLabel(null),
    "No Budget recorded for this Award.",
  );
});

test("owningSequenceLabel names the specific Award sequence a Budget version belongs to", () => {
  assert.equal(
    owningSequenceLabel({ owningAwardSequenceNumber: 14 }),
    "Award sequence 14",
  );
  assert.equal(owningSequenceLabel(null), "—");
});

test("hasAnyBudgetVersions treats a non-empty array as having versions", () => {
  assert.equal(hasAnyBudgetVersions([]), false);
  assert.equal(hasAnyBudgetVersions([{}]), true);
  assert.equal(hasAnyBudgetVersions(null), false);
  assert.equal(hasAnyBudgetVersions(undefined), false);
});

test("personnelEmptyStateMessage explains the empty state rather than implying missing data", () => {
  const message = personnelEmptyStateMessage();
  assert.match(message, /bulk line item/);
  assert.doesNotMatch(message, /^No personnel recorded/);
});

// Budget semantic fix (docs/kuali-business-rules/Budget.md): Award
// Total Cost Limit and Budget Change Total Cost Limit are frozen,
// per-version snapshots distinct from a version's own requested amount
// - real fixture, live-verified against Kuali and the archive: Award
// 105698-00002, budget version 5 (budget_id 176666).

test("the shared currency formatter preserves cents for the real 105698-00002 limit snapshot fixture", () => {
  // Kuali's own Budget Overview screen for this fixture shows all three
  // numbers side by side under different labels - never one collapsed
  // into another.
  assert.equal(formatCurrencyAmount(0.01), "$0.01"); // this version's own Total Cost
  assert.equal(formatCurrencyAmount(699246.57), "$699,246.57"); // Budget Total Cost Limit
  assert.equal(formatCurrencyAmount(0.01), "$0.01"); // Budget Change Total Cost Limit
});

test("a converted Budget version's null awardBudgetTotalCostLimit renders as —, never $0.00", () => {
  // Real fixture, live-verified: this award's own version 4 ("Converted
  // Budget Document") never had obligated_total (awardBudgetTotalCostLimit)
  // populated - archived as null, distinct from a real $0.00 value.
  // budgetChangeTotalCostLimit (total_cost_limit) is a SEPARATE column
  // that WAS populated on this same version - the two must never be
  // assumed null together.
  assert.equal(formatCurrencyAmount(null), "—");
  assert.notEqual(formatCurrencyAmount(null), "$0.00");
  // That version's own Total Cost and budgetChangeTotalCostLimit are
  // both real, non-null persisted values (equal to each other here)
  // and must render unchanged alongside the null awardBudgetTotalCostLimit.
  assert.equal(formatCurrencyAmount(-27627.44), "-$27,627.44");
});

test("AwardBudgetSection renders both limit snapshots in the Summary panel and the Versions table, using the shared formatter", () => {
  const sourcePath = fileURLToPath(
    new URL(
      "../../components/award/AwardBudgetSection.tsx",
      import.meta.url,
    ),
  );
  const source = readFileSync(sourcePath, "utf8");

  assert.match(
    source,
    /formatCurrencyAmount\(summary\.awardBudgetTotalCostLimit\)/,
    "Summary panel must render awardBudgetTotalCostLimit through the shared formatter",
  );
  assert.match(
    source,
    /formatCurrencyAmount\(summary\.budgetChangeTotalCostLimit\)/,
    "Summary panel must render budgetChangeTotalCostLimit through the shared formatter",
  );
  assert.match(
    source,
    /formatCurrencyAmount\(version\.awardBudgetTotalCostLimit\)/,
    "Versions table must render awardBudgetTotalCostLimit through the shared formatter",
  );
  assert.match(
    source,
    /formatCurrencyAmount\(version\.budgetChangeTotalCostLimit\)/,
    "Versions table must render budgetChangeTotalCostLimit through the shared formatter",
  );
  // Both concepts must stay visually/textually distinct from the
  // version's own requested amount - never relabeled as "Total Cost".
  assert.match(source, /Version Total Cost/);
  assert.match(source, /Budget Total Cost Limit/);
  assert.match(source, /Budget Change Total Cost Limit/);
});
