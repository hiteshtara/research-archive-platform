import { strict as assert } from "node:assert";
import test from "node:test";

import {
  readCapabilities,
  showsModeToggle,
  administrationIsUsable,
  adminRouteState,
  mayCallAdminApi,
  rendersInteractiveControls,
  unavailableNotice,
  systemStatusRows,
  securityAdminNavigationItems,
} from "./securityAdminPresentation.mjs";

const caps = (administrator, enforcementEnabled, administrationAvailable) =>
  readCapabilities({ administrator, enforcementEnabled, administrationAvailable });

// ── fail closed ──────────────────────────────────────────────────────
// Every one of these is a way the capability check can fail to produce an
// answer. None of them may reveal the surface.
test("anything unverifiable fails closed and hides the toggle", () => {
  const closed = [
    readCapabilities(null),
    readCapabilities(undefined),
    readCapabilities("administrator"),
    readCapabilities(42),
    readCapabilities({}, { isLoading: true }),
    readCapabilities({ administrator: true }, { isLoading: true }),
    readCapabilities({ administrator: true }, { isError: true }),
  ];
  for (const c of closed) {
    assert.equal(showsModeToggle(c), false);
    assert.equal(administrationIsUsable(c), false);
    assert.equal(adminRouteState(c), "denied");
    assert.equal(mayCallAdminApi(c), false);
  }
});

test("a truthy-but-not-true administrator value is not an administrator", () => {
  // Guards against a server or client shape change making "yes"/1 pass.
  for (const value of ["true", 1, "yes", {}]) {
    assert.equal(showsModeToggle(readCapabilities({ administrator: value })), false);
  }
});

// ── all eight combinations of the three booleans ─────────────────────
test("every combination of the three capability booleans", () => {
  const expected = [
    // administrator, enforcement, available  -> toggle, usable, routeState
    [false, false, false, false, false, "denied"],
    [false, false, true,  false, false, "denied"],
    [false, true,  false, false, false, "denied"],
    [false, true,  true,  false, false, "denied"],
    [true,  false, false, true,  false, "unavailable"],
    [true,  false, true,  true,  true,  "available"],
    [true,  true,  false, true,  false, "unavailable"],
    [true,  true,  true,  true,  true,  "available"],
  ];
  for (const [a, e, av, toggle, usable, state] of expected) {
    const c = caps(a, e, av);
    assert.equal(showsModeToggle(c), toggle, `toggle for ${a}/${e}/${av}`);
    assert.equal(administrationIsUsable(c), usable, `usable for ${a}/${e}/${av}`);
    assert.equal(adminRouteState(c), state, `state for ${a}/${e}/${av}`);
  }
});

// ── the requirement that motivated the redesign ──────────────────────
test("an administrator keeps the toggle when enforcement is OFF", () => {
  const c = caps(true, false, false);
  assert.equal(showsModeToggle(c), true, "the toggle must remain visible");
  assert.equal(adminRouteState(c), "unavailable", "and lead to the unavailable screen");
});

test("a non-administrator never sees the toggle, whatever enforcement says", () => {
  assert.equal(showsModeToggle(caps(false, true, true)), false);
  assert.equal(showsModeToggle(caps(false, false, false)), false);
});

// ── zero admin API calls, and no dead controls ───────────────────────
test("no admin API call is permitted unless administration is usable", () => {
  assert.equal(mayCallAdminApi(caps(true, false, false)), false, "unavailable must not call");
  assert.equal(mayCallAdminApi(caps(false, true, true)), false, "non-admin must not call");
  assert.equal(mayCallAdminApi(caps(true, true, true)), true);
});

test("interactive controls are withheld whenever the API is unreachable", () => {
  // The observed DEV behaviour this prevents: /admin/identities renders a
  // working search box while every admin endpoint answers 404.
  assert.equal(rendersInteractiveControls(caps(true, false, false)), false);
  assert.equal(unavailableNotice(caps(true, false, false)).showsControls, false);
  assert.equal(rendersInteractiveControls(caps(true, true, true)), true);
});

// ── never invent a number ────────────────────────────────────────────
test("active grants reads Unavailable rather than a number when unusable", () => {
  const rows = systemStatusRows(caps(true, false, false), { activeGrants: 2 });
  const grants = rows.find((r) => r.key === "activeGrants");
  assert.equal(grants.value, "Unavailable",
    "a count supplied while the API is unreachable must not be displayed");
});

test("active grants shows a measured number only when administration is usable", () => {
  const rows = systemStatusRows(caps(true, true, true), { activeGrants: 2 });
  assert.equal(rows.find((r) => r.key === "activeGrants").value, "2");
});

test("an absent grant count is never defaulted to zero", () => {
  const rows = systemStatusRows(caps(true, true, true));
  assert.equal(rows.find((r) => r.key === "activeGrants").value, "Unavailable");
});

test("enforcement status is Unknown rather than OFF when unverified", () => {
  const rows = systemStatusRows(readCapabilities(null));
  assert.equal(rows.find((r) => r.key === "enforcement").value, "Unknown");
});

test("enforcement status reports the verified backend state", () => {
  assert.equal(
    systemStatusRows(caps(true, true, true)).find((r) => r.key === "enforcement").value, "ON");
  assert.equal(
    systemStatusRows(caps(true, false, false)).find((r) => r.key === "enforcement").value, "OFF");
});

// ── navigation ───────────────────────────────────────────────────────
test("navigation is the approved five areas and points at real routes", () => {
  assert.deepEqual(
    securityAdminNavigationItems.map((i) => i.label),
    ["Overview", "Identities", "Administrators", "Permissions", "Audit Log"]);
  for (const item of securityAdminNavigationItems) {
    assert.ok(item.path.startsWith("/admin/"), `${item.label} must live under /admin`);
  }
});

test("navigation reuses the existing admin pages rather than new routes", () => {
  const paths = securityAdminNavigationItems.map((i) => i.path);
  for (const existing of ["/admin/identities", "/admin/administrators", "/admin/audit"]) {
    assert.ok(paths.includes(existing), `${existing} already exists and must be reused`);
  }
});
