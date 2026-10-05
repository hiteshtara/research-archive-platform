// QA status page logic - kept as pure functions over plain data (not
// JSX) so it can be unit-tested the same way every other
// presentation-helper module in this project is, since there is no
// component-render test setup. QaStatusPage.tsx imports the JSON and
// passes it in; nothing here reads a file or touches the network.
//
// Every total shown on the page comes from one of these functions, so a
// count can never be maintained by hand alongside the cases and drift
// from them.

/*
 * The six outcomes a case can have. They are deliberately distinct:
 * "passed" and "passedWithGaps" both mean the check succeeded, but the
 * second one has evidence still outstanding, and collapsing the two
 * would overstate what has been proven. "notTested" exists so a case
 * added to the checklist later cannot default to looking like a pass.
 */
export const STATUS_META = [
  {
    key: "passed",
    label: "Passed",
    chipColor: "success",
    description: "Checked against the environment named on the case, and behaved as expected.",
  },
  {
    key: "evidence",
    label: "Passed, more checks needed",
    chipColor: "warning",
    description:
      "Behaved as expected, but some evidence is still missing - usually a real handset or a screen reader.",
  },
  {
    key: "issue",
    label: "Known issue",
    chipColor: "error",
    description: "Did not behave as expected. The case names what happens instead.",
  },
  {
    key: "decision",
    label: "Decision needed",
    chipColor: "info",
    description:
      "What should happen has not been agreed yet, so there is nothing to pass or fail against.",
  },
  {
    key: "blocked",
    label: "Blocked",
    chipColor: "warning",
    description: "Cannot be tested yet - usually because it needs an account nobody has signed in with.",
  },
  {
    key: "notTested",
    label: "Not tested",
    chipColor: "default",
    description: "In scope, but no one has run it yet.",
  },
];

const STATUS_KEYS = STATUS_META.map((status) => status.key);

export function statusMeta(key) {
  return STATUS_META.find((status) => status.key === key) ?? null;
}

export function isKnownStatus(key) {
  return STATUS_KEYS.includes(key);
}

/*
 * Counts for every status, including the ones with no cases, so the
 * filter buttons can show a real zero rather than disappearing.
 */
export function countByStatus(cases) {
  const counts = {};
  for (const key of STATUS_KEYS) {
    counts[key] = 0;
  }
  for (const item of cases) {
    if (!(item.status in counts)) {
      throw new Error(`unknown case status "${item.status}" on ${item.id}`);
    }
    counts[item.status] += 1;
  }
  return counts;
}

/*
 * Cases whose outcome still needs somebody to do something - the
 * number worth leading with, as distinct from "not passed".
 */
export function openCaseCount(cases) {
  const counts = countByStatus(cases);
  return counts.issue + counts.decision + counts.blocked + counts.notTested;
}

export function caseAreas(cases) {
  return [...new Set(cases.map((item) => item.category))].sort((left, right) =>
    left.localeCompare(right),
  );
}

/*
 * Area, status and free text all narrow together. The text is matched
 * against the case id, title, area and status note - what a tester
 * would recognise - and never against the environment string, so
 * typing "dev" does not return almost everything.
 */
export function filterCases(cases, { search = "", status = "all", area = "all" } = {}) {
  const needle = search.trim().toLowerCase();

  return cases.filter((item) => {
    if (status !== "all" && item.status !== status) {
      return false;
    }
    if (area !== "all" && item.category !== area) {
      return false;
    }
    if (needle === "") {
      return true;
    }
    return [item.id, item.title, item.category, item.note]
      .join(" ")
      .toLowerCase()
      .includes(needle);
  });
}

export function resultsLabel(visibleCount, totalCount) {
  if (visibleCount === totalCount) {
    return `Showing all ${totalCount} cases`;
  }
  return `Showing ${visibleCount} of ${totalCount} cases`;
}

/*
 * Cases proven against the deployed website, as opposed to a local run
 * or a candidate build. A fix that exists only on a branch must never
 * read as proven here, which is why scope is carried per case.
 */
export function deployedEvidenceCount(cases) {
  return cases.filter((item) => item.scope === "Development website").length;
}

export function scopesPresent(cases) {
  return [...new Set(cases.map((item) => item.scope))].sort((left, right) =>
    left.localeCompare(right),
  );
}

/*
 * The security requirements are a separate acceptance plan: they are
 * summarised on their own and never folded into the checklist totals,
 * so the page cannot imply seven extra passes.
 */
export function securitySummary(requirements) {
  const verified = requirements.filter(
    (requirement) => requirement.verification === "Verified",
  ).length;
  const conflicts = requirements.filter((requirement) => requirement.conflict).length;

  return {
    total: requirements.length,
    verified,
    conflicts,
    headline: `${verified} of ${requirements.length} requirements verified`,
  };
}

export function requirementsWithConflicts(requirements) {
  return requirements.filter((requirement) => requirement.conflict);
}

/*
 * How far a fix has travelled, for a case somebody is actively working.
 * Deliberately four distinct stages: code existing, code being
 * released, and behaviour being proven on the target environment are
 * three different facts, and collapsing them is how a local fix starts
 * reading as a pass.
 *
 * A case only reaches "verified" - and may only then be marked passed -
 * once its acceptance criteria have been checked in the environment the
 * case requires, with the date, environment and result recorded.
 */
export const PROGRESS_STAGES = [
  {
    key: "inProgress",
    label: "In progress",
    description: "Being worked on. The behaviour below is still what the site does.",
  },
  {
    key: "fixedInCode",
    label: "Fixed in code, not released",
    description:
      "A fix exists and its tests pass, but it is not on this website yet, so the behaviour below is still what you will see.",
  },
  {
    key: "deployedAwaitingVerification",
    label: "Released, awaiting verification",
    description:
      "The fix is on this website but nobody has re-run the case yet. Treat it as unproven until it has been.",
  },
  {
    key: "verified",
    label: "Verified",
    description: "Re-run after release and behaved as the case requires.",
  },
];

const PROGRESS_KEYS = PROGRESS_STAGES.map((stage) => stage.key);

export function progressStage(key) {
  return PROGRESS_STAGES.find((stage) => stage.key === key) ?? null;
}

export function isKnownProgressStage(key) {
  return PROGRESS_KEYS.includes(key);
}

/*
 * Evidence that is really an empty box. A field filled in with "TBD" or
 * "Not deployed" is not weaker evidence than a blank one - it is the
 * same absence wearing a word, and both must fail the same way.
 */
const PLACEHOLDER_EVIDENCE = new Set([
  "",
  "-",
  "--",
  "n/a",
  "na",
  "none",
  "tbd",
  "tba",
  "todo",
  "pending",
  "unknown",
  "not recorded",
  "not deployed",
  "not released",
  "not yet",
  "not yet verified",
  "not verified",
  "awaiting",
  "awaiting deployment",
  "?",
]);

export function isPlaceholderEvidence(value) {
  if (value === null || value === undefined) {
    return true;
  }
  return PLACEHOLDER_EVIDENCE.has(String(value).trim().toLowerCase());
}

/*
 * A real calendar date, not merely digits in the right shape. A regex
 * alone accepts 2026-02-30 and 2026-99-99, which would let a verified
 * claim carry a date nobody could have tested on - the sort of value a
 * find-and-replace or a careless paste leaves behind.
 */
export function isRealIsoDate(value) {
  const text = String(value ?? "").trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) {
    return false;
  }
  const [year, month, day] = text.split("-").map(Number);
  if (month < 1 || month > 12 || day < 1 || day > 31) {
    return false;
  }
  // Round-tripping through UTC catches a day that overflowed into the
  // next month, including 29 February outside a leap year.
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return (
    parsed.getUTCFullYear() === year &&
    parsed.getUTCMonth() === month - 1 &&
    parsed.getUTCDate() === day
  );
}

/*
 * The environment a case must be proven in before it counts. Recorded
 * per case because it is a property of the case, not of whoever happens
 * to be testing: a fix to a deployed search has to be re-run on the
 * deployed site, and a local run cannot stand in for it however green.
 */
export function requiredEnvironment(item) {
  return item.progress?.requiredEnvironment ?? "Development website";
}

/*
 * Everything "verified" has to be able to show. Returns the reasons a
 * claim fails, so a test can say which part is missing rather than only
 * that something is.
 */
export function verificationShortfalls(item) {
  const progress = item.progress;
  if (!progress || progress.stage !== "verified") {
    return [];
  }

  const reasons = [];
  if (isPlaceholderEvidence(progress.deployedBuild)) {
    reasons.push("no released build recorded");
  }
  if (isPlaceholderEvidence(progress.verifiedOn)) {
    reasons.push("no verification date recorded");
  } else if (!isRealIsoDate(progress.verifiedOn)) {
    reasons.push("verification date is not a real date");
  }
  if (isPlaceholderEvidence(progress.verifiedIn)) {
    reasons.push("no verification environment recorded");
  } else if (
    String(progress.verifiedIn).trim().toLowerCase() !==
    String(requiredEnvironment(item)).trim().toLowerCase()
  ) {
    reasons.push(
      `verified in "${progress.verifiedIn}" but this case requires "${requiredEnvironment(item)}"`,
    );
  }
  if (isPlaceholderEvidence(progress.results)) {
    reasons.push("no result recorded");
  }
  return reasons;
}

/*
 * The guard behind "mark PASS only after verification". Applies to a
 * qualified pass as well as an outright one: "passed, more checks
 * needed" is still a pass to a reader, so a tracked fix may not claim
 * it before it is verified either.
 */
const PASS_LIKE_STATUSES = ["passed", "evidence"];

export function casesClaimingAnUnverifiedPass(cases) {
  return cases.filter(
    (item) =>
      item.progress &&
      PASS_LIKE_STATUSES.includes(item.status) &&
      item.progress.stage !== "verified",
  );
}

/*
 * A verified stage that cannot show its evidence. Covers a blank field,
 * a placeholder standing in for one, and - the case that matters most
 * here - evidence from somewhere other than the environment the case
 * requires.
 */
export function casesWithUnevidencedVerification(cases) {
  return cases.filter((item) => verificationShortfalls(item).length > 0);
}

export function trackedCases(cases) {
  return cases.filter((item) => item.progress);
}
