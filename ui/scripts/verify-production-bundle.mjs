#!/usr/bin/env node
/*
 * Does the production bundle actually contain the application?
 *
 * `vite build` exiting 0 does not answer that. ui/src/api/client.ts guards its
 * configuration at module scope:
 *
 *     const API_BASE_URL = import.meta.env.VITE_API_BASE_URL;
 *     if (!API_BASE_URL) { throw new Error("VITE_API_BASE_URL is not configured."); }
 *
 * Vite substitutes `import.meta.env.VITE_*` at BUILD time. With the variable
 * unset the condition folds to a constant true, the throw becomes
 * unconditional, and the minifier is entitled to drop everything downstream of
 * it. ui/src/auth.ts does the same for six Cognito values.
 *
 * The result is a build that succeeds and ships a ~613 KB stub: React and MUI,
 * an unconditional throw, and none of the archive. Every page 404s or blanks.
 * CI built exactly that for months without noticing, because it checked the
 * exit code and the artifact was never opened.
 *
 * So this script opens it. It asserts the configuration guards are GONE -
 * their survival is the precise, direct signature of a build whose variables
 * were missing - and that every route and module marker is present.
 *
 * All markers below were confirmed by `grep -c` against a real build before
 * being written here. None is guessed.
 */

import { readFileSync, existsSync } from "node:fs";
import { join, resolve } from "node:path";

const distDir = resolve(process.argv[2] ?? "dist");

/**
 * Strings that exist ONLY when a required VITE_* variable was empty at build
 * time. In a correctly configured build the condition folds to false and the
 * minifier removes the message with it, so finding one is proof of a stub.
 */
const CONFIG_GUARDS_THAT_MUST_BE_GONE = [
  "VITE_API_BASE_URL is not configured.",
];

/**
 * Route paths exactly as App.tsx declares them, parameters included. A missing
 * entry means that route was dropped from the bundle - the page would 404 in
 * the browser while CI stayed green.
 */
const ROUTES = [
  "qa-status",
  "awards/search",
  "awards/versions/search",
  "awards/hierarchy/:awardNumber",
  "awards/:awardId",
  "proposals/:proposalNumber",
  "proposals/dashboard/:proposalId",
  "negotiations/:negotiationId",
  "subawards/:subawardId",
  "documents",
  "archived-files",
  // Present only because the CI build sets VITE_EXPLORER_ENABLED=true, matching
  // DEV. App.tsx gates this route on that flag, so flipping it in CI must come
  // with removing this line - the check asserts what CI actually builds.
  "explorer",
];

/**
 * One distinctive piece of user-facing copy per module. Copy rather than a
 * component name, because minification renames identifiers but never rewrites
 * string literals.
 */
const MODULE_MARKERS = [
  ["Dashboard", "Welcome to the Research Archive"],
  ["Award search", "Find an Award"],
  ["Award hierarchy", "Award hierarchy"],
  ["Historical awards", "Historical Award Records"],
  ["Proposal search", "Find a Proposal"],
  ["Negotiations", "Search archived negotiations and their associated Kuali records."],
  ["Subaward search", "Find a Subaward"],
  ["Document explorer", "Kuali Documents"],
  ["Archived file finder", "Find an Archived File"],
  ["QA status / Security readiness", "Security readiness"],
];

/**
 * A coarse second guard, well under a real build (~1.08 MB) and well over the
 * stub (~613 KB). The marker checks above are the real test; this catches a
 * build that somehow keeps the strings but loses its bulk.
 */
const MIN_ENTRY_BYTES = 850_000;

function fail(lines) {
  console.error("\nProduction bundle verification FAILED\n");
  for (const line of lines) console.error(`  ${line}`);
  console.error(
    "\nThe usual cause is a build with no VITE_* environment variables.\n" +
      "Vite folds them in at build time, so an unset VITE_API_BASE_URL turns\n" +
      "client.ts's configuration guard into an unconditional throw and the\n" +
      "application is dead-code-eliminated behind it. The build still exits 0.\n" +
      "\nSee the `env:` block on the UI job in .github/workflows/ci.yml.\n",
  );
  process.exit(1);
}

if (!existsSync(distDir)) {
  fail([`No build output at ${distDir}. Run \`npm run build\` first.`]);
}

const indexHtmlPath = join(distDir, "index.html");
if (!existsSync(indexHtmlPath)) {
  fail([`No index.html in ${distDir}.`]);
}
const indexHtml = readFileSync(indexHtmlPath, "utf8");

/*
 * Read the entry the HTML actually loads rather than globbing for any .js -
 * an orphaned chunk from an earlier build would otherwise be able to satisfy
 * these checks while the page loaded something else entirely.
 */
const entryMatch = indexHtml.match(/<script[^>]+src="\/?([^"]+\.js)"/);
if (!entryMatch) {
  fail(["index.html references no JavaScript entry point."]);
}
const entryPath = join(distDir, entryMatch[1].replace(/^\//, ""));
if (!existsSync(entryPath)) {
  fail([`index.html references ${entryMatch[1]}, which is not in the build output.`]);
}

const bundle = readFileSync(entryPath, "utf8");
const bytes = Buffer.byteLength(bundle);
const problems = [];

for (const guard of CONFIG_GUARDS_THAT_MUST_BE_GONE) {
  if (bundle.includes(guard)) {
    problems.push(
      `the configuration guard "${guard}" survived minification - ` +
        "the build ran without its VITE_* variables and the application was " +
        "dead-code-eliminated",
    );
  }
}

if (bytes < MIN_ENTRY_BYTES) {
  problems.push(
    `entry chunk is ${bytes.toLocaleString()} bytes, below the ${MIN_ENTRY_BYTES.toLocaleString()} floor`,
  );
}

const missingRoutes = ROUTES.filter((route) => !bundle.includes(route));
for (const route of missingRoutes) {
  problems.push(`route "${route}" is missing from the bundle`);
}

const missingModules = MODULE_MARKERS.filter(([, marker]) => !bundle.includes(marker));
for (const [name, marker] of missingModules) {
  problems.push(`module ${name} is missing (looked for "${marker}")`);
}

if (problems.length > 0) {
  fail(problems);
}

console.log(
  `Production bundle verified: ${entryMatch[1]}, ${bytes.toLocaleString()} bytes, ` +
    `${ROUTES.length} routes and ${MODULE_MARKERS.length} modules present, ` +
    `no configuration guard left behind.`,
);
