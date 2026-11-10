import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  HARNESS_ORIGIN,
  comparisonRows,
  evaluateQuery,
  familyFor,
  familyTally,
  harnessUnavailableMessage,
  harnessUrl,
  isPartialFamily,
  requestedProfiles,
  scopeSensitivityLabel,
  verdictTone,
} from "./viewAccessAsPresentation.mjs";

const read = (relative) =>
  readFileSync(fileURLToPath(new URL(relative, import.meta.url)), "utf8");

/*
 * Fixtures shaped like the harness response. Deliberately NOT a copy of the
 * harness's own fixtures: these exercise the shaping functions, and none of
 * them asserts what the engine should have decided.
 */
const PARTIAL_FAMILY = {
  familyKey: "FAM-1",
  label: "partial",
  leadUnit: "U-9",
  versions: [
    { versionKey: "1", verdict: "DENY", rule: "OUT_OF_SCOPE" },
    { versionKey: "2", verdict: "PERMIT", rule: "RESEARCH_STAFF_CONTACT" },
  ],
  permitted: 1,
  denied: 1,
  partialFamily: true,
  changesUnderOtherScope: false,
  otherScope: "FAMILY_WIDE",
  aiVerdict: "REFUSED",
  aiRule: "AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS",
};

const COMPLETE_FAMILY = {
  ...PARTIAL_FAMILY,
  familyKey: "FAM-2",
  versions: [{ versionKey: "3", verdict: "PERMIT", rule: "CENTRAL" }],
  permitted: 1,
  denied: 0,
  partialFamily: false,
  changesUnderOtherScope: true,
  aiVerdict: "ALLOWED",
  aiRule: "every version visible",
};

const RESULTS = [
  {
    profile: "Research Staff",
    description: "contact",
    grants: ["CONTACT_DERIVATION"],
    outcome: "Scoped",
    awards: [PARTIAL_FAMILY, COMPLETE_FAMILY],
    subawards: [
      { id: 9001, label: "funded by a permitted version", verdict: "PERMIT", rule: "inherited" },
      { id: 9002, label: "funded by a denied version", verdict: "DENY", rule: "inherited" },
    ],
    negotiations: [{ id: 7001, label: "associated", verdict: "PERMIT", rule: "association" }],
    renderedSql: { AWARD: "AND ..." },
  },
  {
    profile: "CENTRAL",
    description: "central",
    grants: ["CENTRAL"],
    outcome: "Scoped",
    awards: [PARTIAL_FAMILY, COMPLETE_FAMILY],
    subawards: [
      { id: 9001, label: "funded by a permitted version", verdict: "PERMIT", rule: "inherited" },
      { id: 9002, label: "funded by a denied version", verdict: "PERMIT", rule: "inherited" },
    ],
    negotiations: [{ id: 7001, label: "associated", verdict: "PERMIT", rule: "association" }],
    renderedSql: { AWARD: "" },
  },
];

// ── the harness address is fixed, by requirement ──────────────────────

test("the harness origin is loopback and is not configurable", () => {
  assert.equal(HARNESS_ORIGIN, "http://127.0.0.1:8750");
  const source = read("./viewAccessAsPresentation.mjs");
  assert.ok(
    !/import\.meta\.env\.[A-Z_]*HARNESS/.test(source),
    "the harness URL must not come from an environment variable",
  );
  assert.ok(
    !/VITE_/.test(source),
    "a configurable harness URL is how a QA simulator gets pointed at something " +
      "that is not a local throwaway process",
  );
});

test("harnessUrl refuses a path that is not rooted", () => {
  assert.equal(harnessUrl("/api/model"), "http://127.0.0.1:8750/api/model");
  assert.throws(() => harnessUrl("api/model"));
  assert.throws(() => harnessUrl("http://elsewhere.example/api/model"));
});

test("the unavailable message names the harness and how to start it", () => {
  const message = harnessUnavailableMessage();
  assert.equal(message.title, "Local QA harness not running");
  assert.ok(message.detail.includes(HARNESS_ORIGIN));
  assert.ok(message.command.includes("ViewAccessAsDemoServer"));
});

// ── shaping, not deciding ─────────────────────────────────────────────

test("verdictTone maps the engine's verdicts to chip colours only", () => {
  assert.equal(verdictTone("PERMIT"), "success");
  assert.equal(verdictTone("ALLOWED"), "success");
  assert.equal(verdictTone("DENY"), "warning");
  assert.equal(verdictTone("REFUSED"), "warning");
  assert.equal(verdictTone("INDETERMINATE"), "error");
  assert.equal(verdictTone("anything else"), "default");
});

test("familyFor finds the selected family and falls back to the first", () => {
  assert.equal(familyFor(RESULTS[0], "FAM-2"), COMPLETE_FAMILY);
  assert.equal(familyFor(RESULTS[0], "no-such-family"), PARTIAL_FAMILY);
  assert.equal(familyFor(null, "FAM-1"), null);
  assert.equal(familyFor({ awards: [] }, "FAM-1"), null);
});

test("familyTally counts the verdicts the engine returned", () => {
  assert.deepEqual(familyTally(PARTIAL_FAMILY), { permitted: 1, denied: 1, total: 2 });
  assert.deepEqual(familyTally(COMPLETE_FAMILY), { permitted: 1, denied: 0, total: 1 });
  assert.deepEqual(familyTally(null), { permitted: 0, denied: 0, total: 0 });
});

test("isPartialFamily is derived from the verdicts, not read from a flag", () => {
  /*
   * The harness also sends partialFamily, but this is computed from the
   * version verdicts instead. If the two ever disagreed, the verdicts are
   * the engine's output and the flag is a summary of it - so deriving keeps
   * the page honest about what it was told.
   */
  assert.equal(isPartialFamily(PARTIAL_FAMILY), true);
  assert.equal(isPartialFamily(COMPLETE_FAMILY), false);
  assert.equal(
    isPartialFamily({ ...PARTIAL_FAMILY, versions: [], partialFamily: true }),
    false,
  );
});

test("scopeSensitivityLabel reports the server's two-scope comparison", () => {
  assert.equal(
    scopeSensitivityLabel(PARTIAL_FAMILY),
    "Verdicts do not change under FAMILY_WIDE",
  );
  assert.equal(scopeSensitivityLabel(COMPLETE_FAMILY), "Verdicts change under FAMILY_WIDE");
  assert.equal(scopeSensitivityLabel(null), null);
  assert.equal(scopeSensitivityLabel({ familyKey: "x" }), null);
});

test("comparisonRows puts one verdict column per profile on each record", () => {
  const rows = comparisonRows(RESULTS, "subawards");
  assert.equal(rows.length, 2);
  assert.deepEqual(
    rows.map((row) => row.id),
    [9001, 9002],
  );
  assert.deepEqual(rows[1].verdicts, [
    { profile: "Research Staff", verdict: "DENY" },
    { profile: "CENTRAL", verdict: "PERMIT" },
  ]);
  assert.deepEqual(comparisonRows([], "subawards"), []);
  assert.deepEqual(comparisonRows(RESULTS, "missingKey"), []);
});

test("requestedProfiles drops an unset or duplicate comparison slot", () => {
  assert.deepEqual(requestedProfiles("IO", "CENTRAL"), ["IO", "CENTRAL"]);
  assert.deepEqual(requestedProfiles("IO", ""), ["IO"]);
  assert.deepEqual(requestedProfiles("IO", "IO"), ["IO"]);
});

test("evaluateQuery sends both profiles and both policy knobs", () => {
  const query = evaluateQuery({
    profileA: "Research Staff",
    profileB: "CENTRAL",
    versionScope: "FAMILY_WIDE",
    departmentMatch: "LEAD_UNIT_WITH_DESCENDANTS",
  });
  assert.match(query, /^\/api\/evaluate\?/);
  const params = new URLSearchParams(query.slice(query.indexOf("?") + 1));
  assert.equal(params.get("profileA"), "Research Staff");
  assert.equal(params.get("profileB"), "CENTRAL");
  assert.equal(params.get("versionScope"), "FAMILY_WIDE");
  assert.equal(params.get("departmentMatch"), "LEAD_UNIT_WITH_DESCENDANTS");
});

// ── the development-only guarantee ────────────────────────────────────

test("main.tsx registers the route only inside an import.meta.env.DEV branch", () => {
  const source = read("../../main.tsx");

  assert.match(
    source,
    /const ViewAccessAsPage = import\.meta\.env\.DEV\s*\n?\s*\? lazy\(\(\) => import\("\.\/pages\/ViewAccessAsPage"\)\)/,
    "the import must be dynamic AND inside the DEV branch, or Rollup keeps the chunk",
  );
  assert.ok(
    !/^import .*ViewAccessAsPage.* from/m.test(source),
    "a static top-level import would ship the page and only hide the link",
  );

  const routeBlock = source.match(/\{import\.meta\.env\.DEV && ViewAccessAsPage[\s\S]{0,400}/)?.[0];
  assert.ok(routeBlock, "expected the route to be guarded by import.meta.env.DEV");
  assert.match(routeBlock, /path="\/view-access-as"/);

  // Mounted beside the signed-out page, outside AuthGate, and App.tsx - the
  // gated application - must not reference it at all.
  const app = read("../../App.tsx");
  assert.ok(
    !/ViewAccessAs/.test(app),
    "the gated application must not carry the QA route",
  );
});

test("the route is absent from navigation, so no label ships either", () => {
  const nav = read("../navigation/navigationPresentation.mjs");
  assert.ok(
    !/view-access-as/.test(nav),
    "a nav entry would put the path and label in the production bundle even " +
      "though the route itself is dropped",
  );
});

test("the page hard-codes no authorization decision", () => {
  /*
   * The requirement the demonstration rests on: do not hard-code expected
   * decisions in the UI. A page that paired a record key with a verdict would
   * be asserting an answer rather than reporting the engine's.
   */
  const page = read("../../pages/ViewAccessAsPage.tsx");
  const helpers = read("./viewAccessAsPresentation.mjs");

  for (const [name, source] of [
    ["the page", page],
    ["the helpers", helpers],
  ]) {
    assert.ok(
      !/SYN-AWD-[A-Z]\s*(?:===|==|\?|:)/.test(source),
      `${name} must not branch on a fixture record key`,
    );
    assert.ok(
      !/(PERMIT|DENY)"\s*[:=]\s*(?:true|false)/.test(source),
      `${name} must not record an expected verdict`,
    );
  }

  /*
   * One literal family key IS allowed, and only one: the initially selected
   * family. It chooses what to show first, not what the answer is.
   */
  const keyMentions = page.match(/SYN-AWD-[A-Z]/g) ?? [];
  assert.equal(keyMentions.length, 1, "only the initial selection may name a family");
  assert.match(page, /useState\("SYN-AWD-P"\)/);
});

test("the verification script checks the built artifact with positive controls", () => {
  const script = read("../../../scripts/verify-dev-only-routes.mjs");
  assert.match(script, /const FORBIDDEN = \[/);
  assert.match(script, /const EXPECTED = \[/);
  assert.match(script, /positive control missing/);
  assert.ok(
    script.indexOf("positive control missing") < script.indexOf("const offenders"),
    "the positive control must be asserted BEFORE the absence check, or a " +
      "broken scan reads as a clean one",
  );
});
