import assert from "node:assert/strict";
import test from "node:test";

import {
  describeHistoryEntry,
  describeLastAction,
  fandaDistributionPeriodLabel,
} from "./timeAndMoneyPresentation.mjs";

test("describeHistoryEntry: null/undefined never throws", () => {
  assert.deepEqual(describeHistoryEntry(null), {
    timeAndMoneyCreated: false,
    versionsAgree: true,
    versionNote: null,
  });
  assert.deepEqual(describeHistoryEntry(undefined), {
    timeAndMoneyCreated: false,
    versionsAgree: true,
    versionNote: null,
  });
});

test("describeHistoryEntry: the Award's original entry (not Time and Money-created)", () => {
  const described = describeHistoryEntry({
    timeAndMoneyCreated: false,
    sequenceNumber: 1,
    originatingAwardVersion: null,
  });

  assert.equal(described.timeAndMoneyCreated, false);
  assert.equal(described.versionsAgree, true);
  assert.equal(described.versionNote, null);
});

test("describeHistoryEntry: Time and Money-created row where versions agree", () => {
  const described = describeHistoryEntry({
    timeAndMoneyCreated: true,
    sequenceNumber: 7,
    originatingAwardVersion: 7,
  });

  assert.equal(described.timeAndMoneyCreated, true);
  assert.equal(described.versionsAgree, true);
  assert.equal(described.versionNote, null);
});

test("describeHistoryEntry: Time and Money-created row where versions differ - never derives one from the other", () => {
  const described = describeHistoryEntry({
    timeAndMoneyCreated: true,
    sequenceNumber: 7,
    originatingAwardVersion: 6,
  });

  assert.equal(described.timeAndMoneyCreated, true);
  assert.equal(described.versionsAgree, false);
  assert.equal(
    described.versionNote,
    "Recorded against version 6, viewing version 7",
  );
});

test("describeLastAction: no Time and Money action yet", () => {
  assert.equal(
    describeLastAction({ lastFamilyTimeAndMoneyDocumentNumber: null }),
    "No Time and Money action recorded for this Award.",
  );
  assert.equal(
    describeLastAction(null),
    "No Time and Money action recorded for this Award.",
  );
});

test("describeLastAction: composes document number, type, and notice date", () => {
  assert.equal(
    describeLastAction({
      lastFamilyTimeAndMoneyDocumentNumber: "281518",
      lastFamilyTransactionTypeDescription: "Supplement",
      lastFamilyNoticeDate: "2021-01-01",
    }),
    "Document 281518 · Supplement · 2021-01-01",
  );
});

test("describeLastAction: omits missing optional parts", () => {
  assert.equal(
    describeLastAction({
      lastFamilyTimeAndMoneyDocumentNumber: "281518",
      lastFamilyTransactionTypeDescription: null,
      lastFamilyNoticeDate: null,
    }),
    "Document 281518",
  );
});

test("fandaDistributionPeriodLabel: never called budgetPeriod, always labeled", () => {
  assert.equal(
    fandaDistributionPeriodLabel("07/01/2020 - 06/30/2021"),
    "07/01/2020 - 06/30/2021 (F&A distribution period)",
  );
  assert.equal(fandaDistributionPeriodLabel(null), "—");
  assert.equal(fandaDistributionPeriodLabel(undefined), "—");
  assert.equal(fandaDistributionPeriodLabel(""), "—");
});
