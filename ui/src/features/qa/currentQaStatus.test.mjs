/*
 * The separation this file exists to hold.
 *
 * qaSnapshot.json records TC-041 as "passed", closed 2026-10-06. That
 * verification was real and accurate about what it checked - the
 * presentation. The relevance behaviour was never changed, so the page said
 * passed while the investigation said open.
 *
 * The snapshot is not where that gets fixed: it records observations on a
 * date, and three tests guard it byte-for-byte on purpose. So current
 * assessment lives in its own file and the view is derived - the same
 * separation PR #122 established for security readiness.
 */

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import snapshot from "./qaSnapshot.json" with { type: "json" };
import openFindings from "./qaOpenFindings.json" with { type: "json" };
import {
  currentQaCases,
  currentQaStatusFor,
  currentQaTotals,
  openFindingFor,
  orphanedFindings,
  reopenedNotice,
} from "./currentQaStatus.mjs";

const cases = snapshot.cases;
const findings = openFindings.findings;
const derived = currentQaCases(cases, findings);
const find = (id) => derived.find((item) => item.id === id);

// ── history is not rewritten ─────────────────────────────────────────

test("the recorded snapshot is not touched by any of this", () => {
  /*
   * The whole point. Three other tests already assert the file is
   * byte-for-byte unchanged; this asserts the DERIVATION does not mutate the
   * objects it reads, which those tests cannot see.
   */
  const before = JSON.stringify(snapshot.cases);
  currentQaCases(cases, findings);
  assert.equal(JSON.stringify(snapshot.cases), before);
});

test("a reopened case still reports what was recorded", () => {
  const item = find("TC-041");
  assert.equal(item.recordedStatus, "passed", "history survives");
  assert.equal(item.currentStatus, "issue", "and the current view disagrees");
  assert.equal(item.reopened, true);
});

test("the recorded note and history are carried through unchanged", () => {
  const recorded = cases.find((c) => c.id === "TC-041");
  const item = find("TC-041");
  assert.equal(item.note, recorded.note);
  assert.deepEqual(item.history, recorded.history);
  assert.deepEqual(item.progress, recorded.progress);
});

// ── TC-040 is untouched ──────────────────────────────────────────────

test("TC-040 is unaffected, because nothing was found against it", () => {
  const item = find("TC-040");
  assert.equal(item.recordedStatus, "passed");
  assert.equal(item.currentStatus, "passed");
  assert.equal(item.reopened, false);
  assert.equal(item.finding, null);
});

test("every case without a finding passes through exactly as recorded", () => {
  for (const item of derived) {
    if (item.finding === null) {
      assert.equal(item.currentStatus, item.recordedStatus, item.id);
      assert.equal(item.reopened, false, item.id);
    }
  }
});

// ── a finding can only ever make a case MORE open ───────────────────

test("a finding cannot mark anything passed", () => {
  /*
   * The mirror of currentSecurityStatus.mjs's rule that nothing there can
   * mark a requirement accepted. If this file could promote a case, the
   * derived view would be able to invent a verification nobody performed -
   * the same failure, pointing the other way.
   */
  const fabricated = currentQaStatusFor(
    { id: "TC-999", status: "issue" },
    [{ caseId: "TC-999", headline: "all fine now" }],
  );

  assert.equal(fabricated.currentStatus, "issue");
  assert.equal(fabricated.reopened, false, "it was never passed, so nothing reopened");
});

test("a finding against a blocked or decision case leaves the status alone", () => {
  for (const status of ["blocked", "decision", "evidence", "notTested"]) {
    const item = currentQaStatusFor({ id: "X", status }, [{ caseId: "X" }]);
    assert.equal(item.currentStatus, status, status);
    assert.equal(item.reopened, false, status);
  }
});

// ── the finding itself ───────────────────────────────────────────────

test("the finding separates what was verified from what is still open", () => {
  const finding = openFindingFor("TC-041", findings);
  assert.ok(finding);
  assert.match(finding.whatWasVerified, /presentation/i);
  assert.match(finding.whatRemainsOpen, /five nearest/i);
  assert.ok(finding.evidence.length > 80, "a finding needs evidence, not an opinion");
  assert.ok(finding.whatWouldCloseIt.length > 20);
});

test("the notice a tester sees names both halves and the next step", () => {
  const notice = reopenedNotice(openFindingFor("TC-041", findings));
  assert.ok(notice.headline);
  assert.ok(notice.verified);
  assert.ok(notice.open);
  assert.ok(notice.nextStep);
  assert.equal(notice.openedOn, "2026-11-10");
});

test("no notice when there is nothing to report", () => {
  assert.equal(reopenedNotice(null), null);
});

test("the finding carries no engineering detail a tester should not see", () => {
  /*
   * The QA Testing page is for BU testers. A commit SHA or a revision number
   * arriving inside a finding would undo the separation the redesign made.
   */
  const text = Object.values(openFindingFor("TC-041", findings)).join(" ");
  for (const forbidden of [
    /\bECS rev\b/i,
    /\brevision \d+/i,
    /\bsha256:/i,
    /\bAmplify job\b/i,
    /\b[0-9a-f]{7,40}\b/,
    /\bPR #\d+/,
    /pgvector/i,
    /Bedrock/i,
  ]) {
    assert.ok(!forbidden.test(text), `${forbidden} appears in the finding`);
  }
});

// ── a typo cannot hide a problem ────────────────────────────────────

test("a finding against an unknown case is reported, not dropped", () => {
  const orphans = orphanedFindings(cases, [
    ...findings,
    { caseId: "TC-999", headline: "typo" },
  ]);
  assert.deepEqual(orphans.map((f) => f.caseId), ["TC-999"]);
});

test("the committed findings all name a real case", () => {
  assert.deepEqual(orphanedFindings(cases, findings), []);
});

// ── counts distinguish never-passed from passed-then-reopened ───────

test("the totals separate reopened from never passed", () => {
  const totals = currentQaTotals(derived);
  assert.equal(totals.total, cases.length);
  assert.equal(totals.reopened, 1, "TC-041");
  assert.equal(
    totals.currentlyPassed + totals.open,
    totals.total,
    "every case is either currently passed or open",
  );
  assert.ok(
    totals.currentlyPassed < cases.filter((c) => c.status === "passed").length,
    "fewer pass now than were recorded as passing, which is the finding",
  );
});

// ── wired in, not dead code ─────────────────────────────────────────

test("the QA Testing page uses the derived cases, not the raw snapshot", () => {
  const page = readFileSync(
    fileURLToPath(new URL("../../pages/QaTestingPage.tsx", import.meta.url)),
    "utf8",
  ).replace(/\/\*[\s\S]*?\*\//g, " ").replace(/^\s*\/\/.*$/gm, " ");

  assert.match(page, /currentQaCases\(/, "the page must derive its cases");
  assert.match(page, /reopenedNotice\(/, "and show the reopened notice");
  assert.ok(
    !/const cases = snapshot\.cases as unknown as QaCase\[\];\s*$/m.test(page),
    "the raw snapshot must not be what the page lists",
  );
});
