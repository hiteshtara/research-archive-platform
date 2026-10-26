import assert from "node:assert/strict";
import test from "node:test";

import {
  resolveAssociatedAwardCardState,
  resolveAssociatedAwardsSectionLabel,
  resolveFundingAssociationCards,
} from "./subawardFundingPresentation.mjs";

function fundingRow(overrides = {}) {
  return {
    subawardFundingSourceId: 501,
    subawardId: 17206,
    subawardCode: "1363",
    sequenceNumber: 8,
    exactLinkedAwardId: 2036323,
    awardNumber: "202505-00002",
    awardTitle: null,
    awardStatus: null,
    awardSponsor: null,
    awardAmount: null,
    navigableCurrentAwardId: null,
    archived: false,
    sourceUpdateTimestamp: null,
    sourceUpdateUser: null,
    sourceVersionNumber: null,
    sourceObjectId: null,
    ...overrides,
  };
}

test("resolveAssociatedAwardCardState preserves the exact linked Award ID's presence without exposing it in the card state", () => {
  const funding = fundingRow({ exactLinkedAwardId: 2036323 });

  const state = resolveAssociatedAwardCardState(funding);

  // exactLinkedAwardId itself is deliberately not part of any card
  // state - it stays in the API response for audit only.
  assert.equal("exactLinkedAwardId" in state, false);
  assert.notEqual(state.kind, "NONE");
});

test("a stale exactLinkedAwardId (pointing at a non-current version) still resolves to the current Award version via navigableCurrentAwardId", () => {
  // funding.award_id (exactLinkedAwardId) is a stale/archived Award
  // version id, e.g. 834149 (sequence 1) - the repository resolves
  // navigableCurrentAwardId independently, by award_number against
  // is_primary_current, not from exactLinkedAwardId.
  const funding = fundingRow({
    exactLinkedAwardId: 834149,
    awardNumber: "202505-00002",
    awardTitle: "Neuroimaging Genetics of PTSD",
    awardStatus: "03. Pending",
    navigableCurrentAwardId: 2036323,
    archived: true,
  });

  const state = resolveAssociatedAwardCardState(funding);

  assert.equal(state.kind, "LOADED");
  assert.equal(state.navigableCurrentAwardId, 2036323);
  assert.equal(state.awardNumber, "202505-00002");
});

test("a loaded (archived) current Award version renders a clickable state including sponsor and amount", () => {
  const funding = fundingRow({
    awardNumber: "202505-00002",
    awardTitle: "Neuroimaging Genetics of PTSD",
    awardStatus: "03. Pending",
    awardSponsor: "Department of Energy",
    awardAmount: 1934613.43,
    navigableCurrentAwardId: 2036323,
    archived: true,
  });

  const state = resolveAssociatedAwardCardState(funding);

  assert.deepEqual(state, {
    kind: "LOADED",
    awardNumber: "202505-00002",
    awardTitle: "Neuroimaging Genetics of PTSD",
    awardStatus: "03. Pending",
    awardSponsor: "Department of Energy",
    awardAmount: 1934613.43,
    navigableCurrentAwardId: 2036323,
  });
});

test("an unloaded (not currently archived) Award remains visible but non-clickable", () => {
  const funding = fundingRow({
    awardNumber: "203161-00002",
    archived: false,
    navigableCurrentAwardId: null,
  });

  const state = resolveAssociatedAwardCardState(funding);

  assert.equal(state.kind, "NOT_ARCHIVED");
  assert.equal(state.awardNumber, "203161-00002");
  assert.equal("navigableCurrentAwardId" in state, false);
});

test("a funding row with no Award link at all returns the NONE state, never a fabricated card", () => {
  const funding = fundingRow({
    exactLinkedAwardId: null,
    awardNumber: null,
    archived: false,
  });

  const state = resolveAssociatedAwardCardState(funding);

  assert.deepEqual(state, { kind: "NONE" });
});

test("resolveFundingAssociationCards preserves every real funding-source row, in order, when a Subaward has multiple", () => {
  const rows = [
    fundingRow({
      subawardFundingSourceId: 501,
      awardNumber: "202505-00002",
      archived: true,
      navigableCurrentAwardId: 2036323,
      awardTitle: "Neuroimaging Genetics of PTSD",
      awardStatus: "03. Pending",
    }),
    fundingRow({
      subawardFundingSourceId: 777,
      exactLinkedAwardId: 9999999,
      awardNumber: "203161-00002",
      archived: false,
      navigableCurrentAwardId: null,
    }),
  ];

  const cards = resolveFundingAssociationCards(rows);

  assert.equal(cards.length, 2);
  assert.equal(cards[0].subawardFundingSourceId, 501);
  assert.equal(cards[0].card.kind, "LOADED");
  assert.equal(cards[1].subawardFundingSourceId, 777);
  assert.equal(cards[1].card.kind, "NOT_ARCHIVED");
});

test("resolveFundingAssociationCards returns an empty list (an honest empty state) when a Subaward has no funding rows at all", () => {
  const cards = resolveFundingAssociationCards([]);

  assert.deepEqual(cards, []);
});

test("resolveAssociatedAwardsSectionLabel is singular for exactly one real Award link", () => {
  assert.equal(
    resolveAssociatedAwardsSectionLabel([fundingRow()]),
    "Associated Award",
  );
});

test("resolveAssociatedAwardsSectionLabel is plural with a real count for multiple concurrent Award relationships", () => {
  // Real live data: subaward_id 33247 has 3 distinct concurrent Award
  // relationships (proven via SubAwardDocumentRule's duplicate-
  // award-number business rule + confirmed in the archived data).
  const rows = [
    fundingRow({ subawardFundingSourceId: 1, exactLinkedAwardId: 111 }),
    fundingRow({ subawardFundingSourceId: 2, exactLinkedAwardId: 222 }),
    fundingRow({ subawardFundingSourceId: 3, exactLinkedAwardId: 333 }),
  ];

  assert.equal(
    resolveAssociatedAwardsSectionLabel(rows),
    "Associated Awards (3)",
  );
});

test("resolveAssociatedAwardsSectionLabel counts only rows with a real Award link, and is plural for zero", () => {
  assert.equal(
    resolveAssociatedAwardsSectionLabel([
      fundingRow({ exactLinkedAwardId: null, awardNumber: null }),
    ]),
    "Associated Awards (0)",
  );
});

test("a filtered funding list never claims a complete count of Associated Awards", () => {
  // The ANY-match authorization rule's disclosure trap. A Subaward funded
  // by three Awards, only one of which the caller may see, must NOT render
  // "Associated Award" in the singular - that positively asserts the record
  // has exactly one funding Award, which is false. The heading reports what
  // is shown and says the rest are restricted.
  const visibleOnly = [fundingRow({ subawardFundingSourceId: 1, exactLinkedAwardId: 111 })];

  assert.equal(
    resolveAssociatedAwardsSectionLabel(visibleOnly, true),
    "Associated Awards (1 shown; others restricted)",
  );
});

test("the restricted heading never discloses how many funding Awards are hidden", () => {
  // "some are not shown" is the smallest true statement that stops the
  // count being a lie. The number of withheld Awards is itself information
  // about records the caller may not see.
  const label = resolveAssociatedAwardsSectionLabel(
    [fundingRow({ subawardFundingSourceId: 1, exactLinkedAwardId: 111 })],
    true,
  );

  for (const hiddenCount of ["2", "3", "of 3", "of 4"]) {
    assert.equal(label.includes(hiddenCount), false, `leaked "${hiddenCount}"`);
  }
});

test("an unfiltered list keeps the exact pre-authorization heading", () => {
  // Enforcement off, and any scoped caller who can see every funder, must
  // get byte-identical behaviour to before record authorization existed.
  const rows = [
    fundingRow({ subawardFundingSourceId: 1, exactLinkedAwardId: 111 }),
    fundingRow({ subawardFundingSourceId: 2, exactLinkedAwardId: 222 }),
  ];

  assert.equal(resolveAssociatedAwardsSectionLabel(rows, false), "Associated Awards (2)");
  assert.equal(resolveAssociatedAwardsSectionLabel(rows), "Associated Awards (2)");
  assert.equal(
    resolveAssociatedAwardsSectionLabel([
      fundingRow({ subawardFundingSourceId: 1, exactLinkedAwardId: 111 }),
    ]),
    "Associated Award",
  );
});
