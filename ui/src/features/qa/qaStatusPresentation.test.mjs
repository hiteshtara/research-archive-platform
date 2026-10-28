import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  casesClaimingAnUnverifiedPass,
  casesWithUnevidencedVerification,
  caseAreas,
  countByStatus,
  deployedEvidenceCount,
  filterCases,
  isKnownStatus,
  openCaseCount,
  requirementsWithConflicts,
  resultsLabel,
  scopesPresent,
  securitySummary,
  SECURITY_STAGES,
  enforcementIsOffEverywhere,
  isKnownProgressStage,
  isPlaceholderEvidence,
  isRealIsoDate,
  progressStage,
  PROGRESS_STAGES,
  statusMeta,
  STATUS_META,
  trackedCases,
  verificationShortfalls,
} from "./qaStatusPresentation.mjs";

function readJson(relativePath) {
  return JSON.parse(
    readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), "utf8"),
  );
}

function readSource(relativePath) {
  return readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), "utf8");
}

const snapshot = readJson("./qaSnapshot.json");
const security = readJson("./securityRequirements.json");
const cases = snapshot.cases;
const requirements = security.requirements;

// --- The snapshot itself ---------------------------------------------------

test("John's matrix still contributes 47 of its 48 cases, and TC-022 is not one of them", () => {
  // TC-022 is excluded by owner direction. Its historical finding stays
  // in the source workbook; it must never appear here, and must never be
  // counted as passed.
  //
  // The snapshot now also carries cases reported outside that workbook,
  // which carry reportedBy. The matrix total is counted on its own so
  // that adding a reported case can never quietly look like a matrix
  // case, and so originalCaseCount keeps describing the workbook rather
  // than the page.
  const fromMatrix = cases.filter((item) => !item.reportedBy);

  assert.equal(fromMatrix.length, 47);
  assert.equal(snapshot.originalCaseCount, 48);
  assert.equal(snapshot.excludedCaseCount, 1);
  assert.equal(fromMatrix.length + snapshot.excludedCaseCount, snapshot.originalCaseCount);
  assert.ok(!cases.some((item) => item.id === "TC-022"));
});

test("every case reported outside the matrix names who reported it", () => {
  // Provenance has to survive: a reader seeing a case on the board
  // should be able to tell whose finding it was without reading the note.
  for (const item of cases.filter((candidate) => candidate.reportedBy)) {
    assert.ok(item.reportedBy.length > 0, `${item.id} has an empty reportedBy`);
    assert.ok(item.source, `${item.id} has no source`);
  }
});

test("the two labelling cases are clarifications, not incorrect-data claims", () => {
  // Known issue is the BOARD category - the site still showed a
  // confusing label when they were written. Neither claims the stored
  // data is wrong, and neither changed a stored value.
  for (const id of ["TC-051", "TC-052"]) {
    const item = cases.find((candidate) => candidate.id === id);
    assert.match(item.note, /clarification/i);
    assert.match(item.note, /Board category/);
    assert.ok(
      /not a data defect|none were changed|no .* was changed/i.test(item.note),
      `${id} should say plainly that no stored value changed`,
    );
  }
});

test("Lalitha's proposal-history findings are closed on verified evidence", () => {
  // They were Known issues while fixed-in-code and undeployed. They are
  // now passed, and may only say so with a build and a date attached -
  // that is what separates a verified close from an assumed one.
  const reported = ["TC-049", "TC-050", "TC-051", "TC-052"].map((id) => {
    const item = cases.find((candidate) => candidate.id === id);
    assert.ok(item, `expected ${id} in the snapshot`);
    return item;
  });

  for (const item of reported) {
    // Attribution survives closing the case.
    assert.equal(item.reportedBy, "Lalitha");
    assert.equal(item.status, "passed");
    assert.equal(item.progress.stage, "verified");
    assert.equal(item.progress.verifiedOn, "2026-10-06");
    assert.equal(item.progress.verifiedIn, "Development website");
    // The exact build the evidence came from, named so the close can be
    // re-checked rather than taken on trust.
    assert.match(item.progress.deployedBuild, /20261006T182647Z-b10e4e3/);
    assert.match(item.progress.deployedBuild, /rev 80/);
    assert.match(item.progress.deployedBuild, /#123 @ b10e4e3a/);
    // The original observation is kept, not overwritten by the close.
    assert.ok(
      item.history.some((entry) => entry.stage === "knownIssue"),
      `${item.id} lost the observation it was opened on`,
    );
    assert.ok(
      item.history.some((entry) => entry.stage === "fixedInCode"),
      `${item.id} lost the fixed-in-code step`,
    );
  }
});

test("every case carries the fields a tester needs to act on it", () => {
  for (const item of cases) {
    for (const field of [
      "id",
      "category",
      "title",
      "steps",
      "expected",
      "status",
      "note",
      "scope",
      "environment",
    ]) {
      assert.ok(item[field], `${item.id}: ${field} must be set`);
    }
    assert.ok(isKnownStatus(item.status), `${item.id}: unknown status "${item.status}"`);
  }
});

test("case ids are unique", () => {
  const ids = cases.map((item) => item.id);
  assert.equal(new Set(ids).size, ids.length);
});

// --- Counts are derived, never maintained ---------------------------------

test("status counts add up to the number of cases", () => {
  const counts = countByStatus(cases);
  const summed = Object.values(counts).reduce((total, count) => total + count, 0);
  assert.equal(summed, cases.length);
});

test("countByStatus reports a real zero for a status with no cases", () => {
  const counts = countByStatus([
    { id: "X-1", category: "A", title: "t", note: "n", status: "passed", scope: "Development website" },
  ]);
  assert.equal(counts.passed, 1);
  assert.equal(counts.blocked, 0);
  assert.equal(counts.notTested, 0);
});

test("countByStatus refuses a status the page has no label for", () => {
  assert.throws(
    () => countByStatus([{ id: "X-1", status: "looksFine" }]),
    /unknown case status "looksFine" on X-1/,
  );
});

test("open cases are the ones still needing somebody to act", () => {
  const counts = countByStatus(cases);
  assert.equal(
    openCaseCount(cases),
    counts.issue + counts.decision + counts.blocked + counts.notTested,
  );
  // Passing cases, with or without evidence gaps, are not "open".
  assert.equal(openCaseCount(cases) + counts.passed + counts.evidence, cases.length);
});

test("every status has a label and a plain-language description", () => {
  for (const status of STATUS_META) {
    assert.ok(status.label, `${status.key}: label must be set`);
    assert.ok(status.description, `${status.key}: description must be set`);
    assert.ok(statusMeta(status.key));
  }
  assert.equal(statusMeta("nonsense"), null);
});

// --- Filtering ------------------------------------------------------------

test("no filters shows every case", () => {
  assert.equal(filterCases(cases).length, cases.length);
  assert.equal(filterCases(cases, {}).length, cases.length);
});

test("a status filter returns only that status", () => {
  for (const status of STATUS_META) {
    const filtered = filterCases(cases, { status: status.key });
    assert.equal(filtered.length, countByStatus(cases)[status.key]);
    assert.ok(filtered.every((item) => item.status === status.key));
  }
});

test("an area filter returns only that area", () => {
  const area = caseAreas(cases)[0];
  const filtered = filterCases(cases, { area });
  assert.ok(filtered.length > 0);
  assert.ok(filtered.every((item) => item.category === area));
});

test("search finds a case by its id, case-insensitively and with surrounding space", () => {
  assert.deepEqual(
    filterCases(cases, { search: "  tc-041  " }).map((item) => item.id),
    ["TC-041"],
  );
});

test("search finds cases by words in the title", () => {
  const filtered = filterCases(cases, { search: "wildcard" });
  assert.ok(filtered.length > 0);
  assert.ok(filtered.every((item) => `${item.title} ${item.note}`.toLowerCase().includes("wildcard")));
});

test("search ignores the environment string, so a build name does not match everything", () => {
  // "Playwright" appears in environment text but in no title or note.
  assert.equal(filterCases(cases, { search: "playwright" }).length, 0);
});

test("filters narrow together rather than replacing one another", () => {
  const area = "Awards Search";
  const both = filterCases(cases, { area, status: "issue" });
  assert.ok(both.every((item) => item.category === area && item.status === "issue"));
  assert.ok(both.length <= filterCases(cases, { area }).length);
  assert.ok(both.length <= filterCases(cases, { status: "issue" }).length);
});

test("a search matching nothing returns an empty list rather than everything", () => {
  assert.equal(filterCases(cases, { search: "zzzznotacase" }).length, 0);
});

test("the results label states the real numbers and says so when nothing is filtered", () => {
  assert.equal(resultsLabel(47, 47), "Showing all 47 cases");
  assert.equal(resultsLabel(3, 47), "Showing 3 of 47 cases");
});

// --- Keeping evidence honest ----------------------------------------------

test("evidence scope is carried per case, and local work is not counted as deployed", () => {
  assert.ok(scopesPresent(cases).includes("Development website"));
  assert.equal(
    deployedEvidenceCount(cases),
    cases.filter((item) => item.scope === "Development website").length,
  );
  assert.ok(deployedEvidenceCount(cases) < cases.length, "some cases are not dev-verified");
});

test("TC-017 passes only because both halves were verified on the development website", () => {
  // Two releases: the API fix (rev 73) and the UI message (Amplify #104).
  // Checked in the browser across all six search pages before this
  // status was set.
  const item = cases.find((candidate) => candidate.id === "TC-017");
  assert.ok(item);
  assert.equal(item.status, "passed");
  assert.equal(item.scope, "Development website");
  assert.equal(item.progress.stage, "verified");
  assert.deepEqual(verificationShortfalls(item), []);
  assert.equal(item.progress.verifiedIn, "Development website");
  assert.equal(item.progress.verifiedOn, "2026-10-05");
  // Nothing may read as pending any more.
  assert.doesNotMatch(item.note, /Half fixed|in review|awaiting deployment/i);
});

test("the two cases with evidence gaps are not presented as unqualified passes", () => {
  const gapped = cases.filter((item) => item.status === "evidence");
  assert.equal(gapped.length, 2);
  for (const item of gapped) {
    assert.match(item.note, /outstanding|remain|not tested/i);
  }
});

test("TC-018 passes only with both halves released and verified on dev", () => {
  const item = cases.find((candidate) => candidate.id === "TC-018");
  assert.ok(item);
  assert.equal(item.status, "passed");
  assert.equal(item.progress.stage, "verified");
  // The scope moves with the evidence: it was local-only until the API
  // and UI were both released and re-checked on the deployed site.
  assert.equal(item.scope, "Development website");
  assert.equal(item.progress.verifiedIn, "Development website");
  assert.equal(item.progress.verifiedOn, "2026-10-05");
  assert.deepEqual(verificationShortfalls(item), []);

  // Both builds recorded, separately, so neither reads as the whole fix.
  assert.match(item.progress.deployedBuild, /rev 74/);
  assert.match(item.progress.deployedBuild, /#108/);
  assert.match(item.progress.change, /PR #23/);
  assert.match(item.progress.change, /PR #24/);

  // Evidence covers the interactions, not only the API.
  assert.match(item.progress.results, /Apply Filters/);
  assert.match(item.progress.results, /dashboard/i);
  assert.match(item.progress.results, /200 of 200 characters/);
  assert.match(item.progress.results, /seven search endpoints/);
  assert.match(item.progress.results, /two-character minimum/);

  // Nothing pending may survive on the current note.
  assert.doesNotMatch(item.note, /being corrected|in review|Not deployed/i);
});

test("TC-018 keeps its local-only findings in history rather than losing them", () => {
  const item = cases.find((candidate) => candidate.id === "TC-018");
  // The original timings and the local-only evidence both survive the
  // move to a development-website scope.
  assert.ok(item.history.some((entry) => /6,000/.test(entry.summary)));
  assert.ok(item.history.some((entry) => /local only/i.test(entry.summary)));
  assert.ok(item.history.length >= 3);
});

test("TC-041 is closed, verified on the released UI and API together", () => {
  const item = cases.find((candidate) => candidate.id === "TC-041");
  assert.ok(item);
  assert.equal(item.status, "passed");
  assert.equal(item.progress.stage, "verified");
  assert.equal(item.scope, "Development website");
  assert.equal(item.progress.verifiedOn, "2026-10-06");
  assert.equal(item.progress.verifiedIn, "Development website");
  assert.deepEqual(verificationShortfalls(item), []);

  // Both builds recorded, tied to the one release SHA, so neither half
  // can be mistaken for the whole.
  assert.match(item.progress.deployedBuild, /#113/);
  assert.match(item.progress.deployedBuild, /rev 75/);
  assert.match(item.progress.deployedBuild, /8292260/);
  assert.match(item.progress.change, /PR #28/);
  assert.match(item.progress.change, /PR #30/);

  // Nothing pending may survive on a closed case.
  assert.doesNotMatch(item.note, /in review|Not deployed|being corrected|remains open/i);
  assert.match(item.progress.verificationStatus, /^Closed\./);
});

test("TC-041's closure states what was checked live and what is test-only", () => {
  const item = cases.find((candidate) => candidate.id === "TC-041");
  // Live acceptance evidence.
  for (const proof of [
    /No direct matches/,
    /Show 5 related results/,
    /pediatric asthma/,
    /Teaming agreement/,
    /time to pregnancy/,
    /two-character minimum/,
    /TC-017/,
    /TC-018/,
  ]) {
    assert.match(item.progress.results, proof);
  }
  // The two boundaries, stated as test-only rather than implied.
  assert.match(item.progress.limitations, /automated tests only/);
  assert.match(item.progress.limitations, /module failing/);
  assert.match(item.progress.limitations, /1120419/);
  // The withdrawn finding is kept as a probe error, not a defect.
  assert.match(item.progress.results, /withdrawn/);
  assert.match(item.progress.results, /error in how the check read the page/);
  // History keeps the path, including the rejected approach.
  assert.ok(item.history.some((entry) => /never deployed/.test(entry.summary)));
  assert.ok(item.history.some((entry) => /document number/i.test(entry.summary)));
});

test("TC-018 stays closed and verified while TC-041 is worked on", () => {
  const item = cases.find((candidate) => candidate.id === "TC-018");
  assert.equal(item.status, "passed");
  assert.equal(item.progress.stage, "verified");
  assert.deepEqual(verificationShortfalls(item), []);
});

test("blocked cases stay blocked and say what is needed", () => {
  const blocked = cases.filter((item) => item.status === "blocked");
  assert.equal(blocked.length, 2);
  for (const item of blocked) {
    assert.match(item.note, /account|permission|tester/i);
  }
});

// --- Security requirements ------------------------------------------------

test("all nine requirements are present with stable SEC ids", () => {
  assert.deepEqual(
    requirements.map((requirement) => requirement.id),
    [
      "SEC-001", "SEC-002", "SEC-003", "SEC-004", "SEC-005",
      "SEC-006", "SEC-007", "SEC-008", "SEC-009",
    ],
  );
  assert.deepEqual(
    requirements.map((requirement) => requirement.csvId),
    [1, 2, 3, 4, 5, 6, 7, 8, 9],
  );
});

test("each requirement preserves the source document's own wording", () => {
  // Spot-checked against the Security Requirements tab verbatim - the
  // page may add explanation around these, never paraphrase them.
  const byId = Object.fromEntries(requirements.map((item) => [item.id, item]));
  assert.equal(
    byId["SEC-001"].requirement,
    "Central users shall have unrestricted access to all system objects and functionality provided by the system.",
  );
  assert.equal(
    byId["SEC-003"].requirement,
    "Research staff shall have access only to system objects on which they are directly listed as a contact.",
  );
  assert.equal(
    byId["SEC-007"].requirement,
    "The system shall enforce access restrictions when a user attempts to access a system object for which they are not authorized.",
  );
  assert.equal(
    byId["SEC-008"].requirement,
    "The system shall maintain an auditable record of all archive activity, including the user, date and time, and action performed, to support security investigations when necessary.",
  );
  assert.equal(
    byId["SEC-009"].requirement,
    "The system shall provide the archive in a read-only state to prevent historical records from being modified, updated, or deleted.",
  );
  for (const requirement of requirements) {
    assert.ok(requirement.requirement.length > 40, `${requirement.id}: wording looks truncated`);
    assert.ok(requirement.acceptance, `${requirement.id}: acceptance criteria must be set`);
    assert.ok(requirement.accessScope, `${requirement.id}: access scope must be set`);
  }
});

test("no requirement claims to be verified while enforcement is off", () => {
  const summary = securitySummary(requirements);
  assert.equal(summary.total, 9);
  assert.equal(summary.verified, 0);
  assert.equal(summary.headline, "0 of 9 requirements verified");

  // The two facts that must never drift apart from reality.
  for (const requirement of requirements) {
    assert.notEqual(
      requirement.status.enforcementEnabled,
      "yes",
      `${requirement.id}: enforcement is off in every environment`,
    );
    assert.notEqual(
      requirement.status.acceptanceVerified,
      "yes",
      `${requirement.id}: nothing has been accepted yet`,
    );
  }
  assert.ok(enforcementIsOffEverywhere(requirements));
});

test("the merged authorization work cites its real merge SHAs", () => {
  /*
   * Replaces the earlier guard that PR #62 must never be shown as merged.
   * It is merged now - b77f03f for Award, f1b8115 for Proposal - so the
   * check inverts: anything claiming mergedToMain must name the commit that
   * carries it, because "merged" with no SHA is unfalsifiable.
   */
  const merged = requirements.filter((item) => item.status.mergedToMain === "yes");
  assert.ok(merged.length > 0, "the Award and Proposal work is merged");

  const awardProposal = ["SEC-001", "SEC-002", "SEC-003", "SEC-007"];
  for (const id of awardProposal) {
    const requirement = requirements.find((item) => item.id === id);
    assert.equal(requirement.status.mergedToMain, "yes", `${id}: merged`);
    assert.match(requirement.evidence, /b77f03f/, `${id}: must cite the Award merge SHA`);
    assert.match(requirement.evidence, /f1b8115/, `${id}: must cite the Proposal merge SHA`);
    // The old "draft / not merged" wording must be gone.
    assert.doesNotMatch(requirement.evidence, /draft|not merged/i, `${id}: stale draft wording`);
  }
});

test("a merge is never reported as a deployment of the backend", () => {
  /*
   * The trap this page exists to avoid. Amplify rebuilds the UI on every
   * merge to main and did so for both authorization merges, but the API is
   * deployed separately and still runs task definition revision 81. Anything
   * whose evidence names the Award or Proposal merge must say deployed: no.
   */
  for (const id of ["SEC-001", "SEC-002", "SEC-003", "SEC-007"]) {
    const requirement = requirements.find((item) => item.id === id);
    assert.equal(
      requirement.status.deployed,
      "no",
      `${id}: merged backend code is not in the running API`,
    );
    assert.match(
      requirement.evidence,
      /[Nn]ot in the running API/,
      `${id}: must say the merged code is not running`,
    );
  }

  // And the page must state the distinction where nobody can miss it.
  assert.match(security.apiDeploymentState, /MERGED INTO MAIN but are NOT\s+running/);
  assert.match(security.apiDeploymentState, /revision 81/);
  assert.match(security.apiDeploymentState, /not a backend deployment/i);
});

test("Phase 1 statuses for SEC-005, SEC-006, SEC-008 and SEC-009 are preserved", () => {
  const byId = Object.fromEntries(requirements.map((item) => [item.id, item]));

  // The administrative service shipped in #56 and IS in the running API.
  assert.equal(byId["SEC-005"].status.mergedToMain, "yes");
  assert.equal(byId["SEC-005"].status.deployed, "yes");
  /*
   * SEC-006 was "partial" because there was no API surface to revoke through.
   * PR #74 built one (merged b11efaa), so the merge half is now complete and
   * the honest value changed with the fact. Deployed is a flat no: revision 81
   * predates every authorization merge, checked against the live service.
   */
  assert.equal(byId["SEC-006"].status.mergedToMain, "yes");
  assert.equal(byId["SEC-006"].status.deployed, "no");
  // SEC-008 stays open; SEC-009 keeps policy-approved / acceptance-unverified.
  assert.equal(byId["SEC-008"].status.decisionApproved, "open");
  assert.equal(byId["SEC-009"].status.decisionApproved, "approved");
  assert.equal(byId["SEC-009"].status.acceptanceVerified, "no");
  assert.equal(byId["SEC-009"].status.testsPassed, "no");
});


test("the seven implementation stages are reported separately, never collapsed", () => {
  // The whole point: an approved decision, passing tests, a merge, a
  // deployment and an acceptance verification are five different facts.
  for (const requirement of requirements) {
    for (const stage of SECURITY_STAGES) {
      assert.ok(
        Object.prototype.hasOwnProperty.call(requirement.status, stage.key),
        `${requirement.id}: missing stage ${stage.key}`,
      );
    }
  }
  /*
   * At least one requirement must demonstrate the stages disagreeing,
   * otherwise the distinction is decorative. Today the live example is
   * merged-but-not-deployed: the Award and Proposal code is on main and is
   * not in the running API. (It used to be tests-passing-but-unmerged,
   * which stopped being true when #62 and #64 merged - the assertion
   * follows reality rather than pinning a moment.)
   */
  assert.ok(
    requirements.some(
      (requirement) =>
        requirement.status.mergedToMain === "yes" && requirement.status.deployed === "no",
    ),
    "a merge without a backend deployment must be representable and represented",
  );
});

test("a rule implemented for Award only is never reported as fully implemented", () => {
  /*
   * SEC-001, SEC-002 and SEC-003 are written against "all system objects".
   * They are implemented for Award and nothing else, so "yes" would claim a
   * cross-module guarantee that does not exist. They may only say "yes" once
   * every module listed as pending in moduleProgress has gone.
   */
  /*
   * Re-keyed 2026-10-27. This used to look for modules in state "pending",
   * and none remain - all four authorization modules are merged. What it
   * actually protects is unchanged: IRB holds archive records and has no
   * gate, by decision, so "all system objects" is still not demonstrated.
   * IRB is deliberately NOT an authorization module here; it appears as
   * shared-surface isolation, which is why this now looks for any module
   * that is not merged rather than for the old label.
   */
  const notMerged = (security.moduleProgress || []).filter(
    (module) => !["merged-not-deployed", "implemented", "working"].includes(module.state),
  );
  assert.ok(
    notMerged.length > 0,
    "something is still uncovered or unmerged; this guard must apply",
  );

  for (const id of ["SEC-001", "SEC-002", "SEC-003"]) {
    const requirement = requirements.find((item) => item.id === id);
    assert.notEqual(
      requirement.status.codeImplemented,
      "yes",
      `${id}: ${notMerged.length} area(s) still uncovered or unmerged`,
    );
    /*
     * The blocker must NAME the modules that are still uncovered. Which
     * ones they are changes as modules land - Award only, then Award and
     * Proposal - so the check is that at least one uncovered module is
     * named, not that a particular phrase appears.
     */
    assert.match(
      requirement.blockers,
      /Negotiation|Subaward|IRB|not wired|all system objects/i,
      `${id}: the uncovered modules must be named, not implied`,
    );
  }
});

test("an established policy is not the same as a verified acceptance criterion", () => {
  // SEC-009: read-only is long-standing project policy, so the decision is
  // settled - but no test proves the absence of a write path, so acceptance
  // is NOT verified. The page must hold those two apart.
  const nine = requirements.find((item) => item.id === "SEC-009");
  assert.equal(nine.status.decisionApproved, "approved");
  assert.equal(nine.status.acceptanceVerified, "no");
  assert.equal(nine.status.testsPassed, "no");
  assert.match(nine.businessRule, /ESTABLISHED PROJECT POLICY/);
  assert.match(nine.tests, /NOT acceptance verification/i);
  // And it is not one of the nine decisions taken on 2026-10-26.
  assert.ok(
    !security.decisionsApproved.some((decision) => /read-only/i.test(decision)),
    "read-only policy predates the 2026-10-26 decisions and must not be listed among them",
  );
});

test("the enforcement claim states its evidence and its limits", () => {
  /*
   * "OFF in every environment" is a stronger claim than the evidence
   * supports: three environments are defined in Terraform, only dev has a
   * recorded deployment, and no running instance was queried. The statement
   * must say what was actually checked.
   */
  assert.match(security.enforcementState, /OFF/);
  assert.match(security.enforcementState, /NO committed configuration file/);
  assert.match(security.enforcementState, /defaults to false/);
  assert.match(security.enforcementState, /dev, test and prod/);
  assert.match(security.enforcementState, /only\s+dev has a recorded deployment/);
  /*
   * Was /No runtime check/. A runtime check against the live dev service WAS
   * performed on 2026-10-27, so requiring the old caveat would now force the
   * page to understate its evidence. The claim must still say what was
   * observed versus what is asserted from configuration.
   */
  assert.match(security.enforcementState, /A runtime check WAS performed/);
  assert.match(security.enforcementState, /asserted from\s+configuration rather than observed/);
  // The overclaim this replaced must not come back.
  assert.doesNotMatch(security.enforcementState, /OFF in every environment/i);
});

test("genuinely unresolved questions survive, and settled ones are cleared", () => {
  const conflicted = requirementsWithConflicts(requirements).map((item) => item.id);

  // SEC-008 is the one that must NOT be closed: an earlier decision
  // against routine audit logging has not been reconciled with the
  // requirement as written.
  assert.ok(conflicted.includes("SEC-008"), "the audit-logging conflict must stay open");
  assert.equal(
    requirements.find((item) => item.id === "SEC-008").status.decisionApproved,
    "open",
  );

  // SEC-002's PAFO Administrator ambiguity was decided on 2026-10-26, so
  // it must no longer be presented as an open question.
  assert.ok(
    !conflicted.includes("SEC-002"),
    "the PAFO Administrator question was decided and must not still be flagged",
  );
  assert.match(
    requirements.find((item) => item.id === "SEC-002").businessRule,
    /PAFO Administrator/,
  );
});

test("approved decisions are listed, and separated from implementation status", () => {
  assert.ok(Array.isArray(security.decisionsApproved));
  assert.equal(security.decisionsApproved.length, 9);
  const joined = security.decisionsApproved.join(" ");
  for (const phrase of [
    "PAFO Administrator",
    "SAP account number",
    "entire Award family",
    "ARCHIVE_ADMIN",
    "Access Not Provisioned",
    "archive-wide statistics",
    "omitted entirely",
  ]) {
    assert.ok(joined.includes(phrase), `missing approved decision: ${phrase}`);
  }
  // An approved decision must never imply the code is done.
  assert.match(security.recommendationStatus, /not working software/i);
});

test("the page renders the stages, the module table and the decision list", () => {
  const source = readSource("../../pages/QaStatusPage.tsx");
  assert.match(source, /Security implementation progress/);
  assert.match(source, /Approved business decisions/);
  assert.match(source, /securityStages\(requirement\)/);
  assert.match(source, /Remaining blockers/);
  assert.match(source, /Test coverage/);
  // Enforcement-off must be impossible to miss - but stated no more
  // strongly than the evidence in securityRequirements.json supports.
  assert.match(source, /Record-level authorization is OFF</);
  assert.doesNotMatch(source, /OFF in every environment/i);
  // And the merged-but-not-running distinction must be on the page.
  assert.match(source, /Merged, but not running/);
  assert.match(source, /apiDeploymentState/);
});

test("the enforcement state is stated so no one reads these as live controls", () => {
  assert.match(security.enforcementState, /OFF/);
  assert.match(security.enforcementState, /inert|not currently running/i);
});

// --- What must not reach a browser-delivered asset ------------------------

test("no named test identities or record fixtures are shipped to the browser", () => {
  // These live in restricted test documentation. The page describes the
  // groups, never the people or the exact records they can reach.
  const payload = `${JSON.stringify(snapshot)} ${JSON.stringify(security)}`.toUpperCase();
  for (const name of [
    "FARRER",
    "RAYAMAJHI",
    "SCHINDELE",
    "ANTCAST",
    "2573180018",
    "9500316722",
    "9500317253",
    "9500312705",
  ]) {
    assert.ok(!payload.includes(name), `"${name}" must not ship in a frontend asset`);
  }
});

test("no credentials, tokens or local filesystem paths are shipped to the browser", () => {
  const payload = `${JSON.stringify(snapshot)} ${JSON.stringify(security)}`.toLowerCase();
  for (const term of [
    "password",
    "secret",
    "bearer ",
    "session token",
    "mysapsso2",
    "jsessionid",
    "/users/",
    "unredacted",
  ]) {
    assert.ok(!payload.includes(term), `"${term}" must not ship in a frontend asset`);
  }
});

test("the excluded case's subject matter is not described in the shipped asset either", () => {
  const payload = `${JSON.stringify(snapshot)} ${JSON.stringify(security)}`.toLowerCase();
  assert.ok(!payload.includes("sap transmission"));
});

// --- Page wiring (static source inspection - no component-render harness) -

test("QaStatusPage.tsx derives its totals from the presentation module", () => {
  const source = readSource("../../pages/QaStatusPage.tsx");
  assert.match(
    source,
    /from\s*"[^"]*qaStatusPresentation\.mjs"/,
    "the page must use the shared, tested helpers rather than its own counting",
  );
  assert.match(source, /securityRequirements\.json/, "the page must render the security section");
});

test("App.tsx routes /qa-status to the page", () => {
  const source = readSource("../../App.tsx");
  const routeBlock = source.match(/path="qa-status"[\s\S]{0,120}/)?.[0];
  assert.ok(routeBlock, "expected a qa-status route block");
  assert.match(routeBlock, /QaStatusPage/);
});

test("the QA status link is declared in the shared navigation config, not inline in AppLayout", () => {
  // AppLayout renders from navigationPresentation.mjs; a second inline
  // list there is exactly the drift the navigation tests guard against.
  const nav = readSource("../navigation/navigationPresentation.mjs");
  assert.match(nav, /path:\s*"\/qa-status"/);

  const layout = readSource("../../layout/AppLayout.tsx");
  assert.ok(
    !/to="\/qa-status"/.test(layout),
    "AppLayout must not hard-code the QA status link",
  );
});


// --- Progress tracking: a fix is not done until it is verified -----------

test("every tracked case uses a known progress stage", () => {
  for (const item of trackedCases(cases)) {
    assert.ok(
      isKnownProgressStage(item.progress.stage),
      `${item.id}: unknown progress stage "${item.progress.stage}"`,
    );
  }
});

test("no tracked case is marked passed before it has been verified", () => {
  // The rule this page exists to keep: PASS only after the case's
  // acceptance criteria are verified in the required environment.
  assert.deepEqual(
    casesClaimingAnUnverifiedPass(cases).map((item) => item.id),
    [],
  );
});

test("a verified claim must carry its date, environment and result", () => {
  assert.deepEqual(
    casesWithUnevidencedVerification(cases).map((item) => item.id),
    [],
  );
});

test("a qualified pass counts as a pass: neither may precede verification", () => {
  // "Passed, more checks needed" still reads as passed to a tester, so
  // a tracked fix may not claim it before being verified either.
  for (const status of ["passed", "evidence"]) {
    const premature = {
      id: `X-${status}`,
      status,
      progress: { stage: "fixedInCode" },
    };
    assert.deepEqual(
      casesClaimingAnUnverifiedPass([premature]).map((item) => item.id),
      [`X-${status}`],
      `${status} must not be claimable before verification`,
    );
  }
});

test("a released build must be recorded before verification is accepted", () => {
  // The exact value TC-017 carries today while it waits for release.
  const notDeployed = {
    id: "X-1",
    status: "passed",
    progress: {
      stage: "verified",
      deployedBuild: "Not deployed",
      verifiedOn: "2026-10-05",
      verifiedIn: "Development website",
      results: "Behaved as the case requires.",
    },
  };
  assert.deepEqual(verificationShortfalls(notDeployed), ["no released build recorded"]);
  assert.deepEqual(casesWithUnevidencedVerification([notDeployed]).map((i) => i.id), ["X-1"]);
});

test("local-only evidence cannot satisfy a case that requires the development website", () => {
  const localOnly = {
    id: "X-2",
    status: "passed",
    progress: {
      stage: "verified",
      requiredEnvironment: "Development website",
      deployedBuild: "api rev 73",
      verifiedOn: "2026-10-05",
      verifiedIn: "Local test environment",
      results: "All five searches returned 400 VALIDATION_ERROR.",
    },
  };
  const reasons = verificationShortfalls(localOnly);
  assert.equal(reasons.length, 1);
  assert.match(reasons[0], /Local test environment/);
  assert.match(reasons[0], /requires "Development website"/);
  assert.deepEqual(casesWithUnevidencedVerification([localOnly]).map((i) => i.id), ["X-2"]);
});

test("a candidate build is not the development website either", () => {
  const candidate = {
    id: "X-3",
    status: "passed",
    progress: {
      stage: "verified",
      deployedBuild: "api rev 73",
      verifiedOn: "2026-10-05",
      verifiedIn: "Candidate build",
      results: "Looked right.",
    },
  };
  assert.equal(verificationShortfalls(candidate).length, 1);
});

test("placeholder text is rejected exactly like a blank field", () => {
  for (const value of ["", "  ", "N/A", "TBD", "pending", "Not deployed", "unknown", "-", "?"]) {
    assert.ok(isPlaceholderEvidence(value), `"${value}" must count as no evidence`);
  }
  assert.ok(isPlaceholderEvidence(null));
  assert.ok(isPlaceholderEvidence(undefined));
  assert.ok(!isPlaceholderEvidence("api rev 73"));
  assert.ok(!isPlaceholderEvidence("Development website"));

  for (const field of ["deployedBuild", "verifiedOn", "verifiedIn", "results"]) {
    const item = {
      id: `X-${field}`,
      status: "passed",
      progress: {
        stage: "verified",
        deployedBuild: "api rev 73",
        verifiedOn: "2026-10-05",
        verifiedIn: "Development website",
        results: "Behaved as the case requires.",
      },
    };
    item.progress[field] = "TBD";
    assert.ok(
      verificationShortfalls(item).length > 0,
      `a placeholder in ${field} must fail verification`,
    );
  }
});

test("a verification date has to be a real date", () => {
  const vague = {
    id: "X-4",
    status: "passed",
    progress: {
      stage: "verified",
      deployedBuild: "api rev 73",
      verifiedOn: "last week",
      verifiedIn: "Development website",
      results: "Fine.",
    },
  };
  assert.deepEqual(verificationShortfalls(vague), ["verification date is not a real date"]);
});

test("an impossible calendar date is rejected, not just a malformed one", () => {
  // Right shape, no such day. A regex alone would accept all of these.
  for (const value of [
    "2026-02-30",
    "2026-99-99",
    "2026-13-01",
    "2026-00-10",
    "2026-04-31",
    "2026-02-29",
    "2026-01-32",
    "2026-01-00",
  ]) {
    assert.ok(!isRealIsoDate(value), `"${value}" is not a real date`);
  }
});

test("real dates, including a leap day in a leap year, are accepted", () => {
  for (const value of ["2026-10-05", "2026-01-01", "2026-12-31", "2024-02-29", "2026-02-28"]) {
    assert.ok(isRealIsoDate(value), `"${value}" is a real date`);
  }
});

test("a malformed date is still rejected", () => {
  for (const value of ["", "last week", "05-10-2026", "2026-1-5", "20261005", null, undefined]) {
    assert.ok(!isRealIsoDate(value), `"${value}" must be rejected`);
  }
});

test("an impossible date fails verification through the real guard", () => {
  const impossible = {
    id: "X-7",
    status: "passed",
    progress: {
      stage: "verified",
      deployedBuild: "api rev 73",
      verifiedOn: "2026-02-30",
      verifiedIn: "Development website",
      results: "Looked right.",
    },
  };
  assert.deepEqual(verificationShortfalls(impossible), ["verification date is not a real date"]);
  assert.deepEqual(casesWithUnevidencedVerification([impossible]).map((i) => i.id), ["X-7"]);
});

test("a complete, correctly-sited claim passes every guard", () => {
  const proper = {
    id: "X-5",
    status: "passed",
    progress: {
      stage: "verified",
      requiredEnvironment: "Development website",
      deployedBuild: "api rev 73 (source 884142d)",
      verifiedOn: "2026-10-05",
      verifiedIn: "Development website",
      results: "All five searches returned 400 VALIDATION_ERROR; ordinary queries unaffected.",
    },
  };
  assert.deepEqual(verificationShortfalls(proper), []);
  assert.deepEqual(casesClaimingAnUnverifiedPass([proper]), []);
  assert.deepEqual(casesWithUnevidencedVerification([proper]), []);
});

test("several missing pieces are all reported, not just the first", () => {
  const empty = {
    id: "X-6",
    status: "passed",
    progress: { stage: "verified", deployedBuild: "", verifiedOn: "", verifiedIn: "", results: "" },
  };
  assert.equal(verificationShortfalls(empty).length, 4);
});

test("every progress stage has a label and a plain-language description", () => {
  for (const stage of PROGRESS_STAGES) {
    assert.ok(stage.label, `${stage.key}: label must be set`);
    assert.ok(stage.description, `${stage.key}: description must be set`);
    assert.ok(progressStage(stage.key));
  }
  assert.equal(progressStage("nonsense"), null);
});

test("both releases are recorded, and the whole path is preserved in history", () => {
  const item = cases.find((candidate) => candidate.id === "TC-017");
  // Both halves named, so neither release can be mistaken for the whole.
  assert.match(item.progress.change, /PR #18/);
  assert.match(item.progress.change, /PR #20/);
  assert.match(item.progress.deployedBuild, /rev 73/);
  assert.match(item.progress.deployedBuild, /#104/);
  // Verified in the browser, not only against the API.
  assert.match(item.progress.results, /search pages/);
  assert.match(item.progress.results, /sponsor filter/);
  assert.match(item.progress.results, /250-character/);
  assert.match(item.progress.results, /105698-00001/);
  // The honest caveat survives the pass.
  assert.match(item.progress.limitations, /TC-018/);
  // Every earlier state is kept, including the pass that was withdrawn.
  const stages = item.history.map((entry) => entry.stage);
  for (const stage of ["knownIssue", "fixedInCode", "verified", "inProgress"]) {
    assert.ok(stages.includes(stage), `history must keep the ${stage} state`);
  }
  assert.ok(item.history.some((entry) => /Reversed/.test(entry.summary)));
});

test("earlier findings are preserved rather than overwritten", () => {
  const item = cases.find((candidate) => candidate.id === "TC-017");
  assert.ok(Array.isArray(item.history) && item.history.length >= 4,
    "every earlier state must be kept, including the pass that was withdrawn");
  for (const entry of item.history) {
    assert.match(entry.on, /^\d{4}-\d{2}-\d{2}$/);
    assert.ok(entry.summary, "a history entry must say what was found");
  }
});

test("the page renders the progress block and earlier findings", () => {
  const source = readSource("../../pages/QaStatusPage.tsx");
  assert.match(source, /item\.progress/);
  assert.match(source, /Earlier findings/);
  assert.match(source, /Remaining limitations/);
});
