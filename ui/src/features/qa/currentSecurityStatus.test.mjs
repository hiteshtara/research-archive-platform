/*
 * The contradiction this file exists to prevent.
 *
 * On 2026-11-10 the readiness page said, in one block, "the running API now
 * carries them - see the deployment block above", and in the table immediately
 * below it showed ten orange "merged-not-deployed" chips and evidence reading
 * "NOT in the running API (revision 81)".
 *
 * Both came from committed data. The deployment snapshot was refreshed every
 * release and was right; the acceptance plan was written on 2026-10-20 and was
 * frozen. The page rendered the frozen one as current status.
 *
 * The fix was not to edit ten labels. It was to stop the current table reading
 * the frozen source at all: deployed, enabled and tested are now DERIVED from
 * the deployment snapshot, and only acceptance still comes from the acceptance
 * plan, because only the business can set it.
 *
 * These tests hold that separation. Several of them would have failed against
 * the page as it stood when the inconsistency was reported.
 */

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import readiness from "./securityReadiness.json" with { type: "json" };
import securityData from "./securityRequirements.json" with { type: "json" };
import {
  STATUS_DIMENSIONS,
  currentStatusMatrix,
  currentStatusTotals,
  headlineFor,
  supersededDeploymentRecords,
} from "./currentSecurityStatus.mjs";

const read = (relative) => readFileSync(fileURLToPath(new URL(relative, import.meta.url)), "utf8");
const pageSource = read("../../pages/SecurityReadinessPage.tsx");

/*
 * Source with comments stripped. These tests ask what the page RENDERS, and
 * the comment explaining why moduleProgress is no longer read contains the
 * word moduleProgress - a naive scan reads the explanation as the thing it
 * explains.
 */
const page = pageSource
  .replace(/\/\*[\s\S]*?\*\//g, " ")
  .replace(/^\s*\/\/.*$/gm, " ");
const requirements = securityData.requirements;
const matrix = currentStatusMatrix(requirements, readiness.capabilities, readiness.deployment);

function deployedRevision() {
  return Number(readiness.deployment.apiTaskDefinition.match(/:(\d+)$/)[1]);
}

// ── the contradiction itself ─────────────────────────────────────────

test("no requirement is reported as merged-but-not-deployed while the snapshot says it is deployed", () => {
  /*
   * The exact defect. securityRequirements.json still carries
   * status.deployed:"no" for all nine - correctly, as a 2026-10-20 record -
   * and the current matrix must not inherit it.
   */
  for (const status of matrix) {
    const capability = readiness.capabilities.find((c) => c.id === status.id);
    assert.equal(
      status.deployed,
      capability.states.deployed,
      `${status.id}: the current table must take 'deployed' from the verified snapshot`,
    );
  }

  const stillClaimingUndeployed = matrix.filter(
    (status) => status.deployed !== "yes" && status.codeComplete !== "n/a",
  );
  assert.deepEqual(
    stillClaimingUndeployed.map((s) => s.id),
    [],
    "every requirement is in the running API; none may read as not deployed",
  );
});

test("the current table does not render the frozen acceptance-plan states", () => {
  // moduleProgress[].state is the field that produced the ten orange chips.
  assert.ok(
    !page.includes("moduleProgress"),
    "the page must not read moduleProgress as current status",
  );
  assert.ok(
    !/label=\{module\.state\}/.test(page),
    "the merged-not-deployed chip must be gone from the current table",
  );
  assert.match(page, /currentStatusMatrix\(/, "the current table must be derived");
});

test("no current-status text names a superseded revision", () => {
  /*
   * Scans what the CURRENT sections render. The historical block is allowed
   * to name revision 81 - that is what it is for - so the check is on the
   * derived matrix and the deployment snapshot, not on the page as a whole.
   */
  const current = deployedRevision();
  /*
   * imageReference is excluded deliberately. It records that the deploy
   * script first registered revision 86 by tag and that 87 re-pins the same
   * image by digest - a note about a revision NOT being used, which is the
   * opposite of a stale claim and is exactly the kind of detail that is
   * invisible later unless written down.
   */
  const { imageReference, ...deploymentClaims } = readiness.deployment;
  const text = JSON.stringify(matrix) + JSON.stringify(deploymentClaims);

  for (const [, named] of text.matchAll(/revision (\d+)/gi)) {
    assert.ok(
      Number(named) >= current,
      `current status names revision ${named}, older than the deployed ${current}`,
    );
  }
  for (const [, named] of text.matchAll(/dev-api:(\d+)/g)) {
    assert.equal(Number(named), current);
  }
});

// ── history preserved ────────────────────────────────────────────────

test("the superseded records are kept, not deleted", () => {
  const superseded = supersededDeploymentRecords(securityData, readiness.deployment);

  assert.equal(superseded.length, 19, "10 modules plus 9 requirements were recorded against revision 81");
  assert.equal(superseded.filter((r) => r.kind === "module").length, 10);
  assert.equal(superseded.filter((r) => r.kind === "requirement").length, 9);

  // and the underlying data is untouched
  assert.equal(
    securityData.moduleProgress.filter((m) => m.state === "merged-not-deployed").length,
    10,
    "the original record must not be rewritten - it is what was true then",
  );
});

test("the page renders the superseded records under a historical heading", () => {
  assert.match(page, /supersededDeploymentRecords\(/);
  assert.match(page, /Implementation states as at/);
  assert.match(page, /Historical record/);
});

// ── enforcement off is not a code failure ────────────────────────────

test("enforcement being off reads as a configuration state, never as missing code", () => {
  /*
   * The distinction the old table destroyed. "Not enabled" is a decision;
   * "not deployed" is a gap. Showing the second when the first is true is
   * what made delivered work look undelivered.
   */
  for (const status of matrix) {
    if (status.codeComplete === "n/a") continue;
    /*
     * SEC-009 reads n/a rather than no: it is enforced by the database role,
     * not by a configuration flag, so "switched on in DEV" is not a question
     * that applies to it. Everything else must be a plain no.
     */
    assert.ok(
      ["no", "n/a"].includes(status.enabled),
      `${status.id} is switched on; enforcement is supposed to be off everywhere`,
    );
    assert.equal(status.deployed, "yes", "but everything is deployed");
    assert.ok(
      !/not deployed/i.test(status.headline.label),
      `${status.id} reads "${status.headline.label}" while being deployed`,
    );
  }

  assert.match(
    page,
    /configuration decision and not\s*\n?\s*a gap in the deployed code/,
    "the page must say so in words, not only in chip colours",
  );
});

test("acceptance pending reads as awaiting sign-off, never as incomplete implementation", () => {
  const pending = matrix.filter((s) => s.accepted === "no" && s.deployed === "yes");
  assert.ok(pending.length > 0);

  for (const status of pending) {
    assert.match(
      status.headline.label,
      /Acceptance Pending|Not Enabled/,
      `${status.id} must name the reason it is not finished`,
    );
    assert.ok(
      !/incomplete|not implemented|missing/i.test(status.headline.label),
      `${status.id} reads as unbuilt when it is built and waiting`,
    );
  }
});

// ── the vocabulary the business asked for ────────────────────────────

test("deployed-but-off says exactly: Deployed — Not Enabled", () => {
  assert.equal(
    headlineFor({ codeComplete: "yes", deployed: "yes", enabled: "no", enforcing: "no", accepted: "no" }).label,
    "Deployed — Not Enabled",
  );
});

test("deployed-and-on-but-unaccepted says exactly: Deployed — Acceptance Pending", () => {
  assert.equal(
    headlineFor({ codeComplete: "yes", deployed: "yes", enabled: "yes", enforcing: "no", accepted: "no" }).label,
    "Deployed — Acceptance Pending",
  );
});

test("undeployed code is still named as such - the fix must not flatter everything", () => {
  assert.equal(
    headlineFor({ codeComplete: "yes", deployed: "no", enabled: "no", enforcing: "no", accepted: "no" }).label,
    "In source — Not Deployed",
  );
});

test("SEC-009 is in force, and still not accepted", () => {
  const sec009 = matrix.find((s) => s.id === "SEC-009");
  assert.equal(sec009.enforcing, "yes", "the database role enforces it regardless of the flag");
  assert.equal(sec009.accepted, "no");
  assert.equal(sec009.headline.label, "In force — Acceptance Pending");
});

// ── nothing is marked accepted ───────────────────────────────────────

test("no requirement is marked business-accepted, and acceptance has one source", () => {
  assert.equal(currentStatusTotals(matrix).accepted, 0);

  for (const status of matrix) {
    const requirement = requirements.find((r) => r.id === status.id);
    assert.equal(
      status.accepted,
      requirement.status.acceptanceVerified,
      `${status.id}: acceptance must come from the acceptance plan, not be derived`,
    );
  }
});

test("the derived matrix cannot invent acceptance even if the snapshot claims it", () => {
  /*
   * The snapshot is refreshed by whoever deploys. Acceptance is a business
   * sign-off. If a future snapshot edit set enforcing/configured to yes, that
   * still must not produce an "Accepted" headline on its own.
   */
  const fabricated = currentStatusMatrix(
    [{ id: "SEC-001", status: { acceptanceVerified: "no" } }],
    [{ id: "SEC-001", states: { implemented: "yes", deployed: "yes", configured: "yes", enforcing: "yes", tested: "yes" } }],
    readiness.deployment,
  );
  assert.equal(fabricated[0].accepted, "no");
  assert.match(fabricated[0].headline.label, /Acceptance Pending/);
});

// ── the five dimensions stay separate ────────────────────────────────

test("five dimensions are reported, never collapsed", () => {
  assert.deepEqual(
    STATUS_DIMENSIONS.map((d) => d.key),
    ["codeComplete", "deployed", "enabled", "tested", "accepted"],
  );
  for (const dimension of STATUS_DIMENSIONS) {
    assert.ok(dimension.detail.length > 20, `${dimension.key} needs an explanation`);
  }
  /*
   * The columns are rendered by mapping STATUS_DIMENSIONS, so the labels do
   * not appear as literals in the page. Asserting the map is what actually
   * guarantees a new dimension becomes a column rather than being forgotten.
   */
  assert.match(page, /STATUS_DIMENSIONS\.map\(/, "columns must come from the shared list");
  assert.match(page, /\{dimension\.label\}/);
});

test("the totals count each dimension separately", () => {
  const totals = currentStatusTotals(matrix);
  assert.equal(totals.total, 9);
  assert.equal(totals.deployed, 9);
  assert.equal(totals.enabled, 0);
  assert.equal(totals.enforcing, 1);
  assert.equal(totals.tested, 9);
  assert.equal(totals.accepted, 0);
});

test("the current table agrees with the deployment snapshot it was derived from", () => {
  for (const status of matrix) {
    assert.equal(status.deployedRevision, readiness.deployment.apiTaskDefinition);
    assert.equal(status.deployedCommit, readiness.deployment.imageCommit);
    assert.equal(status.verifiedAt, readiness.deployment.verifiedAt);
  }
});
