import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  EVIDENCE_TYPE_LABELS,
  EVIDENCE_TYPES,
  canSubmitEvidenceSearch,
  evidenceScorePercentLabel,
  evidenceSearchErrorMessage,
  evidenceTypeLabel,
  resultsBelongToAward,
  toggleEvidenceType,
} from "./awardEvidenceSearchPresentation.mjs";

// --- Pure logic ---

test("only enables submission for a valid, non-pending query", () => {
  assert.equal(canSubmitEvidenceSearch("", false), false);
  assert.equal(canSubmitEvidenceSearch("  ", false), false);
  assert.equal(canSubmitEvidenceSearch("x".repeat(501), false), false);
  assert.equal(
    canSubmitEvidenceSearch(
      "Which proposal is connected to this Award?",
      true,
    ),
    false,
  );
  assert.equal(
    canSubmitEvidenceSearch(
      "Which proposal is connected to this Award?",
      false,
    ),
    true,
  );
});

test("toggles an evidence type filter on and off", () => {
  const withPerson = toggleEvidenceType([], "AWARD_PERSON");
  assert.deepEqual(withPerson, ["AWARD_PERSON"]);

  const withBoth = toggleEvidenceType(withPerson, "RELATED_PROPOSAL");
  assert.deepEqual(withBoth, ["AWARD_PERSON", "RELATED_PROPOSAL"]);

  const withoutPerson = toggleEvidenceType(withBoth, "AWARD_PERSON");
  assert.deepEqual(withoutPerson, ["RELATED_PROPOSAL"]);
});

test("does not offer attachments or summary as an evidence-search filter", () => {
  assert.equal(EVIDENCE_TYPES.includes("AWARD_ATTACHMENT"), false);
  assert.equal(EVIDENCE_TYPES.includes("AWARD_SUMMARY"), false);
  assert.equal("AWARD_ATTACHMENT" in EVIDENCE_TYPE_LABELS, false);
  assert.equal("AWARD_SUMMARY" in EVIDENCE_TYPE_LABELS, false);
});

test("offers exactly the eight approved evidence types with user-friendly labels", () => {
  assert.deepEqual(EVIDENCE_TYPES, [
    "AWARD_VERSION",
    "AWARD_PERSON",
    "AWARD_AMOUNT",
    "AWARD_TERM",
    "AWARD_COMMENT",
    "RELATED_PROPOSAL",
    "RELATED_NEGOTIATION",
    "RELATED_SUBAWARD",
  ]);
  assert.equal(evidenceTypeLabel("AWARD_PERSON"), "Investigators and People");
  assert.equal(
    evidenceTypeLabel("RELATED_PROPOSAL"),
    "Related Proposals",
  );
});

test("falls back to the raw document type for an unknown type", () => {
  assert.equal(evidenceTypeLabel("SOMETHING_NEW"), "SOMETHING_NEW");
});

test("formats a similarity score as a rounded, clamped percentage", () => {
  assert.equal(evidenceScorePercentLabel(0.91), "91% match");
  assert.equal(evidenceScorePercentLabel(1.5), "100% match");
  assert.equal(evidenceScorePercentLabel(-0.2), "0% match");
});

test("maps HTTP status codes to distinct, actionable messages", () => {
  assert.match(evidenceSearchErrorMessage(401), /session has expired/i);
  assert.match(evidenceSearchErrorMessage(404), /could not be found/i);
  assert.match(evidenceSearchErrorMessage(400), /valid question/i);
  assert.match(evidenceSearchErrorMessage(503), /temporarily unavailable/i);
  assert.match(evidenceSearchErrorMessage(undefined), /could not be reached/i);
  // Provider-unavailable and generic-network-failure messages must be
  // distinguishable from each other.
  assert.notEqual(
    evidenceSearchErrorMessage(503),
    evidenceSearchErrorMessage(undefined),
  );
});

test("proves a result set never mixes Awards", () => {
  const sameAward = [
    { awardNumber: "204713-00001" },
    { awardNumber: "204713-00001" },
  ];
  assert.equal(resultsBelongToAward(sameAward, "204713-00001"), true);

  const mixedAward = [
    { awardNumber: "204713-00001" },
    { awardNumber: "104949-00002" },
  ];
  assert.equal(resultsBelongToAward(mixedAward, "204713-00001"), false);

  assert.equal(resultsBelongToAward([], "204713-00001"), true);
});

// --- Component structure (this repo has no rendered-component test
// harness - mirrors awardQuestionPresentation.test.mjs's own technique
// of asserting against the real component source) ---

function componentSource() {
  return readFileSync(
    new URL("../../components/award/AwardEvidenceSearchSection.tsx", import.meta.url),
    "utf8",
  );
}

function dashboardSource() {
  return readFileSync(
    new URL("../../pages/award/AwardDashboardPage.tsx", import.meta.url),
    "utf8",
  );
}

test("the Award page wires in an Evidence Search tab", () => {
  const dashboard = dashboardSource();
  assert.match(dashboard, /AwardEvidenceSearchSection/);
  assert.match(dashboard, /key:\s*"evidenceSearch"/);
  assert.match(dashboard, /label:\s*"Evidence Search"/);
  assert.match(
    dashboard,
    /activeSection === "evidenceSearch"/,
  );
});

test("renders result cards on a successful search", () => {
  const component = componentSource();
  assert.match(component, /result\.results\.map/);
  assert.match(component, /EvidenceResultCard/);
});

test("shows a loading indicator while the search is pending", () => {
  const component = componentSource();
  assert.match(component, /searchMutation\.isPending/);
  assert.match(component, /CircularProgress/);
  assert.match(component, /Searching archived evidence/);
});

test("shows a distinct insufficient-evidence message via EmptyState", () => {
  const component = componentSource();
  assert.match(component, /result\.insufficientEvidence/);
  assert.match(component, /EmptyState/);
  assert.match(component, /No indexed evidence matched/);
});

test("shows a provider-unavailable error and a retry action", () => {
  const component = componentSource();
  assert.match(component, /searchMutation\.isError/);
  assert.match(component, /evidenceSearchErrorMessage/);
  assert.match(component, />\s*Retry\s*</);
  assert.match(component, /onClick={retry}/);
});

test("each result links back to its Award section for citation navigation", () => {
  const component = componentSource();
  assert.match(component, /onNavigate\(result\.targetSection\)/);
  assert.match(component, /View in Award record/);
});

test("never offers attachments as a filter or reads attachment content", () => {
  const component = componentSource();
  assert.doesNotMatch(component, /AWARD_ATTACHMENT/);
  assert.doesNotMatch(component, /attachment_object/i);
  assert.match(component, /Attachment contents are not searched here/);
});

test("does not show a generated narrative answer, only cited evidence", () => {
  const component = componentSource();
  // AwardAiQuestionPanel's generated-answer field is result.answer -
  // this component must never render that field, only the direct,
  // per-row excerpt/citation fields.
  assert.doesNotMatch(component, /result\.answer\b/);
  assert.match(component, /not a generated answer/);
});
