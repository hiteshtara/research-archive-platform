/*
 * Abuse cases for the View Access As prototype.
 *
 * These test the PROTOTYPE, which renders fixtures and calls nothing. They
 * cannot test an endpoint that does not exist. What they can do - and the
 * reason they exist now rather than after the API is built - is pin the
 * guarantees the design makes, in a form the eventual implementation has to
 * satisfy rather than a paragraph it has to remember.
 *
 * Each test names the threat-model section it comes from.
 */

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import fixtures from "./previewFixtures.json" with { type: "json" };

const read = (relative) => readFileSync(fileURLToPath(new URL(relative, import.meta.url)), "utf8");
const prototypeSource = read("./ViewAccessAsPreviewPrototype.tsx");
const designDoc = read("../../../../docs/security/VIEW_ACCESS_AS_DESIGN.md");

// ── §4.1 the oracle problem ──────────────────────────────────────────

test("a non-existent identifier is indistinguishable from a denied one", () => {
  /*
   * The single most important property. If these differ in any field, the
   * screen becomes a way to discover which identifiers are real - which is
   * exactly what RecordNotAccessibleException's 404 already prevents for
   * ordinary callers.
   */
  const denied = fixtures.decisions.find((row) => row.identifier === "SYN-AWD-0002");
  const missing = fixtures.decisions.find((row) => row.identifier === "SYN-AWD-DOES-NOT-EXIST");

  assert.ok(denied && missing, "both fixtures must exist for this to mean anything");
  assert.deepEqual(
    missing.subject,
    denied.subject,
    "a record that does not exist must answer exactly as one that is denied",
  );
  assert.deepEqual(missing.administrator, denied.administrator);
});

test("no decision carries a NOT_FOUND reason, which would leak existence", () => {
  for (const row of fixtures.decisions) {
    for (const side of [row.subject, row.administrator]) {
      assert.notEqual(side.reasonCode, "NOT_FOUND");
      assert.ok(
        !/exist/i.test(side.reasonCode),
        `${side.reasonCode} hints at existence`,
      );
    }
  }
});

// ── §3.3 no record disclosure ────────────────────────────────────────

test("no decision carries anything describing the record", () => {
  const forbidden = [
    "title", "status", "leadUnit", "unit", "principalInvestigator", "pi",
    "sponsor", "amount", "date", "effectiveDate", "documentNumber",
  ];
  for (const row of fixtures.decisions) {
    for (const key of Object.keys(row)) {
      assert.ok(
        !forbidden.includes(key),
        `a decision row carries "${key}", which describes the record rather than the decision`,
      );
    }
    for (const side of [row.subject, row.administrator]) {
      assert.deepEqual(
        Object.keys(side).sort(),
        ["decision", "reasonCode", "versionsEvaluated"],
        "a decision is a verdict, a reason and a count of versions considered - nothing else",
      );
    }
  }
});

test("the subject's scope is described in words, never as a count of records", () => {
  // §3: a count IS an enumeration, and the administrator may hold no data grant.
  assert.ok(fixtures.subject.scopeDescription.length > 20);

  /*
   * A unit number is not a record count. "Unit 7777 and its sub-units" is the
   * scope itself and has to be shown; "4,312 Awards" is the archive's shape
   * and must not be. So the check is for counting PHRASING, not for digits.
   */
  assert.ok(
    !/\d[\d,]*\s+(records?|awards?|proposals?|subawards?|negotiations?|documents?)/i.test(
      fixtures.subject.scopeDescription,
    ),
    "the scope description states a record count, which enumerates the archive",
  );
  assert.ok(!("reachableRecordCount" in fixtures.subject));
});

test("the prototype renders no dashboard count, search preview or record list", () => {
  for (const banned of ["totalElements", "resultCount", "reachableRecords", "recordList"]) {
    assert.ok(!prototypeSource.includes(banned), `the prototype must not render ${banned}`);
  }
});

// ── §4.2 privilege inversion ─────────────────────────────────────────

test("the administrator holds no CENTRAL grant, and their own column shows it", () => {
  /*
   * ARCHIVE_ADMIN is the right to administer grants, not to read records.
   * The comparison column is the clearest way to make that concrete, so the
   * fixture deliberately shows an administrator being denied records the
   * subject can reach.
   */
  assert.equal(fixtures.administrator.holdsCentralGrant, false);
  assert.equal(fixtures.administrator.isArchiveAdministrator, true);

  const adminDenials = fixtures.decisions.filter((row) => row.administrator.decision === "DENY");
  assert.ok(
    adminDenials.length > 0,
    "an administrator with no data grant must be denied, or the comparison is dishonest",
  );

  const subjectPermits = fixtures.decisions.filter((row) => row.subject.decision === "PERMIT");
  assert.ok(
    subjectPermits.some((row) => row.administrator.decision === "DENY"),
    "the interesting case - the subject reaches what the administrator cannot - must be shown",
  );
});

test("the design does not require CENTRAL as a prerequisite for administration", () => {
  assert.match(designDoc, /Require the administrator to hold `CENTRAL`[\s\S]{0,120}Rejected/);
});

// ── §4.5 impersonation drift ─────────────────────────────────────────

test("the prototype mentions no token, session or impersonation mechanism", () => {
  for (const banned of [
    "ValidatedCognitoIdentity",
    "SecurityContextHolder",
    "accessToken",
    "idToken",
    "actAs",
    "X-Act-As",
    "impersonat",
  ]) {
    assert.ok(
      !prototypeSource.includes(banned),
      `"${banned}" has no business in a preview that is not impersonation`,
    );
  }
});

test("the prototype fetches nothing - it is a prototype, not a client", () => {
  for (const banned of ["fetch(", "axios", "useQuery", "api/admin", "apiClient"]) {
    assert.ok(
      !prototypeSource.includes(banned),
      `the prototype must not call anything; found ${banned}`,
    );
  }
});

// ── §3 honesty about enforcement ─────────────────────────────────────

test("enforcement state is shown before any decision, not as a footnote", () => {
  const enforcementAt = prototypeSource.indexOf("Enforcement is OFF");
  const decisionsAt = prototypeSource.indexOf("Decisions for the records you asked about");

  assert.ok(enforcementAt > 0, "the enforcement banner must exist");
  assert.ok(
    enforcementAt < decisionsAt,
    "a simulated decision read as live behaviour is worse than no preview",
  );
});

test("the policy actually used is shown with the result", () => {
  for (const key of ["versionScope", "departmentMatch", "contactDerivation", "researchStaffRoles"]) {
    assert.ok(key in fixtures.enforcement.policy, `${key} must be stated`);
    assert.ok(prototypeSource.includes(key), `${key} must be rendered`);
  }
  assert.equal(fixtures.enforcement.enabled, false);
  assert.equal(fixtures.enforcement.policySource, "SIMULATED");
});

// ── §4.3 and §4.4 limits and audit ───────────────────────────────────

test("the design states a per-request identifier cap, rate limits and a daily ceiling", () => {
  assert.match(designDoc, /Identifiers per request \| 10/);
  assert.match(designDoc, /30\/minute, 300\/hour/);
  assert.match(designDoc, /2,000, then refuse/);
  assert.match(designDoc, /429/);
});

test("the design requires audit, FAIL_CLOSED, and records no record content", () => {
  assert.match(designDoc, /FAIL_CLOSED/);
  assert.match(designDoc, /a failed audit write \*\*refuses the preview\*\*/);
  assert.match(designDoc, /record titles or contents/);
});

test("the prototype tells the administrator they are being audited", () => {
  assert.match(prototypeSource, /What is recorded when you use this/);
  assert.match(prototypeSource, /if the audit cannot be written the preview\s*\n?\s*does not happen/);
});

// ── §4.6 no mutation ─────────────────────────────────────────────────

test("nothing in the prototype suggests a grant can be changed from here", () => {
  for (const banned of ["grantAccess", "revokeAccess", "ArchiveAdminService", "onGrant", "onRevoke"]) {
    assert.ok(!prototypeSource.includes(banned), `found ${banned}`);
  }
});

// ── fixtures are synthetic ───────────────────────────────────────────

test("every identifier in the fixtures is synthetic", () => {
  const text = JSON.stringify(fixtures);
  for (const identifier of [
    ...(text.match(/"ARP-[A-Z0-9-]+"/g) ?? []),
    ...(text.match(/"SYN-[A-Z0-9-]+"/g) ?? []),
  ]) {
    assert.match(
      identifier,
      /SYN/,
      `${identifier} does not look synthetic - no real person or record may appear here`,
    );
  }
  assert.ok(!/@bu\.edu/.test(text), "no BU address belongs in a committed fixture");
});

test("the fixture that proves the existence guarantee is annotated, so nobody tidies it away", () => {
  const missing = fixtures.decisions.find((row) => row.identifier === "SYN-AWD-DOES-NOT-EXIST");
  assert.ok(missing.note, "a duplicate-looking row needs a reason attached");
  assert.match(missing.note, /indistinguishable/i);
});
