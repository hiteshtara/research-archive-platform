/*
 * Guards against the failure this file was written after.
 *
 * On 2026-11-09 the Security Readiness panel went on reporting API revision 84,
 * image commit 66f3cfe and "30 commits behind main" for hours after revision 85
 * was deployed from main's tip. Nothing failed, because every claim was a
 * free-text string and no test compared one claim against another.
 *
 * These tests cannot check that the data is CORRECT - no unit test knows what
 * is running in AWS. Be clear about what they do and do not buy, because the
 * difference is the whole reason the page was wrong:
 *
 *   - Run against the pre-correction data, 6 of these 15 rules fail and 9
 *     pass. The 9 pass because that data was internally CONSISTENT: it said
 *     revision 84 and commit 66f3cfe everywhere, truthfully reported itself as
 *     30 commits behind, and was simply old. Consistency rules cannot detect
 *     that.
 *   - What they do catch is the far likelier future mistake: a PARTIAL
 *     refresh. Bump the deployment block to a new revision and leave the
 *     evidence strings naming the old image, or flip a capability to deployed
 *     without merging it, and the build fails naming the contradiction.
 *   - Staleness itself is addressed three other ways: the panel now states its
 *     own provenance and date (requirement 7), verificationNotice already
 *     warns past a fortnight, and the release checklist carries a step to
 *     refresh this file - which is asserted below, because the omitted step
 *     is what actually happened.
 */

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import readiness from "./securityReadiness.json" with { type: "json" };
import { deploymentSummary, provenanceNotice, smokeTestSummary } from "./securityReadinessPresentation.mjs";

const read = (relative) =>
  readFileSync(fileURLToPath(new URL(relative, import.meta.url)), "utf8");

const DEPLOYMENT = readiness.deployment;
const CAPABILITIES = readiness.capabilities;
const STAGES = ["implemented", "merged", "deployed", "migrationApplied", "configured", "tested", "enforcing"];

/*
 * Two subtrees are explicitly historical, and are the only places a superseded
 * fact belongs: resolvedMismatches ("this is what we used to report, and why we
 * no longer do") and priorVerifications ("this is what was checked, on the
 * revision it names"). Every staleness rule below reads the document with both
 * removed, so history stays writable and live claims do not.
 *
 * Nothing else may name an older revision. That asymmetry is the point - a
 * stale claim is indistinguishable from a current one unless the historical
 * places are enumerated rather than inferred.
 */
const HISTORICAL_SUBTREES = ["resolvedMismatches", "priorVerifications"];

function withoutHistory() {
  const copy = JSON.parse(JSON.stringify(readiness));
  for (const key of HISTORICAL_SUBTREES) delete copy[key];
  return copy;
}

const liveText = JSON.stringify(withoutHistory());

/** Every string in the document except the historical subtrees. */
function liveStrings() {
  const copy = withoutHistory();
  const out = [];
  const walk = (node, path) => {
    if (typeof node === "string") {
      out.push({ path, value: node });
    } else if (Array.isArray(node)) {
      node.forEach((item, i) => walk(item, `${path}[${i}]`));
    } else if (node && typeof node === "object") {
      for (const [key, value] of Object.entries(node)) walk(value, `${path}.${key}`);
    }
  };
  walk(copy, "$");
  return out;
}

// ── the deployment identity hangs together ───────────────────────────

test("the short commit is a prefix of the full commit, and the digest is a real digest", () => {
  /*
   * A short commit nobody can expand is unverifiable. Carrying the full SHA
   * means a reader can run `git merge-base --is-ancestor` themselves rather
   * than trusting the panel.
   */
  assert.match(DEPLOYMENT.imageCommitFull, /^[0-9a-f]{40}$/, "the full commit must be a 40-character SHA");
  assert.ok(
    DEPLOYMENT.imageCommitFull.startsWith(DEPLOYMENT.imageCommit),
    `imageCommit ${DEPLOYMENT.imageCommit} is not a prefix of ${DEPLOYMENT.imageCommitFull}`,
  );
  assert.match(DEPLOYMENT.imageDigest, /^sha256:[0-9a-f]{64}$/, "the digest must be a full sha256");
  assert.match(DEPLOYMENT.apiTaskDefinition, /^research-archive-platform-dev-api:\d+$/);
});

test("every task-definition revision named anywhere is the deployed one", () => {
  /*
   * This is the exact defect. enforcement.verifiedHow said "task definition
   * 84" and SEC-001's evidence said "task definition 84" while the deployment
   * block had moved to 85.
   */
  const recorded = DEPLOYMENT.apiTaskDefinition.split(":")[1];
  const claims = [
    ...liveText.matchAll(/research-archive-platform-dev-api:(\d+)/g),
    ...liveText.matchAll(/task definition (\d+)/gi),
  ].map((m) => m[1]);

  assert.ok(claims.length > 0, "the revision should be stated somewhere");
  for (const claim of claims) {
    assert.equal(claim, recorded, `a revision ${claim} claim survives while ${recorded} is deployed`);
  }
});

test("every commit named in deployment evidence is the deployed commit", () => {
  /*
   * Nine evidence strings named 66f3cfe after the image became c1d25e6.
   * evidence.deployed describes the deployed image by definition, so a commit
   * mentioned there can only be the deployed one.
   */
  const deployedCommit = DEPLOYMENT.imageCommit;
  const sources = [
    ["deployment.note", DEPLOYMENT.note ?? ""],
    ...CAPABILITIES.map((c) => [`${c.id}.evidence.deployed`, c.evidence.deployed ?? ""]),
  ];

  for (const [where, text] of sources) {
    for (const [, commit] of text.matchAll(/\b(?:image|commit)\s+(?:commit\s+)?([0-9a-f]{7,40})\b/gi)) {
      assert.ok(
        deployedCommit.startsWith(commit) || commit.startsWith(deployedCommit),
        `${where} names commit ${commit}, but ${deployedCommit} is deployed`,
      );
    }
  }
});

test("when the image is main's tip, nothing claims code is missing from it", () => {
  if ((DEPLOYMENT.commitsBehindMain ?? 0) !== 0) {
    return;
  }
  /*
   * "30 commits behind main", "not an ancestor of the running image" and "the
   * deployed API is older than the deployed database" were all true of
   * revision 84 and all false the moment 85 landed. A reader cannot tell which
   * sentences expired, so the test does.
   */
  const forbidden = [
    /\d+\s+commits? behind/i,
    /behind main/i,
    /not (?:an )?ancestor of the running image/i,
    /\bnot in the running image\b/i,
    /\bNOT in the running image\b/,
    /older than the deployed database/i,
    /needs a rebuilt image/i,
  ];
  for (const { path, value } of liveStrings()) {
    for (const pattern of forbidden) {
      assert.ok(
        !pattern.test(value),
        `${path} still says "${value.match(pattern)?.[0]}" while the running image is main's tip`,
      );
    }
  }
});

// ── capability states cannot contradict the deployment ───────────────

test("nothing is deployed that is not merged, when the image is built from main", () => {
  /*
   * SEC-002, SEC-003, SEC-005, SEC-008 and SEC-009 all read merged:"no" while
   * the running image was main's tip - which cannot both be true. The
   * implication only holds for a main build, so it is guarded.
   */
  if (DEPLOYMENT.imageIsMainTip !== true) {
    return;
  }
  for (const capability of CAPABILITIES) {
    const { merged, deployed } = capability.states;
    if (deployed === "yes") {
      assert.equal(merged, "yes", `${capability.id} is deployed from main yet recorded as merged:"${merged}"`);
    }
    if (merged === "no") {
      assert.equal(
        deployed,
        "no",
        `${capability.id} is unmerged yet recorded as deployed:"${deployed}" from a main build`,
      );
    }
  }
});

test("a stage recorded as done does not carry evidence saying it is not", () => {
  // The cheapest contradiction to introduce, and the hardest to notice by eye.
  for (const capability of CAPABILITIES) {
    for (const stage of STAGES) {
      if (capability.states[stage] !== "yes") continue;
      const evidence = capability.evidence[stage];
      if (!evidence) continue;
      const contradiction = evidence.match(/\bnot (?:yet )?(?:merged|applied|deployed|in main)\b/i);
      assert.equal(
        contradiction,
        null,
        `${capability.id}.${stage} is "yes" but its evidence says "${contradiction?.[0]}"`,
      );
    }
  }
});

test("a migration cannot be both applied and pending", () => {
  /*
   * V088 sat in migrationsPending while SEC-005 was being flipped to
   * migrationApplied:"yes". One of the two is wrong and the page cannot say
   * which.
   */
  const pending = new Set(
    [...String(readiness.database.migrationsPending ?? "").matchAll(/V(\d+)/g)].map((m) => Number(m[1])),
  );
  const appliedRange = String(readiness.database.migrationsApplied ?? "").match(/V(\d+)\s*-\s*V(\d+)/);
  assert.ok(appliedRange, "migrationsApplied should state a range");
  const [from, to] = [Number(appliedRange[1]), Number(appliedRange[2])];

  for (const version of pending) {
    assert.ok(
      version < from || version > to,
      `V0${version} is listed as pending but falls inside the applied range V0${from}-V0${to}`,
    );
  }

  for (const capability of CAPABILITIES) {
    if (capability.states.migrationApplied !== "yes") continue;
    for (const [, version] of String(capability.evidence.migrationApplied ?? "").matchAll(/V(\d+)/g)) {
      assert.ok(
        !pending.has(Number(version)),
        `${capability.id} claims V0${version} is applied while the database block lists it as pending`,
      );
    }
  }
});

// ── the states that must not drift ───────────────────────────────────

test("record-level enforcement and activity auditing are still recorded as off", () => {
  /*
   * The point of correcting this page is accuracy, not optimism. Enforcement
   * being off and auditing not collecting are the two facts a reader most
   * needs, and a later edit that quietly flipped either would be far worse
   * than the stale revision number this file exists to prevent.
   */
  assert.equal(readiness.enforcement.state, "off");

  for (const capability of CAPABILITIES) {
    assert.notEqual(
      capability.states.configured,
      "yes",
      `${capability.id} claims to be switched on in DEV; no APP_AUTHORIZATION_* exists on the task definition`,
    );
  }

  const audit = CAPABILITIES.find((c) => c.id === "SEC-008");
  assert.equal(audit.states.configured, "no", "audit collection is off");
  assert.equal(audit.states.migrationApplied, "no", "V089 is not applied anywhere");
  assert.equal(audit.states.enforcing, "no");

  // SEC-009 alone is in force, and by database privilege rather than the flag.
  assert.deepEqual(
    CAPABILITIES.filter((c) => c.states.enforcing === "yes").map((c) => c.id),
    ["SEC-009"],
  );
});

test("the held-back migration is still described as held back", () => {
  const text = String(readiness.database.v089Held ?? "");
  assert.match(text, /migrations-pending-approval/, "where V089 lives is the whole guarantee");
  assert.match(text, /V089/);
});

// ── dates are real and ordered ───────────────────────────────────────

test("every recorded date parses and none is later than the page's own verification date", () => {
  const top = Date.parse(`${readiness.verifiedAt}T00:00:00Z`);
  assert.ok(!Number.isNaN(top), "the top-level verifiedAt must be an ISO date");

  for (const { path, value } of liveStrings()) {
    if (!/(verifiedAt|performedAt|resolvedOn|asOf|AppliedAt|Date|Pushed)$/.test(path)) continue;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) continue;
    const when = Date.parse(`${value}T00:00:00Z`);
    assert.ok(!Number.isNaN(when), `${path} is not a valid date: ${value}`);
    assert.ok(when <= top, `${path} is ${value}, later than the page's verifiedAt ${readiness.verifiedAt}`);
  }
});

// ── provenance is stated, not implied ────────────────────────────────

test("the panel says the deployment facts are a recorded snapshot, not a live reading", () => {
  /*
   * Requirement: prefer runtime-sourced metadata where safely available.
   * It is not available - /actuator/info is exposed but empty and the rest of
   * these facts come from AWS APIs the browser has no credentials for - so the
   * fallback obligation is to label the snapshot with its date. This asserts
   * the label exists rather than trusting prose.
   */
  const provenance = provenanceNotice(readiness);
  assert.equal(provenance.sourced, "snapshot");
  assert.match(provenance.label, /verified 2026-/);
  assert.ok(provenance.detail.length > 40, "a snapshot must say why it is one");
  assert.ok(provenance.followUp, "and what would make it live");

  assert.equal(readiness.runtimeSource.available, false);
  assert.match(readiness.runtimeSource.reason, /actuator\/info/);
});

test("the section renders the provenance line and tolerates a resolved mismatch", () => {
  const section = read("./SecurityReadinessSection.tsx");
  assert.match(section, /provenanceNotice/, "the provenance line must reach the page");
  assert.match(
    section,
    /const mismatch = readiness\.knownMismatch as KnownMismatch \| null/,
    "knownMismatch is null once resolved, so it must be typed as nullable",
  );
  assert.match(section, /\{mismatch \? \(/, "and rendered behind a guard - unguarded it would crash the page");
  assert.ok(
    !/\{readiness\.knownMismatch\./.test(section),
    "knownMismatch must never be dereferenced without the guard",
  );
  assert.match(section, /resolvedMismatches/, "a corrected claim is shown as resolved, not deleted");
});

// ── the smoke test is tied to the revision it tested ─────────────────

test("the recorded smoke test refers to the deployed revision and adds up", () => {
  const smoke = smokeTestSummary(readiness.smokeTest);
  assert.ok(smoke, "a deployment claim should carry the test that checked it");
  assert.equal(
    smoke.revision,
    DEPLOYMENT.apiTaskDefinition,
    "a smoke test against a different revision is not evidence for this one",
  );
  assert.equal(smoke.passed + smoke.failed + smoke.blocked, smoke.checks);
  assert.equal(smoke.allPassed, smoke.failed === 0 && smoke.passed === smoke.checks);
  assert.equal(readiness.smokeTest.recordsStillVisible, true);
});

test("deploymentSummary reports being up to date without inventing it", () => {
  const summary = deploymentSummary(DEPLOYMENT);
  assert.equal(summary.revision, "research-archive-platform-dev-api:89");
  assert.equal(summary.commit, "94c22a5");
  assert.equal(summary.commitFull, "94c22a542ae0e6b7e78697bde7809ff750f0861a");
  assert.equal(summary.behind, 0);
  assert.equal(summary.isCurrent, true);
  assert.equal(summary.runningTasks, "1 of 1");
});

// ── tied to things outside this file ─────────────────────────────────

test("the recorded image commit exists and is an ancestor of main", () => {
  /*
   * Catches an invented, truncated or typo'd SHA, and a commit that was never
   * merged. Deliberately NOT "is the tip of main": once merged a commit stays
   * an ancestor forever, so this rule cannot start failing on an unrelated
   * pull request the way a tip check would.
   */
  const run = (args) => {
    const result = spawnSync("git", args, { cwd: fileURLToPath(new URL(".", import.meta.url)) });
    return { ok: result.status === 0, out: String(result.stdout ?? "").trim() };
  };
  if (!run(["rev-parse", "--is-inside-work-tree"]).ok) {
    return; // not a checkout; nothing to compare against
  }
  /*
   * CI checks out at depth 1, so the history this rule needs is genuinely not
   * in the object store and asking for it fails against a perfectly correct
   * file. Skip rather than fail: an absent object in a shallow clone is
   * evidence about the clone, not about the recorded commit. Confirmed by
   * cloning --depth 1 and watching this assertion fail on data that is right.
   */
  if (run(["rev-parse", "--is-shallow-repository"]).out === "true") {
    return;
  }

  const exists = run(["cat-file", "-e", `${DEPLOYMENT.imageCommitFull}^{commit}`]);
  assert.ok(exists.ok, `the recorded image commit ${DEPLOYMENT.imageCommitFull} is not in this repository`);

  const mainRef = ["origin/main", "main"].find((ref) => run(["rev-parse", "--verify", ref]).ok);
  if (!mainRef) {
    return; // no main to compare against in this checkout
  }
  assert.ok(
    run(["merge-base", "--is-ancestor", DEPLOYMENT.imageCommitFull, mainRef]).ok,
    `the recorded image commit is not an ancestor of ${mainRef}; DEV would be running unmerged code`,
  );
});

test("the release checklist tells the next deployer to refresh this file", () => {
  /*
   * The omitted step, made a test. This page went stale because deploying a
   * new revision and updating the readiness snapshot were two separate acts
   * and only the first was on the checklist.
   */
  const checklist = read("../../../../docs/runbooks/DEV_RELEASE_CHECKLIST_SEC001_SEC009.md");
  assert.match(
    checklist,
    /securityReadiness\.json/,
    "the release checklist must name this file as a post-deployment step",
  );
});

test("an earlier verification is kept, named with the revision it was run against", () => {
  /*
   * The 13-check matrix of 2026-11-09 was run against revision 85. Deleting it
   * when 87 shipped would lose the record; promoting it to the current
   * smokeTest would claim it covered code it never saw. So it moves here, and
   * the current smokeTest is the one that actually ran against what is
   * deployed.
   */
  const prior = readiness.priorVerifications ?? [];
  assert.ok(prior.length > 0, "the earlier verification must be kept");

  const current = readiness.deployment.apiTaskDefinition;
  for (const entry of prior) {
    assert.ok(entry.performedAt && entry.revision && entry.result);
    assert.notEqual(
      entry.revision,
      current,
      "a prior verification naming the CURRENT revision is not prior - it belongs in smokeTest",
    );
  }
});

// ── history stays history ────────────────────────────────────────────

test("the historical QA snapshot is byte-for-byte unchanged", () => {
  /*
   * The count pin in securityReadinessPresentation.test.mjs catches a changed
   * tally. This catches a quietly reworded finding that keeps the tally -
   * which is the more tempting edit when a dashboard is being made to look
   * better. Updating this hash is a deliberate, reviewable act.
   */
  const digest = createHash("sha256").update(read("./qaSnapshot.json")).digest("hex");
  assert.equal(
    digest,
    "86f94d8fa3ec43166fb827668d16883da9d278412b4e53a71f9a2f7025751ef0",
    "qaSnapshot.json changed - recorded QA outcomes must not be rewritten to make this page look green",
  );
});
