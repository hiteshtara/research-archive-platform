/*
 * Current security status, DERIVED rather than maintained.
 *
 * <h2>The bug this exists to make impossible</h2>
 *
 * The readiness page had two sources of truth about the same question.
 *
 *   securityReadiness.json    the deployment snapshot, refreshed every release
 *   securityRequirements.json the acceptance plan, written 2026-10-20
 *
 * The second one records what was true when revision 81 was running: ten
 * modules at "merged-not-deployed", all nine requirements at deployed:"no",
 * and evidence strings saying "NOT in the running API (revision 81)". That was
 * accurate then. Revision 87 shipped on 2026-11-10 carrying all of it.
 *
 * The page rendered BOTH, and rendered the second as current status. So it
 * simultaneously said "the running API now carries them" in one block and
 * "merged, not deployed" ten times in the table below it.
 *
 * Correcting the labels by hand would fix today and guarantee a repeat: a
 * frozen flag that has to be remembered is a flag that will be forgotten. So
 * the deployed dimension is no longer read from the acceptance plan at all. It
 * is derived from the deployment snapshot, which is the thing that actually
 * gets verified each release - and the acceptance plan keeps only what it is
 * authoritative about: the business decision and whether acceptance has been
 * signed off.
 *
 * <h2>Who owns which fact</h2>
 *
 * | Dimension        | Source                      | Why |
 * |------------------|-----------------------------|-----|
 * | Code complete    | securityReadiness           | verified against the deployed commit |
 * | Deployed         | securityReadiness           | verified against the running revision |
 * | Enabled          | securityReadiness           | read from the live task definition |
 * | Tested           | securityReadiness           | the suites that actually run |
 * | Business accepted| securityRequirements        | only the business can set this |
 *
 * Nothing here can mark a requirement accepted. That bit has exactly one
 * source and it is not this file.
 */

/** The five dimensions, in the order a reader should follow them. */
export const STATUS_DIMENSIONS = [
  { key: "codeComplete", label: "Code complete", detail: "The rule exists in source at the deployed commit." },
  { key: "deployed", label: "Deployed", detail: "Present in the API revision actually running." },
  { key: "enabled", label: "Enabled", detail: "Switched on in the environment. Deployed is not enabled." },
  { key: "tested", label: "Tested", detail: "Automated coverage exercises it and passes." },
  { key: "accepted", label: "Business accepted", detail: "Checked against its acceptance criteria, with enforcement on." },
];

/**
 * One headline per component, from the five dimensions.
 *
 * The vocabulary is deliberately explicit about the difference between "we
 * have not built it", "we built it and it is off", and "it is running and
 * nobody has signed it off". Collapsing those into one word is what produced a
 * page that read as though nothing had been delivered.
 */
export function headlineFor(status) {
  if (status.codeComplete === "n/a") {
    return { label: "Not applicable", tone: "notApplicable" };
  }
  if (status.deployed !== "yes") {
    return { label: "In source — Not Deployed", tone: "outstanding" };
  }
  if (status.enforcing === "yes") {
    return status.accepted === "yes"
      ? { label: "In force — Accepted", tone: "complete" }
      : { label: "In force — Acceptance Pending", tone: "partial" };
  }
  if (status.enabled !== "yes") {
    return { label: "Deployed — Not Enabled", tone: "partial" };
  }
  return status.accepted === "yes"
    ? { label: "Enabled — Accepted", tone: "complete" }
    : { label: "Deployed — Acceptance Pending", tone: "partial" };
}

/**
 * Compose one requirement's current status.
 *
 * `requirement` supplies acceptance and the business decision. `capability`
 * supplies everything about code and deployment. Where they disagree about
 * the deployed dimension, the capability wins - it is the one that was
 * verified against the running revision, and the disagreement is the bug.
 */
export function currentStatusFor(requirement, capability, deployment) {
  const states = capability?.states ?? {};
  const status = {
    id: requirement?.id ?? capability?.id,
    codeComplete: states.implemented ?? "no",
    deployed: states.deployed ?? "no",
    enabled: states.configured ?? "no",
    enforcing: states.enforcing ?? "no",
    tested: states.tested ?? "no",
    accepted: requirement?.status?.acceptanceVerified ?? "no",
    deployedRevision: deployment?.apiTaskDefinition ?? null,
    deployedCommit: deployment?.imageCommit ?? null,
    verifiedAt: deployment?.verifiedAt ?? null,
  };
  return { ...status, headline: headlineFor(status) };
}

/** The whole current matrix, requirement order preserved. */
export function currentStatusMatrix(requirements, capabilities, deployment) {
  const byId = new Map((capabilities ?? []).map((c) => [c.id, c]));
  return (requirements ?? []).map((requirement) =>
    currentStatusFor(requirement, byId.get(requirement.id), deployment),
  );
}

/**
 * Everything in the acceptance plan that describes a PAST deployment.
 *
 * Returned so the page can render it under a historical heading rather than
 * silently dropping it. Nothing is rewritten - the strings come back exactly
 * as recorded, with the revision they were recorded against.
 */
export function supersededDeploymentRecords(securityData, deployment) {
  const current = revisionNumber(deployment?.apiTaskDefinition);
  const records = [];

  for (const module of securityData?.moduleProgress ?? []) {
    const named = revisionsIn(module.evidence);
    const stale =
      module.state === "merged-not-deployed" ||
      (named.length > 0 && current !== null && named.every((r) => r < current));
    if (stale) {
      records.push({
        kind: "module",
        name: module.name,
        state: module.state,
        evidence: module.evidence,
        namedRevisions: named,
      });
    }
  }

  for (const requirement of securityData?.requirements ?? []) {
    if (requirement?.status?.deployed === "no") {
      records.push({
        kind: "requirement",
        name: requirement.id,
        state: "deployed: no",
        evidence: requirement.evidence ?? null,
        namedRevisions: revisionsIn(JSON.stringify(requirement.evidence ?? "")),
      });
    }
  }
  return records;
}

function revisionsIn(text) {
  if (typeof text !== "string") return [];
  return [...text.matchAll(/revision (\d+)/gi)].map((m) => Number(m[1]));
}

function revisionNumber(taskDefinition) {
  if (typeof taskDefinition !== "string") return null;
  const match = taskDefinition.match(/:(\d+)$/);
  return match ? Number(match[1]) : null;
}

/**
 * Counts for the summary line, each naming exactly one dimension.
 *
 * Deliberately not a percentage. "7 of 9 complete" invites a reader to treat
 * the number as progress toward being finished, when the remaining work is a
 * business decision rather than engineering.
 */
export function currentStatusTotals(matrix) {
  const list = (matrix ?? []).filter((s) => s.codeComplete !== "n/a");
  return {
    total: list.length,
    deployed: list.filter((s) => s.deployed === "yes").length,
    enabled: list.filter((s) => s.enabled === "yes").length,
    enforcing: list.filter((s) => s.enforcing === "yes").length,
    tested: list.filter((s) => s.tested === "yes").length,
    accepted: list.filter((s) => s.accepted === "yes").length,
  };
}
