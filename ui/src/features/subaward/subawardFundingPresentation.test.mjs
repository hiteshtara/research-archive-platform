import assert from "node:assert/strict";
import test from "node:test";

import {
  resolveAssociatedAwardCardState,
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

test("a loaded (archived) current Award version renders a clickable state", () => {
  const funding = fundingRow({
    awardNumber: "202505-00002",
    awardTitle: "Neuroimaging Genetics of PTSD",
    awardStatus: "03. Pending",
    navigableCurrentAwardId: 2036323,
    archived: true,
  });

  const state = resolveAssociatedAwardCardState(funding);

  assert.deepEqual(state, {
    kind: "LOADED",
    awardNumber: "202505-00002",
    awardTitle: "Neuroimaging Genetics of PTSD",
    awardStatus: "03. Pending",
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
