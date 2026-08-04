import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  budgetScopeNote,
  hasAnyBudgetVersions,
  owningSequenceLabel,
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
