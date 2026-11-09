/*
 * Presentation helpers for the Security Readiness dashboard.
 *
 * These shape recorded evidence for display. None of them decides whether
 * something is ready: every state comes from securityReadiness.json, which
 * records what was measured and when. A helper that inferred a state - "it is
 * merged, so it must be deployed" - would be the exact confusion this screen
 * exists to remove.
 */

/** The seven states, in the order a reader should follow them. */
export const READINESS_STAGES = [
  "implemented",
  "merged",
  "deployed",
  "migrationApplied",
  "configured",
  "tested",
  "enforcing",
];

/**
 * Tone for a state value.
 *
 * "no" is NOT an error. Most of these are deliberately no - enforcement is off
 * on purpose - so rendering them red would make a correct, intended state look
 * like a fault and train readers to ignore the colour.
 */
export function stateTone(value) {
  switch (value) {
    case "yes":
      return "complete";
    case "partial":
      return "partial";
    case "no":
      return "outstanding";
    case "n/a":
      return "notApplicable";
    default:
      return "unknown";
  }
}

export function stateLabel(value) {
  switch (value) {
    case "yes":
      return "Yes";
    case "partial":
      return "Partly";
    case "no":
      return "No";
    case "n/a":
      return "Not applicable";
    default:
      return "Unknown";
  }
}

/**
 * A one-line answer to "is this working for users yet", in business terms.
 *
 * Deliberately conservative: anything short of enforcing says so plainly.
 * The temptation on a readiness dashboard is to call "built and tested"
 * finished, which is how a stakeholder ends up believing a control is
 * protecting records when it is switched off.
 */
export function readinessSummary(capability) {
  const states = capability?.states ?? {};
  if (states.enforcing === "yes") {
    return { tone: "complete", text: "In force now" };
  }
  if (states.implemented === "n/a") {
    return { tone: "notApplicable", text: "Not applicable - see explanation" };
  }
  if (states.deployed === "yes" && states.configured === "no") {
    return { tone: "partial", text: "Deployed but switched off" };
  }
  if (states.implemented === "yes" || states.implemented === "partial") {
    if (states.merged === "no") {
      return { tone: "outstanding", text: "Built, awaiting review and release" };
    }
    return { tone: "outstanding", text: "Built, not yet in the deployed version" };
  }
  return { tone: "unknown", text: "Not started" };
}

/** Capabilities with an outstanding business decision, in file order. */
export function awaitingBusinessApproval(capabilities) {
  return (capabilities ?? []).filter((c) => Boolean(c.pendingApproval));
}

/** Totals for the summary line. Counts capabilities, never test cases. */
export function readinessTotals(capabilities) {
  const list = capabilities ?? [];
  const counted = list.filter((c) => c.states?.implemented !== "n/a");
  return {
    total: counted.length,
    enforcing: counted.filter((c) => c.states?.enforcing === "yes").length,
    deployed: counted.filter((c) => c.states?.deployed === "yes").length,
    awaitingApproval: awaitingBusinessApproval(list).length,
  };
}

/**
 * How to describe the age of the evidence.
 *
 * Always "Last verified", never "Live". The page cannot refresh any of this at
 * load time - it is a recorded measurement - and a dashboard that implied
 * otherwise would be worse than one that said nothing.
 */
export function verificationNotice(readiness, today) {
  const verifiedAt = readiness?.verifiedAt ?? null;
  if (!verifiedAt) {
    return { label: "Not verified", detail: "No verification date recorded.", stale: true };
  }
  const days = daysBetween(verifiedAt, today);
  return {
    label: `Last verified ${verifiedAt}`,
    detail:
      days === null
        ? "Recorded measurement, not a live check."
        : `Recorded ${describeAge(days)} by ${readiness.verifiedBy ?? "inspection"}. Not a live check.`,
    stale: days !== null && days > 14,
    days,
  };
}

function describeAge(days) {
  if (days <= 0) return "today";
  if (days === 1) return "yesterday";
  if (days < 14) return `${days} days ago`;
  return `${Math.floor(days / 7)} weeks ago`;
}

export function daysBetween(from, to) {
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return null;
  }
  return Math.round((end - start) / 86_400_000);
}

/** The deployed-version line, assembled from recorded facts only. */
export function deploymentSummary(deployment) {
  if (!deployment) {
    return null;
  }
  return {
    environment: deployment.environment,
    revision: deployment.apiTaskDefinition,
    commit: deployment.imageCommit,
    commitDate: deployment.imageCommitDate,
    digest: deployment.imageDigest,
    shortDigest: shortenDigest(deployment.imageDigest),
    behind: deployment.commitsBehindMain ?? 0,
    isCurrent: (deployment.commitsBehindMain ?? 0) === 0,
    verifiedAt: deployment.verifiedAt,
  };
}

export function shortenDigest(digest) {
  if (typeof digest !== "string" || !digest.includes(":")) {
    return digest ?? "";
  }
  const [algorithm, hex] = digest.split(":");
  return `${algorithm}:${hex.slice(0, 12)}`;
}

/**
 * Whether the grant figures may be presented as current.
 *
 * They may not: the read-only role used on 2026-11-09 cannot see the
 * authorization schema. The page must show the older date rather than imply
 * the numbers were re-checked.
 */
export function grantFigures(database) {
  const verified = database?.grantCountsVerified === true;
  const last = database?.lastKnownGrants ?? null;
  return {
    verified,
    asOf: last?.asOf ?? null,
    administrators: last?.administrators ?? null,
    identityLinks: last?.identityLinks ?? null,
    activeGrants: last?.activeGrants ?? null,
    caveat: verified ? null : database?.grantCountsNote ?? null,
  };
}
