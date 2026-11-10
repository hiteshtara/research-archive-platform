/*
 * Telling current status apart from historical record.
 *
 * securityRequirements.json was written on 2026-10-20 and describes the
 * deployment as it stood then: "the dev API service runs task definition
 * revision 81 ... NOTHING authorization-related is running". That was true.
 * Revision 85 shipped on 2026-11-09 carrying all of it, so the sentence is now
 * false - and a reader has no way to tell, because the text does not say when
 * it was written.
 *
 * Deleting it would be worse. It is the record of what was true, and the
 * acceptance verdicts around it still stand. So the page shows it as history,
 * labelled, and this module decides which statements that applies to.
 *
 * The test is mechanical rather than a hard-coded list: a statement that names
 * an API task-definition revision OLDER than the one actually deployed is
 * describing a past deployment. When revision 86 ships, the same rule catches
 * whatever is stale then, with nobody having to remember.
 */

/** Pulls every "revision NN" / "task definition NN" the text claims. */
export function revisionsNamedIn(text) {
  if (typeof text !== "string") return [];
  const found = new Set();
  for (const match of text.matchAll(/\b(?:task definition )?revision (\d+)/gi)) {
    found.add(Number(match[1]));
  }
  for (const match of text.matchAll(/\btask definition[^.\d]{0,20}(\d+)/gi)) {
    found.add(Number(match[1]));
  }
  return [...found].sort((a, b) => a - b);
}

/** The revision currently deployed, from the readiness snapshot. */
export function deployedRevision(deployment) {
  const name = deployment?.apiTaskDefinition;
  if (typeof name !== "string") return null;
  const match = name.match(/:(\d+)$/);
  return match ? Number(match[1]) : null;
}

/**
 * Is this statement describing a deployment that has since been replaced?
 *
 * Only answers true when it can prove it: a revision is named, the current
 * revision is known, and the named one is older.
 */
export function isSuperseded(text, deployment) {
  const current = deployedRevision(deployment);
  if (current === null) return false;
  const named = revisionsNamedIn(text);
  return named.length > 0 && named.every((revision) => revision < current);
}

/**
 * Split the requirements document's standing statements into what still holds
 * and what has been overtaken.
 *
 * `enforcementState` is deliberately NOT subject to this: it names no
 * revision, and "record-level authorization is OFF everywhere" is still true
 * and is the single most important sentence on the page. A rule that could
 * quietly demote it to history would be the worst possible failure here, so
 * the rule can only ever demote a statement that names an older revision.
 */
export function classifyStatements(securityData, deployment) {
  const current = [];
  const historical = [];

  const entries = [
    ["enforcementState", "Record-level authorization", securityData?.enforcementState],
    ["recommendationStatus", "Business decisions and what they mean", securityData?.recommendationStatus],
    ["apiDeploymentState", "What was running", securityData?.apiDeploymentState],
  ];

  for (const [key, heading, text] of entries) {
    if (!text) continue;
    const entry = { key, heading, text };
    if (isSuperseded(text, deployment)) {
      historical.push({ ...entry, supersededBy: deployment?.apiTaskDefinition ?? null });
    } else {
      current.push(entry);
    }
  }
  return { current, historical };
}

/**
 * None of the nine acceptance requirements may read as verified.
 *
 * Acceptance means someone checked the requirement against its criteria with
 * enforcement on. That has not happened for any of them, and a merged pull
 * request, a passing test and a deployment are each a different thing. This
 * returns the ones claiming otherwise so the page can fail loudly rather than
 * quietly overstating.
 */
export function requirementsClaimingVerification(requirements) {
  return (requirements ?? []).filter(
    (requirement) => requirement?.status?.acceptanceVerified !== "no",
  );
}

/**
 * How many requirements are accepted. Expected to be zero, and stated as a
 * number rather than a sentence so the page cannot drift away from the data.
 */
export function acceptanceSummary(requirements) {
  const list = requirements ?? [];
  return {
    total: list.length,
    verified: list.filter((r) => r?.status?.acceptanceVerified === "yes").length,
    decisionsOpen: list.filter((r) => r?.status?.decisionApproved === "open").length,
  };
}
