import assert from "node:assert/strict";
import test from "node:test";

import {
  RESERVED_PROPOSAL_API_SEGMENTS,
  isProposalWorkspacePayload,
  isReservedProposalIdentifier,
  proposalWorkspaceErrorMessage,
} from "./proposalWorkspacePresentation.mjs";

// Shapes the API really returns (see ProposalArchiveController).
const validWorkspace = {
  proposalNumber: "01091939",
  current: { proposalId: 81000, proposalNumber: "01091939", versionNumber: 3 },
};
// GET /api/proposals/search: the normal paginated Proposals search.
const searchPage = {
  content: [{ proposalNumber: "01091939", currentProposalId: 81000 }],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1,
};
// GET /api/proposals/families
const familyList = [{ proposalNumber: "01091939", currentProposalId: 81000 }];

test("the literal 'search' can never name a Proposal workspace (TC-033)", () => {
  assert.equal(isReservedProposalIdentifier("search"), true);
  assert.equal(isReservedProposalIdentifier(" search "), true);
});

test("'families' collides the same way and is reserved too", () => {
  assert.equal(isReservedProposalIdentifier("families"), true);
});

test("real Proposal numbers and other invalid identifiers are not reserved", () => {
  for (const identifier of ["01091939", "P00012345", "NOT-A-REAL-PROPOSAL", "Search", "searches"]) {
    assert.equal(isReservedProposalIdentifier(identifier), false, identifier);
  }
  assert.equal(isReservedProposalIdentifier(undefined), false);
});

test("the reserved list matches the controller's literal routes", () => {
  assert.deepEqual([...RESERVED_PROPOSAL_API_SEGMENTS].sort(), ["families", "search"]);
});

test("a valid workspace payload is accepted", () => {
  assert.equal(isProposalWorkspacePayload(validWorkspace), true);
});

test("a search-results page is rejected as a workspace", () => {
  assert.equal(isProposalWorkspacePayload(searchPage), false);
});

test("a family list, empty or malformed payloads are rejected", () => {
  assert.equal(isProposalWorkspacePayload(familyList), false);
  assert.equal(isProposalWorkspacePayload(null), false);
  assert.equal(isProposalWorkspacePayload({}), false);
  assert.equal(isProposalWorkspacePayload({ proposalNumber: "01091939" }), false);
  assert.equal(isProposalWorkspacePayload({ proposalNumber: "01091939", current: { proposalId: "81000" } }), false);
});

test("messages distinguish a missing Proposal from an unusable response", () => {
  assert.equal(
    proposalWorkspaceErrorMessage("not-found", "search"),
    'No Proposal with number "search" was found.',
  );
  assert.equal(
    proposalWorkspaceErrorMessage("unavailable", "01091939"),
    "Unable to load Proposal workspace.",
  );
});
