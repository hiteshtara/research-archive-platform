/*
 * The three situations the API distinguishes, and the one it deliberately
 * does not.
 *
 * The test that matters most here is the negative one: a 404 must never be
 * rendered as "you may not have permission". An out-of-scope record and a
 * record that never existed answer identically from the API on purpose, and
 * a help message that explains the difference would be a disclosure channel
 * wearing the costume of good error handling.
 */

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  ACCESS_STATES,
  accessNotice,
  classifyAccessError,
  shouldReplaceEmptyState,
  supportReferenceFor,
} from "./accessStatePresentation.mjs";

const read = (relative) => readFileSync(fileURLToPath(new URL(relative, import.meta.url)), "utf8");

const NOT_PROVISIONED = { status: 403, code: "ACCESS_NOT_PROVISIONED", correlationId: "corr-1" };
const IDENTITY_DENIED = { status: 403, code: "ACCESS_DENIED", correlationId: "corr-2" };
const OUT_OF_SCOPE = { status: 404, correlationId: "corr-3" };
const ABSENT = { status: 404, correlationId: "corr-4" };

// ── classification ───────────────────────────────────────────────────

test("an unprovisioned account is told so, and is not confused with a denial", () => {
  assert.equal(classifyAccessError(NOT_PROVISIONED), ACCESS_STATES.NOT_PROVISIONED);
  assert.equal(classifyAccessError(IDENTITY_DENIED), ACCESS_STATES.IDENTITY_DENIED);
});

test("an unrecognised 403 is a denial, never 'nobody enrolled you'", () => {
  /*
   * Guessing the wrong way sends someone to the wrong person: "ask an
   * administrator to enrol you" is useless advice if the real reason was a
   * suspension.
   */
  assert.equal(classifyAccessError({ status: 403 }), ACCESS_STATES.IDENTITY_DENIED);
  assert.equal(classifyAccessError({ status: 403, code: "SOMETHING_NEW" }), ACCESS_STATES.IDENTITY_DENIED);
});

test("401 asks for a fresh sign-in; anything else is a generic failure", () => {
  assert.equal(classifyAccessError({ status: 401 }), ACCESS_STATES.REAUTHENTICATION_REQUIRED);
  assert.equal(classifyAccessError({ status: 500 }), ACCESS_STATES.OTHER);
  assert.equal(classifyAccessError(null), ACCESS_STATES.OTHER);
  assert.equal(classifyAccessError({}), ACCESS_STATES.OTHER);
});

// ── the disclosure guarantee ─────────────────────────────────────────

test("an out-of-scope record and an absent one produce an identical notice", () => {
  // The whole point. If these ever differ, the UI has started answering a
  // question the API refuses to answer.
  assert.deepEqual(accessNotice(OUT_OF_SCOPE), accessNotice(ABSENT));
});

test("a 404 notice says nothing about permission, access or authorisation", () => {
  const notice = accessNotice(OUT_OF_SCOPE, { recordLabel: "Award" });
  const text = `${notice.title} ${notice.body} ${notice.action ?? ""}`.toLowerCase();

  for (const forbidden of [
    "permission",
    "access",
    "authoris",
    "authoriz",
    "not allowed",
    "restricted",
    "granted",
    "administrator",
  ]) {
    assert.ok(
      !text.includes(forbidden),
      `"${forbidden}" in a 404 notice tells the caller the record might exist`,
    );
  }
  assert.match(notice.title, /not found/i);
});

test("a 404 offers no support reference, because there is nothing to chase", () => {
  /*
   * A reference invites "why can't I see this?" about a record that may not
   * exist. For the access states it is genuinely useful, because there an
   * administrator really does have to do something.
   */
  assert.equal(supportReferenceFor(OUT_OF_SCOPE), null);
  assert.equal(supportReferenceFor(NOT_PROVISIONED), "corr-1");
  assert.equal(supportReferenceFor(IDENTITY_DENIED), "corr-2");
});

// ── the message a No-Grant Control user sees ─────────────────────────

test("the unprovisioned notice says the archive is not empty, and who to ask", () => {
  const notice = accessNotice(NOT_PROVISIONED);

  assert.equal(notice.state, ACCESS_STATES.NOT_PROVISIONED);
  assert.match(notice.body, /not a fault/i);
  assert.match(notice.body, /archive is not empty/i);
  assert.match(notice.action, /administrator/i);
  assert.equal(notice.retryable, false, "retrying will not provision an account");
});

test("the unprovisioned notice never implies a fault or an outage", () => {
  const notice = accessNotice(NOT_PROVISIONED);
  const text = `${notice.title} ${notice.body}`.toLowerCase();

  for (const forbidden of ["error", "failed", "unavailable", "down", "problem", "try again"]) {
    assert.ok(!text.includes(forbidden), `"${forbidden}" reads as a broken site`);
  }
});

test("an empty screen is replaced for the access states, and not for a 404", () => {
  assert.equal(shouldReplaceEmptyState(NOT_PROVISIONED), true);
  assert.equal(shouldReplaceEmptyState(IDENTITY_DENIED), true);
  assert.equal(shouldReplaceEmptyState(OUT_OF_SCOPE), false);
  assert.equal(shouldReplaceEmptyState(null), false);
});

// ── every state is renderable ────────────────────────────────────────

test("every state produces a complete, non-empty notice", () => {
  const errors = [NOT_PROVISIONED, IDENTITY_DENIED, { status: 401 }, OUT_OF_SCOPE, { status: 500 }];
  const seen = new Set();

  for (const error of errors) {
    const notice = accessNotice(error);
    seen.add(notice.state);
    assert.ok(notice.title.length > 5, `${notice.state} has no title`);
    assert.ok(notice.body.length > 20, `${notice.state} has no body`);
    assert.ok(["info", "warning", "error"].includes(notice.severity));
  }
  assert.equal(seen.size, 5, "all five states must be reachable");
});

test("the record label is used, so the message names what was looked for", () => {
  assert.match(accessNotice(OUT_OF_SCOPE, { recordLabel: "Proposal" }).title, /Proposal/);
  assert.match(accessNotice(OUT_OF_SCOPE).title, /record/);
});

// ── the component ────────────────────────────────────────────────────

test("the component renders the notice and never invents its own wording", () => {
  const component = read("./AccessNotice.tsx");

  assert.match(component, /accessNotice\(/, "it must use the tested helper");
  assert.match(component, /supportReferenceFor\(/, "including the reference rule");
  assert.ok(
    !/you (do not|don't) have (permission|access)/i.test(component),
    "the component must not carry its own access wording",
  );
});

test("the component renders nothing when there is no error", () => {
  const component = read("./AccessNotice.tsx");
  assert.match(component, /if \(!error\) \{\s*\n?\s*return null;/);
});

// ── wired in, not dead code ──────────────────────────────────────────

test("the Dashboard uses the notice for the access states and keeps its generic error otherwise", () => {
  /*
   * The Dashboard is where "no access" is most easily mistaken for "the
   * archive is down": a user with no grants saw "the dashboard data could not
   * be loaded", which produces a bug report rather than an access request.
   *
   * The generic branch has to survive. Only the access states are safe to
   * explain - a 404 or a 500 still gets the neutral message.
   */
  const page = read("../../pages/DashboardPage.tsx");

  assert.match(page, /shouldReplaceEmptyState\(error\)/);
  assert.match(page, /<AccessNotice error=\{error\} \/>/);
  assert.match(
    page,
    /The dashboard data could not be loaded\./,
    "the generic failure message must still exist for everything else",
  );
});
