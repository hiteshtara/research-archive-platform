/*
 * The QA Testing page, and the promise it makes.
 *
 * Two things are being protected here. The recorded outcomes must survive the
 * redesign untouched - a page that reorganises test results is one edit away
 * from improving them. And the tester-facing text must not carry engineering
 * detail, which cannot be guaranteed by "we moved the technical sections",
 * because the case notes themselves were written by engineers and some of them
 * end with an Amplify job number.
 */

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import snapshot from "./qaSnapshot.json" with { type: "json" };
import {
  applyFilters,
  attentionCases,
  containsEngineeringDetail,
  coordinatedCases,
  extractTestedOn,
  moduleOptions,
  needsCoordination,
  summary,
  testerEnvironment,
  testerHistory,
  testerProgress,
  testerStatus,
  testerStatusGuide,
  withoutBuildDetail,
} from "./qaTestingPresentation.mjs";

const read = (relative) => readFileSync(fileURLToPath(new URL(relative, import.meta.url)), "utf8");

/**
 * Source with comments removed.
 *
 * These tests ask what the page SHOWS. A JSDoc block explaining why the page
 * does not claim to be administrator-only contains the words "administrator-
 * only", and a naive scan reads the explanation as the claim.
 */
const readRendered = (relative) =>
  read(relative)
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/^\s*\/\/.*$/gm, " ");
const cases = snapshot.cases;

// ── the counts the business asked to see ─────────────────────────────

test("the five headline counts are exactly what was recorded", () => {
  const totals = summary(cases);

  assert.equal(totals.total, 51);
  assert.equal(totals.counts.passed, 44);
  assert.equal(totals.counts.decision, 3);
  assert.equal(totals.counts.blocked, 2);
  assert.equal(totals.counts.evidence, 2);
  assert.equal(
    totals.counts.passed + totals.counts.decision + totals.counts.blocked + totals.counts.evidence,
    51,
    "the four statuses must account for every case, or one is being hidden",
  );
  assert.equal(totals.open, 7);
});

test("the recorded snapshot is byte-for-byte unchanged by the redesign", () => {
  /*
   * The whole point of the instruction. A redesign that reworded a finding or
   * nudged a status would be indistinguishable from a redesign that did not,
   * unless something pins the bytes.
   */
  const digest = createHash("sha256").update(read("./qaSnapshot.json")).digest("hex");
  assert.equal(
    digest,
    "86f94d8fa3ec43166fb827668d16883da9d278412b4e53a71f9a2f7025751ef0",
    "qaSnapshot.json changed - recorded QA outcomes must not be rewritten",
  );
});

test("every case keeps its id, status and the site it was tested on", () => {
  for (const item of cases) {
    assert.match(item.id, /^TC-\d{3}$/);
    assert.ok(item.status, `${item.id} lost its status`);
    assert.ok(testerEnvironment(item).site, `${item.id} lost the site it was tested on`);
  }
});

test("the eight cases with no recorded date are shown as undated, not guessed", () => {
  /*
   * Eight environment notes never carried a date. The page says "date not
   * recorded" for those rather than inventing one or borrowing the snapshot's
   * evidenceUpdated, which would read as a test that happened on a day it did
   * not. Pinned so that a data change makes the number move visibly.
   */
  const undated = cases.filter((item) => testerEnvironment(item).testedOn === null);

  assert.deepEqual(
    undated.map((item) => item.id),
    ["TC-019", "TC-020", "TC-023", "TC-027", "TC-029", "TC-033", "TC-042", "TC-045"],
  );
  assert.match(readRendered("../../pages/QaTestingPage.tsx"), /date not recorded/);
});

// ── what a tester is shown ───────────────────────────────────────────

test("no tester-facing text carries engineering detail", () => {
  /*
   * Scans everything the QA Testing page can render. The three history
   * summaries that used to end "...still serving Amplify job #93 @ 79b792a"
   * are the reason withoutBuildDetail exists.
   */
  const leaks = [];
  for (const item of cases) {
    for (const field of ["title", "steps", "expected", "note"]) {
      if (containsEngineeringDetail(item[field])) leaks.push(`${item.id}.${field}`);
    }
    const progress = testerProgress(item);
    if (progress) {
      for (const [field, value] of Object.entries(progress)) {
        if (containsEngineeringDetail(value)) leaks.push(`${item.id}.progress.${field}`);
      }
    }
    for (const entry of testerHistory(item)) {
      for (const [field, value] of Object.entries(entry)) {
        if (containsEngineeringDetail(value)) leaks.push(`${item.id}.history.${field}`);
      }
    }
  }
  assert.deepEqual(leaks, [], "these would show commit SHAs or build numbers to a tester");
});

test("the engineering detector tells a SHA apart from a record number", () => {
  // A false positive here would hide a proposal number a tester needs.
  assert.equal(containsEngineeringDetail("fd2b2ee"), true);
  assert.equal(containsEngineeringDetail("API ECS rev 69"), true);
  assert.equal(containsEngineeringDetail("Amplify job #92"), true);
  assert.equal(containsEngineeringDetail("V088 migration"), true);

  assert.equal(containsEngineeringDetail("proposal 01394406"), false);
  assert.equal(containsEngineeringDetail("Document 1000570"), false);
  assert.equal(containsEngineeringDetail("defaced"), false);
});

test("withoutBuildDetail drops the build sentence and keeps the finding", () => {
  const note =
    "Decision taken: keep the behaviour, correct the guidance. " +
    "Recorded while the development website was still serving Amplify job #93 @ 79b792a.";

  const cleaned = withoutBuildDetail(note);
  assert.match(cleaned, /Decision taken: keep the behaviour, correct the guidance\./);
  assert.ok(!cleaned.includes("Amplify"), "the build sentence must be gone");
  assert.equal(containsEngineeringDetail(cleaned), false);
});

test("a note that is entirely about a build becomes nothing, not an empty string", () => {
  assert.equal(withoutBuildDetail("Recorded on Amplify job #93 @ 79b792a."), null);
});

test("the full text survives in the data, so the readiness page can still show it", () => {
  const tc009 = cases.find((item) => item.id === "TC-009");
  const raw = tc009.history.map((entry) => entry.summary).join(" ");
  assert.equal(
    containsEngineeringDetail(raw),
    true,
    "the recorded note keeps its build detail - only the rendering drops it",
  );
});

// ── what needs attention ─────────────────────────────────────────────

test("the open cases are listed worst first, and passed cases are not among them", () => {
  const attention = attentionCases(cases);

  assert.equal(attention.length, 7);
  assert.ok(
    attention.every((item) => item.status !== "passed"),
    "a passed case needs no attention",
  );
  assert.deepEqual(
    attention.map((item) => item.status),
    ["blocked", "blocked", "decision", "decision", "decision", "evidence", "evidence"],
    "blocked first: it is the one a tester cannot simply try again",
  );
});

test("the cases needing a coordinated session are identified", () => {
  const coordinated = coordinatedCases(cases);

  assert.deepEqual(coordinated.map((item) => item.id), ["TC-023", "TC-027"]);
  assert.ok(
    coordinated.every((item) => needsCoordination(item)),
    "the list and the predicate must agree",
  );
});

test("a case needing a permission is flagged even if it is not blocked", () => {
  assert.equal(
    needsCoordination({ status: "passed", note: "Needs an account without that permission." }),
    true,
  );
  assert.equal(needsCoordination({ status: "passed", note: "Worked first time." }), false);
});

// ── filtering ────────────────────────────────────────────────────────

test("a tester can find a case however they type its id", () => {
  for (const typed of ["TC-023", "tc-023", "tc023", "023", "23"]) {
    const found = applyFilters(cases, { caseId: typed });
    assert.ok(
      found.some((item) => item.id === "TC-023"),
      `typing "${typed}" should find TC-023`,
    );
  }
});

test("filtering by title works for someone who remembers the words, not the number", () => {
  const found = applyFilters(cases, { caseId: "attachments" });
  assert.ok(found.length > 0);
  assert.ok(found.every((item) => /attachment/i.test(item.title)));
});

test("status and module filters narrow, and combine", () => {
  const blocked = applyFilters(cases, { status: "blocked" });
  assert.equal(blocked.length, 2);

  const awards = applyFilters(cases, { module: "Awards Search" });
  assert.ok(awards.length > 0);
  assert.ok(awards.every((item) => item.category === "Awards Search"));

  const both = applyFilters(cases, { status: "blocked", module: "Award Detail" });
  assert.deepEqual(both.map((item) => item.id), ["TC-023"]);
});

test("no filters means every case, and an impossible filter means none", () => {
  assert.equal(applyFilters(cases, {}).length, 51);
  assert.equal(applyFilters(cases, { caseId: "TC-999" }).length, 0);
});

test("the module list covers every case", () => {
  const modules = moduleOptions(cases);
  assert.ok(modules.length >= 10);
  for (const item of cases) {
    assert.ok(modules.includes(item.category), `${item.id}'s module is missing from the filter`);
  }
});

// ── plain English ────────────────────────────────────────────────────

test("every status has plain-English text, and none of it is jargon", () => {
  for (const status of testerStatusGuide()) {
    assert.ok(status.headline.length > 0);
    assert.ok(status.plain.length > 30, `${status.key} needs a real explanation`);
    for (const jargon of ["regression", "CI", "enforcement", "task definition", "SHA"]) {
      assert.ok(
        !status.plain.includes(jargon),
        `"${jargon}" in the ${status.key} explanation defeats the point`,
      );
    }
  }
});

test("the four statuses actually present all have text", () => {
  for (const key of ["passed", "decision", "blocked", "evidence"]) {
    assert.ok(testerStatus(key).plain.length > 30, `${key} has no plain explanation`);
  }
});

test("a date is recovered from the recorded environment, or reported as absent", () => {
  assert.equal(extractTestedOn("dev: UI Amplify job #92 @ fd2b2ee, 2026-09-29"), "2026-09-29");
  assert.equal(extractTestedOn("no date here"), null);
  assert.equal(extractTestedOn(null), null);
});

// ── the pages themselves ─────────────────────────────────────────────

test("the tester page shows no deployment, migration or authorization sections", () => {
  const page = readRendered("../../pages/QaTestingPage.tsx");

  for (const banned of [
    "SecurityReadinessSection",
    "securityRequirements.json",
    "securityReadiness.json",
    "apiTaskDefinition",
    "enforcement",
  ]) {
    assert.ok(!page.includes(banned), `the tester page must not render ${banned}`);
  }
});

test("the tester page answers the four questions, in order", () => {
  const page = readRendered("../../pages/QaTestingPage.tsx");
  const order = [
    "still need attention",
    "coordinated session, not an individual attempt",
    "how to report a problem",
    "All {totals.total} test cases",
  ];
  let cursor = -1;
  for (const heading of order) {
    const at = page.toLowerCase().indexOf(heading.toLowerCase());
    assert.ok(at > cursor, `"${heading}" is missing or out of order`);
    cursor = at;
  }
});

test("both pages link to each other, so neither is a dead end", () => {
  const testing = readRendered("../../pages/QaTestingPage.tsx");
  const readinessPage = readRendered("../../pages/SecurityReadinessPage.tsx");

  assert.match(testing, /to="\/qa-status\/readiness"/);
  assert.match(readinessPage, /to="\/qa-status"/);
});

test("the readiness page does not claim to be access-restricted", () => {
  /*
   * It is a separate page, not a protected one: what it renders ships in the
   * JavaScript bundle every signed-in user already downloads. Saying
   * "administrator only" would describe a boundary that is not there.
   */
  const page = readRendered("../../pages/SecurityReadinessPage.tsx");

  assert.match(page, /not restricted|not access-restricted/i);
  assert.ok(
    !/administrator[- ]only/i.test(page),
    "that would be a claim the implementation does not support",
  );
});
