/*
 * Current QA assessment, DERIVED - so that reopening a case never rewrites
 * what was observed on the day it was verified.
 *
 * <h2>The problem this solves</h2>
 *
 * qaSnapshot.json records TC-041 as "passed", closed on 2026-10-06. That
 * verification was real, and its own note is accurate about what it checked:
 * the PRESENTATION. What it did not check, and could not have, is the
 * relevance behaviour, which was never changed. So the page said passed while
 * the investigation said open.
 *
 * The snapshot is not the place to fix that. It is a record of observations on
 * a date, and three tests guard it byte-for-byte on purpose. Editing it would
 * both rewrite history and relax a deliberate control.
 *
 * So this is the same separation PR #122 established for security readiness:
 *
 *   qaSnapshot.json      what was observed, and when. Frozen.
 *   qaOpenFindings.json  what is known NOW. The only mutable half.
 *
 * <h2>The one rule</h2>
 *
 * A finding can only make a case MORE open. There is deliberately no way for
 * this file to mark something passed - that would make the derived view
 * capable of inventing a verification nobody performed, which is the failure
 * it exists to prevent, pointing the other way.
 */

/** A case whose status is "passed" is closed unless a finding reopens it. */
const PASSED = "passed";

/** What a reopened case becomes. "issue" is the recorded vocabulary's "Known issue". */
const REOPENED_STATUS = "issue";

export function openFindingFor(caseId, findings = []) {
  return (findings ?? []).find((finding) => finding?.caseId === caseId) ?? null;
}

/**
 * One case's current status, from its recorded status and any open finding.
 *
 * The recorded status is always carried through unchanged as
 * `recordedStatus`, so a reader can see both what was observed and what is
 * known now - and so that "it used to pass" never quietly disappears.
 */
export function currentQaStatusFor(recordedCase, findings = []) {
  const recordedStatus = recordedCase?.status ?? "notTested";
  const finding = openFindingFor(recordedCase?.id, findings);

  if (!finding) {
    return {
      ...recordedCase,
      recordedStatus,
      currentStatus: recordedStatus,
      reopened: false,
      finding: null,
    };
  }

  // Only ever more open. A finding cannot promote anything.
  const currentStatus =
    recordedStatus === PASSED ? REOPENED_STATUS : recordedStatus;

  return {
    ...recordedCase,
    // `status` is deliberately overwritten with the CURRENT value so every
    // existing helper - summary, attentionCases, applyFilters, testerStatus -
    // reads the current assessment without being changed. The recorded value
    // is not lost; it moves to recordedStatus, which is what keeps this a
    // derivation rather than an edit.
    status: currentStatus,
    recordedStatus,
    currentStatus,
    reopened: recordedStatus === PASSED,
    finding,
  };
}

/** Every case, with its current assessment applied. Order preserved. */
export function currentQaCases(cases = [], findings = []) {
  return (cases ?? []).map((item) => currentQaStatusFor(item, findings));
}

/**
 * Findings whose case is not in the recorded set at all.
 *
 * Reported rather than ignored: a finding against a case id that does not
 * exist is a typo, and silently dropping it would mean the page shows
 * everything as fine while somebody believes they recorded a problem.
 */
export function orphanedFindings(cases = [], findings = []) {
  const known = new Set((cases ?? []).map((item) => item?.id));
  return (findings ?? []).filter((finding) => !known.has(finding?.caseId));
}

/**
 * What to tell a tester about a reopened case.
 *
 * Deliberately plain. A tester needs to know that part of this was checked
 * and holds, that part of it is still wrong, and that the difference is not
 * their misreading.
 */
export function reopenedNotice(finding) {
  if (!finding) {
    return null;
  }
  return {
    headline: finding.headline,
    verified: finding.whatWasVerified,
    open: finding.whatRemainsOpen,
    evidence: finding.evidence,
    nextStep: finding.whatWouldCloseIt,
    openedOn: finding.openedOn,
  };
}

/** Counts that distinguish "never passed" from "passed, then reopened". */
export function currentQaTotals(derived = []) {
  const list = derived ?? [];
  return {
    total: list.length,
    currentlyPassed: list.filter((item) => item.currentStatus === PASSED).length,
    reopened: list.filter((item) => item.reopened).length,
    open: list.filter((item) => item.currentStatus !== PASSED).length,
  };
}
