#!/usr/bin/env node
/*
 * Asserts that the temporary development-only QA routes are absent from a
 * production build.
 *
 * Run AFTER `npm run build`:
 *
 *     npm run build && npm run verify:dev-only-routes
 *
 * Why this exists rather than a code review note: "excluded from the
 * production build" is a claim about the bundler, and a claim about a build is
 * exactly the kind of thing that silently stops being true - a `lazy()` moved
 * out of its `import.meta.env.DEV` branch, or a nav entry added, and the code
 * ships again with nothing to say so.
 *
 * It mirrors the Java-side gate (ViewAccessAsAbsentFromJarTest), including the
 * part that matters most: POSITIVE CONTROLS. A scan that fails to read the
 * bundle, or reads an empty directory, would report "nothing forbidden found"
 * and pass. So it also asserts that strings which MUST be in the bundle are
 * found. Absence of evidence is not evidence of absence - this project has
 * already recorded three jar-inspection false negatives learned the hard way.
 */

import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const uiRoot = fileURLToPath(new URL("..", import.meta.url));
const dist = join(uiRoot, "dist");

/** Anything whose text appears in the bundle means the QA route shipped. */
const FORBIDDEN = [
  "view-access-as",
  "ViewAccessAs",
  "viewAccessAs",
  "127.0.0.1:8750",
  "Local QA harness not running",
  "SYN-AWD",
  "RecordScopeSqlBuilder",
];

/**
 * Must be present, or the inspection is not working. These are ordinary
 * production strings: the QA status route, which IS a production route on this
 * branch, and the application shell's own copy.
 */
const EXPECTED = ["qa-status", "Dashboard"];

function allFiles(directory) {
  let found = [];
  for (const entry of readdirSync(directory)) {
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) {
      found = found.concat(allFiles(path));
    } else {
      found.push(path);
    }
  }
  return found;
}

let distFiles;
try {
  distFiles = allFiles(dist);
} catch {
  console.error(
    `FAIL  no production build at ${dist}\n` +
      "      run `npm run build` first - this gate asserts on the artifact, " +
      "not on the source tree",
  );
  process.exit(2);
}

const textFiles = distFiles.filter((path) => /\.(js|mjs|css|html|json|map)$/.test(path));
if (textFiles.length === 0) {
  console.error(`FAIL  ${dist} holds no readable bundle files; the scan would prove nothing`);
  process.exit(2);
}

const corpus = textFiles
  .map((path) => ({ path, text: readFileSync(path, "utf8") }))
  .filter((file) => file.text.length > 0);

// Positive control FIRST: if this fails, every result below is meaningless.
const missingControls = EXPECTED.filter(
  (needle) => !corpus.some((file) => file.text.includes(needle)),
);
if (missingControls.length > 0) {
  console.error(
    "FAIL  positive control missing from the bundle: " +
      missingControls.join(", ") +
      "\n      the scan cannot see known production strings, so its silence " +
      "about the QA route proves nothing",
  );
  process.exit(2);
}

const offenders = [];
for (const needle of FORBIDDEN) {
  for (const file of corpus) {
    if (file.text.includes(needle)) {
      offenders.push(`${needle}  in  ${file.path.slice(uiRoot.length)}`);
    }
  }
}

// Chunk FILENAMES too: a lazily-imported page becomes its own file.
for (const path of distFiles) {
  if (/ViewAccessAs|viewAccessAs/.test(path)) {
    offenders.push(`chunk filename  ${path.slice(uiRoot.length)}`);
  }
}

if (offenders.length > 0) {
  console.error(
    "FAIL  development-only QA code reached the production build:\n" +
      offenders.map((line) => "      " + line).join("\n") +
      "\n\n      The route must stay inside an `import.meta.env.DEV` branch in " +
      "src/App.tsx\n      with a dynamic import, and must not appear in " +
      "navigationPresentation.mjs.",
  );
  process.exit(1);
}

console.log(
  `PASS  ${textFiles.length} bundle files inspected, ${corpus.length} non-empty; ` +
    `${EXPECTED.length} positive controls found; 0 of ${FORBIDDEN.length} ` +
    "forbidden markers present.",
);
