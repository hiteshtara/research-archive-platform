/*
 * Security Administration mode - decision logic as plain data, so it can be
 * unit-tested like every other presentation module here (node:test; there is
 * no component-render setup). The .tsx components render what this decides.
 *
 * The whole point of this module is that UI visibility is NOT access control.
 * Showing or hiding the toggle changes nothing server-side: all 12
 * administrator endpoints call requireAdministrator(actor) independently, and
 * the whole /api/v1/admin tree 404s while enforcement is off. This module only
 * decides what a user is shown, never what they may do.
 */

/** Security Administration navigation. Mirrors the approved five-item shape. */
export const securityAdminNavigationItems = [
  { key: "overview", label: "Overview", path: "/admin/overview" },
  { key: "identities", label: "Identities", path: "/admin/identities" },
  { key: "administrators", label: "Administrators", path: "/admin/administrators" },
  { key: "permissions", label: "Permissions", path: "/admin/units" },
  { key: "auditLog", label: "Audit Log", path: "/admin/audit" },
];

/** The two modes the header toggle switches between. */
export const ARCHIVE_MODE = "archive";
export const SECURITY_ADMIN_MODE = "securityAdmin";

/**
 * Capabilities as the UI should treat them, derived from whatever
 * /api/v1/me/capabilities returned - including nothing at all.
 *
 * FAILS CLOSED. Anything unparseable, absent, still loading or errored is
 * treated as "not an administrator", so there is no default-visible path.
 * A capability check that cannot be verified must never reveal the surface.
 */
export function readCapabilities(raw, { isLoading = false, isError = false } = {}) {
  if (isLoading || isError || raw === null || raw === undefined || typeof raw !== "object") {
    return { administrator: false, enforcementEnabled: false, administrationAvailable: false, verified: false };
  }
  const administrator = raw.administrator === true;
  const enforcementEnabled = raw.enforcementEnabled === true;
  // Never recomputed from the other two: the server decides, because a client
  // bug must not be able to show a surface the backend would refuse.
  const administrationAvailable = raw.administrationAvailable === true;
  return { administrator, enforcementEnabled, administrationAvailable, verified: true };
}

/**
 * Whether the header shows the Research Archive / Security Admin toggle.
 *
 * Keys on `administrator` ALONE, deliberately. An administrator keeps the
 * toggle even when enforcement is off - otherwise the entire surface would be
 * invisible on DEV, where enforcement is off by design. Selecting it then
 * reaches the unavailable screen rather than a working console.
 */
export function showsModeToggle(capabilities) {
  return capabilities?.administrator === true && capabilities?.verified === true;
}

/**
 * Whether Security Administration screens may function.
 *
 * Distinct from toggle visibility on purpose: visible but unavailable is a
 * real, expected state, not a contradiction.
 */
export function administrationIsUsable(capabilities) {
  return showsModeToggle(capabilities) && capabilities?.administrationAvailable === true;
}

/**
 * What an /admin/* route should render.
 *
 * "denied"      - not an administrator, or unverifiable. Never confirms the
 *                 surface exists.
 * "unavailable" - an administrator, but enforcement is off. Explanatory.
 * "available"   - render the real administration screens.
 */
export function adminRouteState(capabilities) {
  if (!showsModeToggle(capabilities)) {
    return "denied";
  }
  return administrationIsUsable(capabilities) ? "available" : "unavailable";
}

/**
 * Whether an administrator screen may issue admin API requests.
 *
 * Every React Query hook on the admin pages is gated on this. It is how
 * "zero admin API calls while unavailable" is guaranteed structurally rather
 * than by remembering not to call them: the request is never enabled, so a
 * 404 is never issued and never has to be interpreted.
 */
export function mayCallAdminApi(capabilities) {
  return administrationIsUsable(capabilities);
}

/**
 * Whether to render a working search or management control.
 *
 * Separate from mayCallAdminApi because the failure it prevents is different:
 * a form that renders but cannot submit is worse than an honest message. On
 * DEV today /admin/identities renders "Find an identity" with a live search
 * box while every admin endpoint answers 404 - a tester can type a query and
 * press Search into a dead end. This is what stops that.
 */
export function rendersInteractiveControls(capabilities) {
  return administrationIsUsable(capabilities);
}

/** Copy for the unavailable screen. Plain, and never an error. */
export function unavailableNotice(capabilities) {
  return {
    title: "Security Administration is unavailable",
    reason: "Record-level authorization enforcement is currently disabled in this environment.",
    clarification:
      "This is a configuration state, not a fault and not a problem with your access. " +
      "The administration tools become available when enforcement is enabled.",
    enforcementEnabled: capabilities?.enforcementEnabled === true,
    showsControls: false,
  };
}

/** Status rows for Overview. A value that cannot be verified is never invented. */
export function systemStatusRows(capabilities, { activeGrants = undefined } = {}) {
  const usable = administrationIsUsable(capabilities);
  return [
    {
      key: "enforcement",
      label: "Authorization Enforcement",
      value: capabilities?.verified ? (capabilities.enforcementEnabled ? "ON" : "OFF") : "Unknown",
      tone: capabilities?.enforcementEnabled ? "active" : "inactive",
    },
    {
      key: "administration",
      label: "Administration",
      value: usable ? "Available" : "Unavailable",
      tone: usable ? "active" : "inactive",
    },
    {
      key: "activeGrants",
      label: "Active Grants",
      // Unobtainable while the admin API is unreachable. Never defaulted to a
      // number: a hardcoded count is indistinguishable from a measured one.
      value: usable && typeof activeGrants === "number" ? String(activeGrants) : "Unavailable",
      tone: "neutral",
    },
  ];
}
