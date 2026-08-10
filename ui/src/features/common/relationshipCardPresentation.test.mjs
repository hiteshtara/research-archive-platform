import assert from "node:assert/strict";
import test from "node:test";

import {
  RELATIONSHIP_ACTION_LABEL,
  buildFundedAwardMetaText,
  buildFundingProposalMetaText,
  resolveNegotiationAssociationArchived,
  resolveNegotiationAssociationDisplayKind,
  resolveNegotiationCardTitle,
  resolveRelationshipCardState,
} from "./relationshipCardPresentation.mjs";

// --- resolveRelationshipCardState (loaded / not-currently-archived) -----

test("resolveRelationshipCardState treats a real navigable id as archived", () => {
  assert.equal(resolveRelationshipCardState(4213).archived, true);
});

test("resolveRelationshipCardState treats a null navigable id as not archived", () => {
  assert.equal(resolveRelationshipCardState(null).archived, false);
});

test("resolveRelationshipCardState treats an undefined navigable id as not archived", () => {
  assert.equal(resolveRelationshipCardState(undefined).archived, false);
});

test("resolveRelationshipCardState treats id 0 as archived - a falsy-but-real id must not be mistaken for missing", () => {
  assert.equal(resolveRelationshipCardState(0).archived, true);
});

// --- resolveNegotiationAssociationArchived (Negotiation's polymorphic ---
// --- association: needs clickable AND a real navigableId together) -----

test("resolveNegotiationAssociationArchived is archived when clickable and navigableId are both present", () => {
  assert.equal(
    resolveNegotiationAssociationArchived({
      clickable: true,
      navigableId: 991,
    }),
    true,
  );
});

test("resolveNegotiationAssociationArchived is not archived when clickable is true but navigableId is null (valid relationship, target not currently archived)", () => {
  assert.equal(
    resolveNegotiationAssociationArchived({
      clickable: true,
      navigableId: null,
    }),
    false,
  );
});

test("resolveNegotiationAssociationArchived is not archived when navigableId is present but clickable is false - no hidden valid relationship should ever be treated as navigable", () => {
  assert.equal(
    resolveNegotiationAssociationArchived({
      clickable: false,
      navigableId: 991,
    }),
    false,
  );
});

test("resolveNegotiationAssociationArchived is not archived for a null/undefined association", () => {
  assert.equal(resolveNegotiationAssociationArchived(null), false);
  assert.equal(resolveNegotiationAssociationArchived(undefined), false);
});

// --- resolveNegotiationAssociationDisplayKind (Sprint 4: which of the ---
// --- three Associated Record tab presentations - empty/empty/card) ------

test("resolveNegotiationAssociationDisplayKind is 'none' when the Negotiation has no association at all - a real, valid state, not an error", () => {
  assert.equal(
    resolveNegotiationAssociationDisplayKind({ kind: "NONE" }),
    "none",
  );
});

test("resolveNegotiationAssociationDisplayKind is 'unsupported' for an association type the archive doesn't recognize yet", () => {
  assert.equal(
    resolveNegotiationAssociationDisplayKind({ kind: "UNSUPPORTED" }),
    "unsupported",
  );
});

test("resolveNegotiationAssociationDisplayKind is 'card' for a real AWARD/PROPOSAL/SUBAWARD association - the loaded, successful-render case", () => {
  assert.equal(
    resolveNegotiationAssociationDisplayKind({ kind: "AWARD" }),
    "card",
  );
  assert.equal(
    resolveNegotiationAssociationDisplayKind({ kind: "PROPOSAL" }),
    "card",
  );
  assert.equal(
    resolveNegotiationAssociationDisplayKind({ kind: "SUBAWARD" }),
    "card",
  );
});

test("resolveNegotiationAssociationDisplayKind is 'card' (never a false empty) for a null/undefined association - the query-loading window is handled separately by isLoading, not by this resolver", () => {
  assert.equal(resolveNegotiationAssociationDisplayKind(null), "card");
  assert.equal(resolveNegotiationAssociationDisplayKind(undefined), "card");
});

// --- resolveNegotiationCardTitle (business identifier, not row id) ------

test("resolveNegotiationCardTitle prefers the real documentNumber business identifier", () => {
  assert.equal(
    resolveNegotiationCardTitle({
      negotiationId: 55,
      documentNumber: "NEG-2024-0012",
    }),
    "Negotiation NEG-2024-0012",
  );
});

test("resolveNegotiationCardTitle falls back to the internal negotiationId only when documentNumber was never archived", () => {
  assert.equal(
    resolveNegotiationCardTitle({ negotiationId: 55, documentNumber: null }),
    "Negotiation 55",
  );
});

// --- RELATIONSHIP_ACTION_LABEL (correct action label by module) ---------

test("RELATIONSHIP_ACTION_LABEL provides one label per domain, matching each module's own record type", () => {
  assert.equal(RELATIONSHIP_ACTION_LABEL.award, "Open Award");
  assert.equal(RELATIONSHIP_ACTION_LABEL.proposal, "Open Proposal");
  assert.equal(RELATIONSHIP_ACTION_LABEL.subaward, "Open Subaward");
  assert.equal(RELATIONSHIP_ACTION_LABEL.negotiation, "Open Negotiation");
});

// --- buildFundingProposalMetaText (Award -> Funding Proposals caption) --

test("buildFundingProposalMetaText joins PI, sponsor, and requested cost when all present", () => {
  assert.equal(
    buildFundingProposalMetaText({
      principalInvestigatorName: "Jennifer Smacher",
      sponsorName: "NIH",
      requestedTotalCost: 250000,
    }),
    "Jennifer Smacher · NIH · Requested $250,000.00",
  );
});

test("buildFundingProposalMetaText falls back to an em dash for a missing PI name but omits a missing sponsor entirely", () => {
  assert.equal(
    buildFundingProposalMetaText({
      principalInvestigatorName: null,
      sponsorName: null,
      requestedTotalCost: null,
    }),
    "—",
  );
});

// --- buildFundedAwardMetaText (Proposal -> Funded Awards caption) -------

test("buildFundedAwardMetaText omits the version-mismatch note when linked and current versions agree", () => {
  assert.equal(
    buildFundedAwardMetaText({
      currentAwardVersion: 3,
      linkedAwardVersion: 3,
      proposalVersion: 2,
    }),
    "Current version 3 · Proposal version 2",
  );
});

test("buildFundedAwardMetaText adds the version-mismatch note when the linked version differs from current - preserves audit-relevant history", () => {
  assert.equal(
    buildFundedAwardMetaText({
      currentAwardVersion: 5,
      linkedAwardVersion: 2,
      proposalVersion: 2,
    }),
    "Current version 5 (linked at version 2) · Proposal version 2",
  );
});
