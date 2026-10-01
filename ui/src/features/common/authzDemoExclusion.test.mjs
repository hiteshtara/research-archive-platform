import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

// The synthetic-identity demo sign-in must exist only in Vite mode
// "authz-demo". These checks pin the two guards; CI additionally greps the
// production dist/ for demo strings after `npm run build`.
const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");

test("the demo auth replacement is installed only in authz-demo mode", () => {
  const config = read("../../../vite.config.ts");
  assert.match(config, /mode === 'authz-demo' \? \[authzDemoAuth\(\), react\(\)\] : \[react\(\)\]/);
});

test("the demo identity bar is loaded only behind a build-time mode constant", () => {
  const main = read("../../main.tsx");
  assert.match(main, /if \(import\.meta\.env\.MODE === "authz-demo"\) \{\s*void import\("\.\/demo\/authz-demo\/DemoIdentityBar"\)/);
  assert.doesNotMatch(main, /^import .*demo\/authz-demo/m);
});

test("no application module imports the demo files directly", () => {
  for (const file of ["../../App.tsx", "../../api/client.ts", "../../AuthGate.tsx"]) {
    assert.doesNotMatch(read(file), /authz-demo/);
  }
});
