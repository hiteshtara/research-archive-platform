import assert from "node:assert/strict";
import test from "node:test";

import { resolveContactDisplay } from "./subawardContactsPresentation.mjs";

function contact(overrides = {}) {
  return {
    subawardContactId: 1,
    subawardId: 17206,
    subawardCode: "1363",
    sequenceNumber: 8,
    contactTypeCode: "1",
    contactTypeDescription: null,
    fullName: null,
    organization: null,
    email: null,
    phone: null,
    rolodexId: null,
    requisitionerId: null,
    ...overrides,
  };
}

test("resolveContactDisplay leads with the resolved role name, not the raw code", () => {
  const display = resolveContactDisplay(
    contact({ contactTypeCode: "34", contactTypeDescription: "Administrative Contact" }),
  );

  assert.equal(display.role, "Administrative Contact");
});

test("resolveContactDisplay falls back to the raw code only when no description was archived", () => {
  const display = resolveContactDisplay(
    contact({ contactTypeCode: "99", contactTypeDescription: null }),
  );

  assert.equal(display.role, "99");
});

test("resolveContactDisplay surfaces a resolved Rolodex identity (name/org/phone/email)", () => {
  const display = resolveContactDisplay(
    contact({
      fullName: "Smith, Jane",
      organization: "Acme Research Foundation",
      email: "jane.smith@example.org",
      phone: "555-0100",
      rolodexId: 4242,
    }),
  );

  assert.equal(display.hasIdentity, true);
  assert.equal(display.name, "Smith, Jane");
  assert.equal(display.organization, "Acme Research Foundation");
  assert.equal(display.email, "jane.smith@example.org");
  assert.equal(display.phone, "555-0100");
});

test("resolveContactDisplay surfaces a resolved Person identity without an organization (archive.person has none)", () => {
  const display = resolveContactDisplay(
    contact({
      fullName: "Doe, John",
      organization: null,
      email: "jdoe@bu.edu",
      phone: "555-0199",
      requisitionerId: "JDOE",
    }),
  );

  assert.equal(display.hasIdentity, true);
  assert.equal(display.name, "Doe, John");
  assert.equal(display.organization, null);
});

test("resolveContactDisplay is honest, not fabricated, when neither Rolodex nor Person resolved", () => {
  // A real requisitioner_id outside archive.person's deliberately
  // narrow scope (unit_administrator/award_unit_contact only) - the
  // relationship is real, but this archive has no name for it.
  const display = resolveContactDisplay(
    contact({ requisitionerId: "SOMEONE", fullName: null }),
  );

  assert.equal(display.hasIdentity, false);
  assert.equal(display.name, null);
});
