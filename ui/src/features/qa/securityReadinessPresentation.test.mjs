import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import readiness from "./securityReadiness.json" with { type: "json" };
import {
  READINESS_STAGES,
  awaitingBusinessApproval,
  daysBetween,
  deploymentSummary,
  grantFigures,
  readinessSummary,
  readinessTotals,
  shortenDigest,
  stateLabel,
  stateTone,
  verificationNotice,
} from "./securityReadinessPresentation.mjs";

const read = (relative) =>
  readFileSync(fileURLToPath(new URL(relative, import.meta.url)), "utf8");

// ── the data itself ──────────────────────────────────────────────────

test("every capability declares all seven stages", () => {
  for (const capability of readiness.capabilities) {
    for (const stage of READINESS_STAGES) {
      assert.ok(
        capability.states[stage] !== undefined,
        `${capability.id} is missing the ${stage} stage`,
      );
      assert.ok(
        ["yes", "no", "partial", "n/a"].includes(capability.states[stage]),
        `${capability.id}.${stage} is "${capability.states[stage]}"`,
      );
    }
  }
});

test("every capability has a business summary written without jargon", () => {
  for (const capability of readiness.capabilities) {
    assert.ok(capability.businessSummary.length > 40, `${capability.id} summary is too thin`);
    /*
     * The audience is a business reader. A summary that needs the reader to
     * know what a migration or a task definition is has failed at its job -
     * that detail belongs in the evidence section behind the expander.
     */
    for (const jargon of ["RecordAccessEvaluator", "SqlFragment", "task definition", "JDBC"]) {
      assert.ok(
        !capability.businessSummary.includes(jargon),
        `${capability.id} summary leaks "${jargon}" into business-facing copy`,
      );
    }
  }
});

test("nothing claims to be enforcing unless it is deployed", () => {
  // The exact confusion this dashboard exists to prevent.
  for (const capability of readiness.capabilities) {
    if (capability.states.enforcing === "yes") {
      assert.equal(
        capability.states.deployed,
        "yes",
        `${capability.id} claims to be enforcing while not deployed`,
      );
    }
  }
});

test("enforcement is recorded as off, matching the measured task definition", () => {
  assert.equal(readiness.enforcement.state, "off");
  const enforcingCapabilities = readiness.capabilities.filter(
    (c) => c.states.enforcing === "yes",
  );
  /*
   * SEC-009 is the only control in force while the flag is off, because the
   * database role enforces it rather than the application. If another
   * capability ever claims to be enforcing with the flag off, that is either
   * a data error or a genuine surprise - either way it must be looked at.
   */
  assert.deepEqual(
    enforcingCapabilities.map((c) => c.id),
    ["SEC-009"],
  );
});

test("the deployed commit and revision are recorded, not inferred", () => {
  const deployment = deploymentSummary(readiness.deployment);
  assert.equal(deployment.revision, "research-archive-platform-dev-api:85");
  assert.equal(deployment.commit, "c1d25e6");
  assert.equal(deployment.behind, 0);
  assert.equal(deployment.isCurrent, true);
  assert.ok(deployment.verifiedAt);
  /*
   * Pinned deliberately. These three values drifted out of date together on
   * 2026-11-09 and nothing failed; the consistency suite now checks them
   * against every other claim in the file.
   */
});

// ── the helpers ──────────────────────────────────────────────────────

test("stateTone does not paint a deliberate 'no' as an error", () => {
  assert.equal(stateTone("yes"), "complete");
  assert.equal(stateTone("partial"), "partial");
  assert.equal(stateTone("no"), "outstanding");
  assert.equal(stateTone("n/a"), "notApplicable");
  assert.notEqual(stateTone("no"), "error");
});

test("stateLabel reads as plain English", () => {
  assert.equal(stateLabel("yes"), "Yes");
  assert.equal(stateLabel("partial"), "Partly");
  assert.equal(stateLabel("n/a"), "Not applicable");
  assert.equal(stateLabel("something else"), "Unknown");
});

test("readinessSummary never calls built-and-tested finished", () => {
  const builtNotDeployed = {
    states: { implemented: "yes", merged: "no", deployed: "no", configured: "no", enforcing: "no" },
  };
  assert.deepEqual(readinessSummary(builtNotDeployed), {
    tone: "outstanding",
    text: "Built, awaiting review and release",
  });

  const deployedOff = {
    states: { implemented: "yes", merged: "yes", deployed: "yes", configured: "no", enforcing: "no" },
  };
  assert.equal(readinessSummary(deployedOff).text, "Deployed but switched off");

  const live = {
    states: { implemented: "yes", merged: "yes", deployed: "yes", configured: "yes", enforcing: "yes" },
  };
  assert.equal(readinessSummary(live).text, "In force now");
});

test("a not-applicable capability is explained rather than scored", () => {
  const fundCenter = readiness.capabilities.find((c) => c.id === "SEC-005b");
  assert.equal(fundCenter.states.implemented, "n/a");
  assert.equal(readinessSummary(fundCenter).text, "Not applicable - see explanation");
});

test("totals count capabilities and exclude the not-applicable one", () => {
  const totals = readinessTotals(readiness.capabilities);
  assert.equal(totals.total, readiness.capabilities.length - 1);
  assert.equal(totals.enforcing, 1);
  assert.ok(totals.awaitingApproval >= 1);
});

test("verificationNotice always says last verified, never live", () => {
  const notice = verificationNotice({ verifiedAt: "2026-11-09", verifiedBy: "inspection" }, "2026-11-09");
  assert.match(notice.label, /^Last verified /);
  assert.match(notice.detail, /Not a live check/);
  assert.equal(notice.stale, false);
  assert.ok(!/\blive\b/i.test(notice.label));
});

test("evidence older than a fortnight is flagged stale", () => {
  const notice = verificationNotice({ verifiedAt: "2026-10-01" }, "2026-11-09");
  assert.equal(notice.stale, true);
  assert.match(notice.detail, /weeks ago/);
});

test("a missing verification date is not treated as fresh", () => {
  const notice = verificationNotice({}, "2026-11-09");
  assert.equal(notice.stale, true);
  assert.equal(notice.label, "Not verified");
});

test("daysBetween handles the obvious cases and rejects nonsense", () => {
  assert.equal(daysBetween("2026-11-01", "2026-11-09"), 8);
  assert.equal(daysBetween("2026-11-09", "2026-11-09"), 0);
  assert.equal(daysBetween("not a date", "2026-11-09"), null);
});

test("the image digest is shortened for display but kept in full in the data", () => {
  assert.equal(shortenDigest("sha256:c9f3bc99be9a99fb480c577c0d78d82ea7ebdad3"), "sha256:c9f3bc99be9a");
  assert.equal(shortenDigest("not-a-digest"), "not-a-digest");
  assert.ok(readiness.deployment.imageDigest.length > 40, "the full digest must survive in the data");
});

test("grant figures are presented with their real date, not as current", () => {
  const grants = grantFigures(readiness.database);
  assert.equal(grants.verified, false, "they could not be re-measured on 2026-11-09");
  assert.equal(grants.asOf, "2026-11-03");
  assert.equal(grants.activeGrants, 0);
  assert.ok(grants.caveat, "an unverified figure must carry its caveat");
});

test("awaitingBusinessApproval lists only genuine decisions", () => {
  const pending = awaitingBusinessApproval(readiness.capabilities);
  assert.ok(pending.length >= 3);
  for (const capability of pending) {
    assert.ok(capability.pendingApproval.length > 20);
  }
  assert.ok(pending.some((c) => c.id === "SEC-008"), "the audit policy decision is outstanding");
});

// ── the page keeps history, and says what it is ──────────────────────

test("the page renders readiness before the historical QA cases", () => {
  const page = read("../../pages/QaStatusPage.tsx");
  const readinessAt = page.indexOf("<SecurityReadinessSection");
  const historyAt = page.indexOf("Historical QA results");
  assert.ok(readinessAt > 0, "the readiness section must be on the page");
  assert.ok(historyAt > readinessAt, "security readiness comes first");
});

test("historical QA results are still rendered, not replaced", () => {
  const page = read("../../pages/QaStatusPage.tsx");
  assert.ok(page.includes("qaSnapshot.json"), "the recorded cases must still be loaded");
  assert.match(page, /never rewritten|never rewrite|exactly as\s*\n?\s*tested/);
});

test("the recorded QA snapshot is unchanged by this work", () => {
  /*
   * The instruction that matters most on a readiness dashboard: do not
   * rewrite old outcomes to make the screen look green. The case count and
   * the status mix are pinned so a later edit to the snapshot is a visible,
   * deliberate change.
   */
  const snapshot = JSON.parse(read("./qaSnapshot.json"));
  assert.equal(snapshot.cases.length, 51);
  const counts = snapshot.cases.reduce((acc, c) => {
    acc[c.status] = (acc[c.status] ?? 0) + 1;
    return acc;
  }, {});
  assert.deepEqual(counts, { passed: 44, decision: 3, evidence: 2, blocked: 2 });
});
