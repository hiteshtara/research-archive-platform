/**
 * Guards for the legacy /proposals/:proposalNumber route, which resolves a
 * Proposal number through GET /api/proposals/{proposalNumber} and then
 * redirects to the Proposal dashboard.
 *
 * ProposalArchiveController also maps literal paths under /api/proposals
 * (/families, /search). Spring matches a literal path before the
 * {proposalNumber} template, so a "proposal number" equal to one of them
 * never reaches the workspace endpoint: /api/proposals/search returns a page
 * of search results and /api/proposals/families a list. Rendering either as
 * a workspace crashed the page (QA TC-033).
 */

/** Literal sibling routes of GET /api/proposals/{proposalNumber}. */
export const RESERVED_PROPOSAL_API_SEGMENTS = Object.freeze(["families", "search"]);

/**
 * True when the identifier would be routed to a sibling endpoint instead of
 * the workspace endpoint, so it can never name a Proposal workspace.
 * Spring's path matching is case-sensitive, so only the exact segment
 * collides.
 */
export function isReservedProposalIdentifier(identifier) {
  return typeof identifier === "string"
    && RESERVED_PROPOSAL_API_SEGMENTS.includes(identifier.trim());
}

/**
 * True only for the shape the redirect needs: a Proposal number and a
 * current version with a numeric proposalId. A search page, a family list
 * or an error body all fail this check.
 */
export function isProposalWorkspacePayload(payload) {
  return payload !== null
    && typeof payload === "object"
    && !Array.isArray(payload)
    && typeof payload.proposalNumber === "string"
    && payload.current !== null
    && typeof payload.current === "object"
    && Number.isInteger(payload.current.proposalId);
}

/** User-facing message for a workspace that could not be opened. */
export function proposalWorkspaceErrorMessage(kind, identifier) {
  if (kind === "not-found") {
    return `No Proposal with number "${identifier}" was found.`;
  }
  return "Unable to load Proposal workspace.";
}
