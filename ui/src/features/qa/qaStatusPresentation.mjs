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
