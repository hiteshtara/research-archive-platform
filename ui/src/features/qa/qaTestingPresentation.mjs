/*
 * Presentation helpers for the QA Testing page.
 *
 * The audience is a BU tester, not an engineer. That changes two things.
 *
 * First, what is shown. The recorded `environment` string on each case is an
 * engineering note - "dev: UI Amplify job #92 @ fd2b2ee; API ECS rev 69 digest
 * 0ba53c5c". A tester needs the two facts inside it, the date and which site it
 * was, and none of the rest. {@link testerEnvironment} extracts exactly those,
 * and the raw string stays in the data for the readiness page.
 *
 * Second, the order. The old page led with security engineering and buried the
 * open cases in a list of 51. A tester arriving to do work needs the seven that
 * are not finished, and the two that need somebody else in the room.
 *
 * Nothing here changes an outcome. Every count, status, date and note is read
 * from qaSnapshot.json exactly as recorded.
 */

import { STATUS_META, statusMeta } from "./qaStatusPresentation.mjs";

/** Statuses a tester can still do something about, worst first. */
const ATTENTION_ORDER = ["issue", "blocked", "decision", "evidence", "notTested"];

/**
 * Plain-English status text, written for someone who has not read the
 * engineering notes. Keyed off the same STATUS_META so a new status cannot
 * appear on one page and not the other.
 */
export const TESTER_STATUS_TEXT = {
  passed: {
    headline: "Passed",
    plain: "Someone ran this and it worked. Nothing to do unless you see it behaving differently.",
  },
  evidence: {
    headline: "Passed, more checks needed",
    plain:
      "It worked, but not everywhere it needs to. Usually it still needs trying on a real phone or with a screen reader.",
  },
  issue: {
    headline: "Known issue",
    plain: "It did not work. The case says what happens instead - no need to report it again.",
  },
  decision: {
    headline: "Decision needed",
    plain:
      "Nobody has agreed what should happen yet, so there is nothing to pass or fail. Your opinion on what it ought to do is the useful thing here.",
  },
  blocked: {
    headline: "Blocked",
    plain:
      "It cannot be tested yet, usually because it needs an account or a permission nobody testing has.",
  },
  notTested: {
    headline: "Not tested",
    plain: "In scope, but nobody has run it yet. Good one to pick up.",
  },
};

export function testerStatus(key) {
  const meta = statusMeta(key);
  const text = TESTER_STATUS_TEXT[key] ?? {
    headline: meta.label,
    plain: meta.description,
  };
  return { key: meta.key, chipColor: meta.chipColor, ...text };
}

/** Every status, in the order a tester should read them. */
export function testerStatusGuide() {
  return STATUS_META.map((status) => testerStatus(status.key));
}

/**
 * The cases that are not finished, worst first.
 *
 * This is the page's reason to exist. "44 of 51 passed" is reassuring and
 * useless; "these seven need you" is the thing a tester acts on.
 */
export function attentionCases(cases) {
  return (cases ?? [])
    .filter((item) => item.status !== "passed")
    .slice()
    .sort((a, b) => {
      const byStatus = ATTENTION_ORDER.indexOf(a.status) - ATTENTION_ORDER.indexOf(b.status);
      return byStatus !== 0 ? byStatus : a.id.localeCompare(b.id);
    });
}

/**
 * Cases that cannot be done alone.
 *
 * Two different reasons, deliberately collected into one list: a case may need
 * a permission the tester does not have, or it may need several people at once.
 * Either way the answer for an individual tester is "not on your own", which is
 * what they need to know before they try and report a false failure.
 */
export function coordinatedCases(cases) {
  return (cases ?? []).filter((item) => needsCoordination(item));
}

export function needsCoordination(item) {
  if (!item) return false;
  if (item.status === "blocked") return true;
  const note = `${item.note ?? ""} ${item.steps ?? ""}`.toLowerCase();
  return (
    note.includes("permission") ||
    note.includes("coordinated") ||
    note.includes("another account") ||
    note.includes("without archiveattachmentviewer")
  );
}

/**
 * The date and site a case was tested on - and nothing else from the recorded
 * environment string.
 *
 * `scope` is already plain English ("Development website"). The date has to be
 * recovered from the environment note, which is where it was written down.
 * Returning null rather than guessing is deliberate: an undated result should
 * look undated.
 */
export function testerEnvironment(item) {
  return {
    site: item?.scope ?? null,
    testedOn: extractTestedOn(item?.environment),
  };
}

export function extractTestedOn(environment) {
  if (typeof environment !== "string") return null;
  const match = environment.match(/\b(\d{4}-\d{2}-\d{2})\b/);
  return match ? match[1] : null;
}

/** Distinct modules, for the filter. */
export function moduleOptions(cases) {
  return [...new Set((cases ?? []).map((item) => item.category).filter(Boolean))].sort();
}

/**
 * Filter by module, status and case ID.
 *
 * Case ID matching is forgiving on purpose: a tester reading "TC-023" off a
 * sheet should be able to type `23`, `tc23` or `TC-023` and land on it.
 */
export function applyFilters(cases, { caseId = "", status = "all", module = "all" } = {}) {
  const needle = normaliseId(caseId);
  return (cases ?? []).filter((item) => {
    if (status !== "all" && item.status !== status) return false;
    if (module !== "all" && item.category !== module) return false;
    if (needle === "") return true;
    return (
      normaliseId(item.id).includes(needle) ||
      `${item.title ?? ""}`.toLowerCase().includes(caseId.trim().toLowerCase())
    );
  });
}

function normaliseId(value) {
  return `${value ?? ""}`.toLowerCase().replace(/[^a-z0-9]/g, "");
}

/** Counts for the summary line, in the order the page shows them. */
export function summary(cases) {
  const list = cases ?? [];
  const counts = {};
  for (const status of STATUS_META) {
    counts[status.key] = list.filter((item) => item.status === status.key).length;
  }
  return {
    total: list.length,
    counts,
    open: list.filter((item) => item.status !== "passed").length,
    coordinated: coordinatedCases(list).length,
  };
}

/**
 * Does this text carry engineering detail a tester should not be shown?
 *
 * Used by the page's own tests rather than by the page. The QA Testing page
 * renders recorded notes written by engineers, so "we moved the technical
 * sections" is not by itself a guarantee - a commit SHA can arrive inside a
 * case note. This is what makes that guarantee checkable.
 */
export function containsEngineeringDetail(text) {
  if (typeof text !== "string") return false;
  const patterns = [
    /\bECS rev\b/i,
    /\btask definition\b/i,
    /\bAmplify job\b/i,
    /\bdigest\b/i,
    /\bsha256:/i,
    /\bV0\d\d\b/,
    /\bmigration\b/i,
    /*
     * A hex blob, not an English word and not a record number. "defaced" is
     * seven letters all inside a-f, and 01394406 is a real proposal number a
     * tester needs to see - so a SHA is required to carry BOTH a letter and a
     * digit, which neither of those does.
     */
    /\b(?=[0-9a-f]{7,40}\b)(?=[0-9a-f]*[a-f])(?=[0-9a-f]*\d)[0-9a-f]{7,40}\b/,
    /\bimage \d{8}T\d{6}Z/i,
  ];
  return patterns.some((pattern) => pattern.test(text));
}

/**
 * What a tester needs from a case's verification record: the stage it reached,
 * when it was checked, what was observed, and what is still missing.
 *
 * Deliberately NOT `change` or `deployedBuild`. Those carry pull-request
 * numbers, commit SHAs, Amplify job numbers and ECS revisions - true, useful
 * to an engineer, and noise to a tester reading "did this work". They stay in
 * the data and are shown on the readiness page.
 */
/**
 * The same text, minus any sentence that is about a build rather than about
 * the archive.
 *
 * Recorded notes sometimes end "...Recorded while the development website was
 * still serving Amplify job #93 @ 79b792a". That clause is true and belongs in
 * the record; it is also the only part a tester cannot act on. Dropping it
 * SENTENCE BY SENTENCE keeps the finding intact rather than truncating mid
 * thought, and nothing is written back - qaSnapshot.json is untouched and the
 * readiness page still shows the full text.
 */
export function withoutBuildDetail(text) {
  if (typeof text !== "string") return text;
  const sentences = text.split(/(?<=\.)\s+/);
  const kept = sentences.filter((sentence) => !containsEngineeringDetail(sentence));
  // If every sentence mentions a build, the note is entirely about one; say
  // nothing rather than present an empty string as if there were no note.
  return kept.length > 0 ? kept.join(" ").trim() : null;
}

export function testerProgress(item) {
  const progress = item?.progress;
  if (!progress) return null;
  return {
    stage: progress.stage ?? null,
    verifiedOn: progress.verifiedOn ?? null,
    verifiedIn: progress.verifiedIn ?? null,
    requiredEnvironment: progress.requiredEnvironment ?? null,
    results: withoutBuildDetail(progress.results ?? null),
    limitations: withoutBuildDetail(progress.limitations ?? null),
  };
}

/**
 * Earlier findings, oldest first, with the engineering environment string
 * dropped and its date kept.
 */
export function testerHistory(item) {
  return (item?.history ?? []).map((entry) => ({
    on: entry.on ?? null,
    stage: entry.stage ?? null,
    summary: withoutBuildDetail(entry.summary ?? null),
  }));
}
