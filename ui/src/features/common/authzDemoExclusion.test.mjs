import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

// The synthetic-identity demo sign-in must exist only in Vite mode
// "authz-demo". These checks pin the two guards; CI additionally greps the
// production dist/ for demo strings after `npm run build`.
const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");

test("the demo auth replacement is installed only in authz-demo mode", () => {
  const config = read("../../../vite.config.ts");
  assert.match(config, /if \(mode === 'authz-demo'\) return \[authzDemoAuth\(\)\]/);
  assert.match(config, /plugins: \[\.\.\.modePlugins\(mode\), react\(\)\]/);
});

test("the demo identity bar is loaded only behind a build-time mode constant", () => {
  const main = read("../../main.tsx");
  assert.match(main, /if \(import\.meta\.env\.MODE === "authz-demo"\) \{\s*void import\("\.\/demo\/authz-demo\/DemoIdentityBar"\)/);
  assert.doesNotMatch(main, /^import .*demo\/authz-demo/m);
});

test("the identity-lab endpoint override is installed only in identity-lab mode", () => {
  const config = read("../../../vite.config.ts");
  assert.match(config, /if \(mode === 'identity-lab'\) return \[identityLabUserPoolEndpoint\(\)\]/);
  assert.match(config, /return \[\]\n\}/);
});

test("the identity-lab banner is loaded only behind a build-time mode constant", () => {
  const main = read("../../main.tsx");
  assert.match(main, /if \(import\.meta\.env\.MODE === "identity-lab"\) \{\s*void import\("\.\/demo\/identity-lab\/LabBanner"\)/);
  assert.doesNotMatch(main, /^import .*demo\/identity-lab/m);
});

test("production auth.ts itself has no lab endpoint", () => {
  assert.doesNotMatch(read("../../auth.ts"), /userPoolEndpoint|VITE_LAB_/);
});

test("no application module imports the demo or lab files directly", () => {
  for (const file of ["../../App.tsx", "../../api/client.ts", "../../AuthGate.tsx", "../../auth.ts"]) {
    assert.doesNotMatch(read(file), /authz-demo|identity-lab/);
  }
});
