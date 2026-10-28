import test from "node:test";
import assert from "node:assert/strict";

import {
  accessBanner,
  auditActionLabel,
  canRevokeAdministrator,
  grantScopeNote,
  grantTypeLabel,
  historicalStatusCaption,
  isGrantActive,
  revokeAdministratorDisabledReason,
  searchSourceNote,
  validateGrantForm,
} from "./adminPresentation.mjs";

/** An identity that has been verified and linked by an administrator. */
const ACTIVE_LINK = { status: "ACTIVE" };

test("an administrator with no grant is warned, not reassured", () => {
  const banner = accessBanner({ administrator: true, suspended: false, grants: [], links: [ACTIVE_LINK] });

  assert.equal(banner.level, "warning");
  assert.match(banner.text, /grants no access to records/);
});

test("an ordinary identity with no grant is told authentication alone grants nothing", () => {
  const banner = accessBanner({ administrator: false, suspended: false, grants: [], links: [ACTIVE_LINK] });

  assert.match(banner.text, /grants nothing on its own/);
});

test("a suspension outranks any grant and is shown as danger", () => {
  const banner = accessBanner({
    administrator: false,
    suspended: true,
    grants: [{ type: "CENTRAL" }],
    links: [ACTIVE_LINK],
  });

  assert.equal(banner.level, "danger");
  assert.match(banner.text, /Every grant is overridden/);
});

test("no banner when the identity actually has access", () => {
  assert.equal(
    accessBanner({
      administrator: false,
      suspended: false,
      grants: [{ type: "CENTRAL" }],
      links: [ACTIVE_LINK],
    }),
    null,
  );
});

test("a revoked grant does not count as access", () => {
  const banner = accessBanner({
    administrator: false,
    suspended: false,
    grants: [{ type: "CENTRAL", revokedAt: "2026-10-01T00:00:00Z" }],
    links: [ACTIVE_LINK],
  });

  assert.equal(banner.level, "warning");
});

test("an expired grant does not count as access", () => {
  const grant = { type: "CENTRAL", expiresAt: "2026-01-01T00:00:00Z" };

  assert.equal(isGrantActive(grant, new Date("2026-10-27T00:00:00Z")), false);
  assert.equal(isGrantActive(grant, new Date("2025-06-01T00:00:00Z")), true);
});

test("a grant with no expiry stays active indefinitely", () => {
  assert.equal(
    isGrantActive({ type: "CENTRAL" }, new Date("2099-01-01T00:00:00Z")),
    true,
  );
});

test("IO is labelled SAP Account and scoped to Awards only", () => {
  assert.equal(grantTypeLabel("IO"), "SAP Account");
  assert.match(grantScopeNote({ type: "IO" }), /Awards only/);
  assert.match(
    grantScopeNote({ type: "IO" }),
    /does not reach related Proposals, Negotiations or Subawards/i,
  );
});

test("a unit grant says whether sub-units are included", () => {
  assert.match(grantScopeNote({ type: "UNIT", includeDescendants: true }), /Includes sub-units/);
  assert.match(grantScopeNote({ type: "UNIT", includeDescendants: false }), /sub-units are not included/);
});

test("a research staff relationship is described as a grant, not an inference", () => {
  assert.match(grantScopeNote({ type: "CONTACT_DERIVATION" }), /listed in a qualifying role/);
});

test("a historical person hit is captioned as proving nothing about access", () => {
  const note = searchSourceNote("HISTORICAL_PERSON");

  assert.match(note, /Historical record from Kuali/);
  assert.match(note, /nothing about current employment or access/);
});

test("a sign-in history hit says the person authenticated but holds no link", () => {
  assert.match(searchSourceNote("SIGN_IN_HISTORY"), /holds no archive link/);
});

test("historical Kuali status is always captioned as historical", () => {
  const caption = historicalStatusCaption("N", "2026-08-03T00:00:00Z");

  assert.match(caption, /Inactive in Kuali/);
  assert.match(caption, /as at 2026-08-03/);
  assert.match(caption, /does not affect archive access/);
});

test("no historical status caption when there is no status to show", () => {
  assert.equal(historicalStatusCaption(null, "2026-08-03T00:00:00Z"), null);
});

test("the last administrator cannot be revoked from the UI", () => {
  assert.equal(canRevokeAdministrator(1), false);
  assert.equal(canRevokeAdministrator(2), true);
  assert.match(revokeAdministratorDisabledReason(1), /at least one administrator/);
  assert.equal(revokeAdministratorDisabledReason(2), null);
});

test("a unit grant without a unit number is refused before it is sent", () => {
  assert.deepEqual(validateGrantForm({ type: "UNIT", unitNumber: "  " }), [
    "A unit grant needs a unit number.",
  ]);
});

test("an SAP account grant without an account number is refused before it is sent", () => {
  assert.deepEqual(validateGrantForm({ type: "IO", ioValue: "" }), [
    "An SAP account grant needs an account number.",
  ]);
});

test("only a unit grant may include sub-units", () => {
  assert.deepEqual(validateGrantForm({ type: "CENTRAL", includeDescendants: true }), [
    "Only a unit grant can include sub-units.",
  ]);
  assert.deepEqual(
    validateGrantForm({ type: "IO", ioValue: "9500012345", includeDescendants: true }),
    ["Only a unit grant can include sub-units."],
  );
});

test("a valid grant form produces no problems", () => {
  assert.deepEqual(validateGrantForm({ type: "CENTRAL" }), []);
  assert.deepEqual(validateGrantForm({ type: "UNIT", unitNumber: "1234", includeDescendants: true }), []);
  assert.deepEqual(validateGrantForm({ type: "IO", ioValue: "9500012345" }), []);
});

test("an unparseable expiry is caught before it is sent", () => {
  assert.deepEqual(validateGrantForm({ type: "CENTRAL", expiresAt: "not a date" }), [
    "The expiry date is not a valid date.",
  ]);
});

test("audit actions read as plain English", () => {
  assert.equal(auditActionLabel("ACCESS_REVOKED"), "Access revoked");
  assert.equal(auditActionLabel("IDENTITY_SUSPENDED"), "Access suspended");
  assert.equal(auditActionLabel("SOMETHING_NEW"), "SOMETHING_NEW");
});

test("an identity with no verified link is reported as not provisioned", () => {
  const banner = accessBanner({ administrator: false, suspended: false, grants: [], links: [] });

  assert.equal(banner.level, "warning");
  assert.match(banner.text, /Not provisioned/);
  assert.match(banner.text, /No verified identity link/);
});

test("grants on an unlinked identity are reported as having no effect", () => {
  // The misleading case: the page lists grants, so it reads as provisioned,
  // but the person cannot resolve and therefore has no access at all.
  const banner = accessBanner({
    administrator: false,
    suspended: false,
    grants: [{ type: "CENTRAL" }],
    links: [],
  });

  assert.equal(banner.level, "warning");
  assert.match(banner.text, /Not provisioned/);
  assert.match(banner.text, /1 grant below has no effect/);
});

test("the unlinked warning pluralises its grant count", () => {
  const banner = accessBanner({
    administrator: false,
    suspended: false,
    grants: [{ type: "CENTRAL" }, { type: "UNIT", unitNumber: "1234" }],
    links: [],
  });

  assert.match(banner.text, /2 grants below have no effect/);
});

test("a revoked link does not count as provisioned", () => {
  const banner = accessBanner({
    administrator: false,
    suspended: false,
    grants: [{ type: "CENTRAL" }],
    links: [{ status: "REVOKED" }],
  });

  assert.match(banner.text, /Not provisioned/);
});

test("suspension still outranks the not-provisioned warning", () => {
  const banner = accessBanner({
    administrator: false,
    suspended: true,
    grants: [],
    links: [],
  });

  assert.equal(banner.level, "danger");
  assert.match(banner.text, /Suspended/);
});

test("an unlinked administrator is told they are not provisioned, not merely ungranted", () => {
  const banner = accessBanner({ administrator: true, suspended: false, grants: [], links: [] });

  assert.match(banner.text, /Not provisioned/);
});
