// User-friendly evidence-type filter labels - deliberately excludes
// AWARD_SUMMARY (owned by the full-dataset semantic search feature, not
// evidence search) and AWARD_ATTACHMENT (attachment content is out of
// scope for evidence RAG - see the required attachment statement in
// docs/demo/AWARD_RAG_CLIENT_PRESENTATION.md). Order matches
// docs/architecture/AWARD_EVIDENCE_RETRIEVAL_PHASE3_DESIGN.md's own
// approved-type list.
export const EVIDENCE_TYPE_LABELS = Object.freeze({
  AWARD_VERSION: "Award Versions",
  AWARD_PERSON: "Investigators and People",
  AWARD_AMOUNT: "Funding Amounts",
  AWARD_TERM: "Terms and Reports",
  AWARD_COMMENT: "Award Comments",
  RELATED_PROPOSAL: "Related Proposals",
  RELATED_NEGOTIATION: "Related Negotiations",
  RELATED_SUBAWARD: "Related Subawards",
});

export const EVIDENCE_TYPES = Object.freeze(
  Object.keys(EVIDENCE_TYPE_LABELS),
);

export function canSubmitEvidenceSearch(query, pending) {
  const length = query.trim().length;
  return !pending && length > 0 && length <= 500;
}

export function toggleEvidenceType(selectedTypes, type) {
  return selectedTypes.includes(type)
    ? selectedTypes.filter((value) => value !== type)
    : [...selectedTypes, type];
}

export function evidenceTypeLabel(documentType) {
  return EVIDENCE_TYPE_LABELS[documentType] ?? documentType;
}

export function evidenceScorePercentLabel(score) {
  const clamped = Math.min(1, Math.max(0, score));
  return `${Math.round(clamped * 100)}% match`;
}

export function evidenceSearchErrorMessage(status) {
  if (status === 401) {
    return "Your session has expired. Sign in again to search evidence.";
  }
  if (status === 404) {
    return "This Award could not be found.";
  }
  if (status === 400) {
    return "Enter a valid question of no more than 500 characters.";
  }
  if (status === 503) {
    return "Evidence search is temporarily unavailable. Try again shortly.";
  }
  return "Evidence search could not be reached. Check your connection and try again.";
}

// Defensive, provable guarantee that a result set never mixes Awards -
// every result in a real response already comes from one
// awardNumber-scoped API call, but this makes the invariant directly
// testable rather than only true by construction.
export function resultsBelongToAward(results, awardNumber) {
  return results.every((result) => result.awardNumber === awardNumber);
}
