/*
 * Presentation helpers for the temporary View Access As QA route.
 *
 * DEVELOPMENT ONLY. This module is reachable only from
 * src/pages/ViewAccessAsPage.tsx, which App.tsx imports inside an
 * `import.meta.env.DEV` branch, so Vite drops the whole chunk from a
 * production build. See docs/VIEW_ACCESS_AS_PAGE.md for the removal checklist.
 *
 * These functions shape what the local harness returns. None of them decides
 * an authorization question: every PERMIT, DENY, rule name and refusal code
 * arrives already decided by the production engine running inside the harness.
 * A helper here that worked out a verdict for itself would be a second
 * authorization implementation, and the page would then agree with itself
 * rather than report on the engine.
 */

/**
 * The local harness, hard-coded on purpose.
 *
 * There is deliberately no environment variable and no setting for this. A
 * configurable harness URL is a way to point a QA simulator at something that
 * is not a local throwaway process, which is exactly the thing this feature
 * must not become. Loopback, one port, no override.
 */
export const HARNESS_ORIGIN = "http://127.0.0.1:8750";

/** How an operator starts the harness, shown verbatim when it is not running. */
export const HARNESS_START_COMMAND =
  "cd api && mvn -o test-compile && java -cp target/classes:target/test-classes:$(cat cp.txt) " +
  "edu.bu.archive.application.authorization.viewaccessas.demo.ViewAccessAsDemoServer";

export function harnessUrl(path) {
  if (typeof path !== "string" || !path.startsWith("/")) {
    throw new Error("harness path must start with /");
  }
  return HARNESS_ORIGIN + path;
}

/**
 * What to tell the reader when the fetch failed.
 *
 * An empty page is the failure mode this project has already been bitten by:
 * a negative assertion on a page that never loaded passes silently. So the
 * page says the harness is not running, rather than showing nothing.
 */
export function harnessUnavailableMessage() {
  return {
    title: "Local QA harness not running",
    detail:
      `Nothing answered on ${HARNESS_ORIGIN}. This route reads its decisions from a ` +
      "test-scope harness on this machine; it has no production API and is inert " +
      "without it.",
    command: HARNESS_START_COMMAND,
  };
}

/** MUI chip colour for a verdict. Presentation only. */
export function verdictTone(verdict) {
  switch (verdict) {
    case "PERMIT":
    case "ALLOWED":
      return "success";
    case "DENY":
    case "REFUSED":
      return "warning";
    case "INDETERMINATE":
      return "error";
    default:
      return "default";
  }
}

/** The family a profile result holds for this key, or its first one. */
export function familyFor(result, familyKey) {
  if (!result || !Array.isArray(result.awards) || result.awards.length === 0) {
    return null;
  }
  return result.awards.find((award) => award.familyKey === familyKey) ?? result.awards[0];
}

/**
 * One row per record, with a verdict column per profile, so two simulated
 * profiles read side by side. Order follows the first profile's rows.
 */
export function comparisonRows(results, key) {
  if (!Array.isArray(results) || results.length === 0) {
    return [];
  }
  const rows = new Map();
  for (const result of results) {
    for (const record of result[key] ?? []) {
      if (!rows.has(record.id)) {
        rows.set(record.id, {
          id: record.id,
          label: record.label,
          rule: record.rule,
          verdicts: [],
        });
      }
      rows.get(record.id).verdicts.push({
        profile: result.profile,
        verdict: record.verdict,
      });
    }
  }
  return [...rows.values()];
}

/** PERMIT/DENY tally for one family, from the verdicts the engine returned. */
export function familyTally(family) {
  if (!family || !Array.isArray(family.versions)) {
    return { permitted: 0, denied: 0, total: 0 };
  }
  const permitted = family.versions.filter((v) => v.verdict === "PERMIT").length;
  return {
    permitted,
    denied: family.versions.length - permitted,
    total: family.versions.length,
  };
}

/**
 * How to describe this family's sensitivity to the version-scope policy.
 *
 * The boolean is computed server-side by evaluating the same profile twice,
 * under both scopes. It is the clearest reading of what SEC-003 did: a
 * contact-derived profile is unaffected by the policy, because contact access
 * is decided against the version being read whatever the policy says, while a
 * department or IO grant still follows it.
 */
export function scopeSensitivityLabel(family) {
  if (!family || typeof family.changesUnderOtherScope !== "boolean") {
    return null;
  }
  return family.changesUnderOtherScope
    ? `Verdicts change under ${family.otherScope}`
    : `Verdicts do not change under ${family.otherScope}`;
}

/** True when some versions of a family are visible and others are not. */
export function isPartialFamily(family) {
  const tally = familyTally(family);
  return tally.permitted > 0 && tally.denied > 0;
}

/** The profiles to request, dropping an unset comparison slot. */
export function requestedProfiles(profileA, profileB) {
  const chosen = [profileA];
  if (profileB && profileB !== profileA) {
    chosen.push(profileB);
  }
  return chosen.filter((name) => typeof name === "string" && name.length > 0);
}

/** The harness query string for one evaluation. */
export function evaluateQuery({ profileA, profileB, versionScope, departmentMatch }) {
  const params = new URLSearchParams();
  params.set("profileA", profileA ?? "");
  params.set("profileB", profileB ?? "");
  params.set("versionScope", versionScope ?? "PER_VERSION");
  params.set("departmentMatch", departmentMatch ?? "EXACT_LEAD_UNIT");
  return `/api/evaluate?${params.toString()}`;
}
