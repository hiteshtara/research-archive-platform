/**
 * Presentation helpers for the ARCHIVE_ADMIN screens.
 *
 * Pure functions only, so they can be tested with node:test like every other
 * *.test.mjs in this project (there is no component-render test setup here).
 *
 * The rules these encode are architectural, not cosmetic:
 *   - administration is not data access, so an administrator with no grant is
 *     warned, not reassured;
 *   - historical Kuali data says who someone WAS, never whether they may read
 *     the archive today;
 *   - an SAP account grant reaches Awards only.
 */

/** Grant types as the API stores them. IO is shown as "SAP Account". */
export const GRANT_TYPES = ["CENTRAL", "UNIT", "IO", "CONTACT_DERIVATION"];

export function grantTypeLabel(type) {
  switch (type) {
    case "CENTRAL":
      return "Central (all records)";
    case "UNIT":
      return "Unit";
    case "IO":
      return "SAP Account";
    case "CONTACT_DERIVATION":
      return "Research staff relationship";
    default:
      return type ?? "Unknown";
  }
}

/**
 * The one-line scope description shown under a grant. Returns null when the
 * grant's scope needs no explanation.
 */
export function grantScopeNote(grant) {
  if (!grant) return null;
  if (grant.type === "IO") {
    return "Applies to Awards only. Does not reach related Proposals, Negotiations or Subawards.";
  }
  if (grant.type === "UNIT") {
    return grant.includeDescendants
      ? "Includes sub-units beneath this unit."
      : "This unit only; sub-units are not included.";
  }
  if (grant.type === "CONTACT_DERIVATION") {
    return "Records on which this person is listed in a qualifying role.";
  }
  return null;
}

/** True when a grant is currently in force. Mirrors AccessGrant.isActiveAt. */
export function isGrantActive(grant, now = new Date()) {
  if (!grant || grant.revokedAt) return false;
  const at = now instanceof Date ? now.getTime() : new Date(now).getTime();
  if (grant.validFrom && at < new Date(grant.validFrom).getTime()) return false;
  if (grant.expiresAt && at >= new Date(grant.expiresAt).getTime()) return false;
  return true;
}

/**
 * The access banner for an identity.
 *
 * Returns { level, text } or null when the identity has access and nothing
 * needs saying. "level" is "warning" for no access and "danger" for a
 * suspension, because the two call for different administrator responses.
 */
export function accessBanner(identity) {
  if (!identity) return null;

  if (identity.suspended) {
    return {
      level: "danger",
      text: "Suspended. Every grant is overridden and no record is visible.",
    };
  }

  // Checked before grants, deliberately. An identity with no verified link
  // cannot be resolved at all, so it has no access however many grants are
  // listed - and a screen that showed those grants without saying so would
  // read as "provisioned" when the person cannot even sign in.
  const hasActiveLink = (identity.links ?? []).some(
    (link) => link && link.status === "ACTIVE",
  );
  if (!hasActiveLink) {
    const grantCount = (identity.grants ?? []).filter((grant) =>
      isGrantActive(grant),
    ).length;
    return {
      level: "warning",
      text:
        grantCount > 0
          ? `Not provisioned. No verified identity link, so this person cannot sign in ` +
            `and the ${grantCount} grant${grantCount === 1 ? "" : "s"} below ` +
            `${grantCount === 1 ? "has" : "have"} no effect until an administrator ` +
            `links their identity.`
          : "Not provisioned. No verified identity link and no archive access.",
    };
  }

  const hasActiveGrant = (identity.grants ?? []).some((grant) => isGrantActive(grant));
  if (hasActiveGrant) return null;

  if (identity.administrator) {
    return {
      level: "warning",
      text:
        "No archive access. Holding ARCHIVE_ADMIN allows managing access and grants " +
        "no access to records.",
    };
  }
  return {
    level: "warning",
    text: "No archive access. Signing in through BU grants nothing on its own.",
  };
}

/** What a search hit's source actually proves. */
export function searchSourceNote(source) {
  switch (source) {
    case "IDENTITY_LINK":
      return "Already linked in the archive.";
    case "SIGN_IN_HISTORY":
      return "Has authenticated through BU, but holds no archive link.";
    case "HISTORICAL_PERSON":
      return (
        "Historical record from Kuali. Says who they were on archived records; " +
        "says nothing about current employment or access."
      );
    default:
      return "";
  }
}

/**
 * Historical Kuali status, captioned so it can never be mistaken for an
 * access state. Returns null when there is nothing to show.
 */
export function historicalStatusCaption(actvInd, extractedAt) {
  if (!actvInd) return null;
  const state = actvInd === "Y" ? "Active in Kuali" : "Inactive in Kuali";
  const asAt = extractedAt ? ` as at ${formatDate(extractedAt)}` : "";
  return `${state}${asAt} — historical only; does not affect archive access.`;
}

/**
 * Whether the revoke-administrator control should be enabled.
 *
 * Disabled at one active administrator so the 409 from the server is a
 * last-resort guard rather than the way a user discovers the rule.
 */
export function canRevokeAdministrator(activeAdministratorCount) {
  return Number(activeAdministratorCount) > 1;
}

export function revokeAdministratorDisabledReason(activeAdministratorCount) {
  return canRevokeAdministrator(activeAdministratorCount)
    ? null
    : "The archive must always have at least one administrator. Appoint another first.";
}

/** Human wording for an audit action. */
export function auditActionLabel(action) {
  const labels = {
    ADMIN_ROLE_ASSIGNED: "Administrator appointed",
    ADMIN_ROLE_REVOKED: "Administrator removed",
    ADMIN_ROLE_BOOTSTRAPPED: "First administrator created",
    ACCESS_GRANTED: "Access granted",
    ACCESS_MODIFIED: "Access changed",
    ACCESS_REVOKED: "Access revoked",
    IDENTITY_LINKED: "Identity linked",
    IDENTITY_UNLINKED: "Identity unlinked",
    IDENTITY_SUSPENDED: "Access suspended",
    IDENTITY_RESTORED: "Access restored",
  };
  return labels[action] ?? action;
}

/**
 * Validate a grant form before sending it, so the server's CHECK constraints
 * are never the first thing a user hears from. Returns an array of messages;
 * empty means valid.
 */
export function validateGrantForm(form) {
  const problems = [];
  if (!form || !GRANT_TYPES.includes(form.type)) {
    problems.push("Choose a grant type.");
    return problems;
  }
  if (form.type === "UNIT" && !String(form.unitNumber ?? "").trim()) {
    problems.push("A unit grant needs a unit number.");
  }
  if (form.type === "IO" && !String(form.ioValue ?? "").trim()) {
    problems.push("An SAP account grant needs an account number.");
  }
  if (form.type !== "UNIT" && form.includeDescendants) {
    problems.push("Only a unit grant can include sub-units.");
  }
  if (form.expiresAt && Number.isNaN(new Date(form.expiresAt).getTime())) {
    problems.push("The expiry date is not a valid date.");
  }
  return problems;
}

/** Destructive actions require a typed reason: an unexplained audit row is a poor one. */
export function requiresReason(action) {
  return ["revokeGrant", "unlink", "suspend", "revokeAdministrator"].includes(action);
}

export function formatDate(value) {
  if (!value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? ""
    : date.toISOString().slice(0, 10);
}
