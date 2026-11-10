# View Access As — temporary QA route

**This is throwaway QA scaffolding.** It exists to demonstrate record-level
authorization (SEC-001…SEC-005, and SEC-003 version-specific research-staff
access) interactively, before enforcement is enabled anywhere. It is not a
product feature and must not become one. The removal checklist is at the
bottom; everything above it is what a reader needs to decide whether removal is
safe.

It follows the same convention as [`QA_STATUS_PAGE.md`](QA_STATUS_PAGE.md): a
temporary page, documented with its own removal list, so deletion is a
mechanical act rather than an archaeology exercise.

## What it is

| Piece | Scope | Ships in production? |
|---|---|---|
| `api/src/test/.../viewaccessas/demo/ViewAccessAsDemoServer.java` | **test** | no |
| `api/src/test/.../viewaccessas/demo/DemoModel.java` (synthetic fixtures) | **test** | no |
| `api/src/test/.../viewaccessas/demo/DemoEvaluator.java` | **test** | no |
| `api/src/test/resources/viewaccessas-demo/index.html` (standalone page) | **test** | no |
| `ui/src/pages/ViewAccessAsPage.tsx` | dev-only route | **no — dropped by the bundler** |
| `ui/src/features/viewAccessAs/*` | dev-only | **no — dropped by the bundler** |
| `ui/scripts/verify-dev-only-routes.mjs` | build gate | n/a (not bundled) |

The route is reachable at **`/view-access-as`** when `npm run dev` is running,
and nowhere else.

## The two guarantees, and what enforces each

**1. No simulator code in the production Java artifact.**
`edu.bu.archive.ops.ViewAccessAsAbsentFromJarTest` (from PR #107) asserts this
on the built jar, on `target/classes`, and on `src/main`, each with a positive
control. The demo classes live under `…/viewaccessas/demo/`, so the existing
`viewaccessas` match already covers them — no change to that test was needed,
and it still passes with 3/3 assertions against a real jar.

**2. No QA route in the production UI bundle.**
`ui/src/main.tsx` imports the page inside an `import.meta.env.DEV` branch, with
a **dynamic** import:

```tsx
const ViewAccessAsPage = import.meta.env.DEV
  ? lazy(() => import("./pages/ViewAccessAsPage"))
  : null;
```

Vite replaces `import.meta.env.DEV` with a literal `false` for production, so
the branch is dead code and Rollup never emits the chunk. A static top-level
`import` would ship the code and only hide the link — which is why
`viewAccessAsPresentation.test.mjs` asserts the dynamic form is intact, and why
the route is deliberately **not** in `navigationPresentation.mjs` (a nav entry
would put the path and label in the bundle even though the route is gone).

Verified on the artifact by `npm run verify:dev-only-routes`, which refuses to
pass unless it first finds known production strings — the same positive-control
discipline as the Java gate.

## Why it is mounted outside `AuthGate`

In `main.tsx` the route sits beside the signed-out page, outside the auth gate.
The page reads **nothing** from the production API: every decision comes from
the local harness on `127.0.0.1:8750`. A Cognito session would authorise
nothing here and its absence hides nothing, so gating it would only stop a
developer opening it.

This is not an auth bypass for the archive. The route renders one simulator
page, reaches no archive data, and does not exist at all in a production build.

## Security properties

- **No impersonation.** Profiles are grant *shapes*, not people. The identity
  is built from the synthetic issuer `simulation://view-access-as/not-a-user-pool`;
  no token is minted or parsed and nothing passes through
  `SecurityConfiguration`. There is nothing here to authenticate as.
- **No real grants or writes.** `InMemoryAccessGrantRepository` only. Nothing
  reaches `authz.access_grant` or `authz.identity_link`; the harness has no
  datasource at all.
- **No archive data.** Records are synthetic `RecordFacts` values in
  `DemoModel`. No archive row is read.
- **Enforcement unchanged.** `AuthorizationProperties` is constructed inside
  `DemoEvaluator.serviceFor`, lives for one evaluation, and is never read from
  or written to Spring configuration. DEV's task definition carries no
  `APP_AUTHORIZATION_*` variable and this code never runs there. Enforcement ON
  inside the harness is the only way to reach the AI gate — with the flag off
  every method short-circuits and the demonstration would report access for
  everything.
- **No configurable harness URL.** `HARNESS_ORIGIN` is a constant, asserted by
  test to come from no environment variable. A configurable one is how a QA
  simulator gets pointed at something that is not a local throwaway process.
- **CORS is an explicit loopback allowlist** (`http://localhost:5173`,
  `http://127.0.0.1:5173`), never `*` and never the request's origin echoed
  back unchecked.
- **No existence disclosure.** A denial the engine answers with
  `RecordNotAccessibleException` is a `NoSuchElementException` by design (D-J /
  P7) — the same 404 as a record that does not exist. The page reports it that
  way rather than inventing a more informative message.

## Running it

```bash
# 1. the harness (test scope)
cd api
mvn -o test-compile
mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=test -q
java -cp "target/classes:target/test-classes:$(cat cp.txt)" \
  edu.bu.archive.application.authorization.viewaccessas.demo.ViewAccessAsDemoServer

# 2. the UI
cd ../ui
npm run dev          # then open http://localhost:5173/view-access-as
```

The harness also serves its own standalone page at `http://127.0.0.1:8750/`,
which needs no UI build and no `.env.local`.

If the harness is not running, the route shows **"Local QA harness not
running"** with the command above — never a blank page. A blank page is
indistinguishable from "everything denied", which is the wrong conclusion to
let a reader draw from an authorization demonstration.

`npm run dev` needs a `ui/.env.local` with the six `VITE_COGNITO_*`/`VITE_AWS_REGION`
values (placeholders are fine — this route does not use them). That file is
gitignored.

## Removal checklist

Delete, in any order:

- [ ] `ui/src/pages/ViewAccessAsPage.tsx`
- [ ] `ui/src/features/viewAccessAs/` (3 files: `.mjs`, `.d.mts`, `.test.mjs`)
- [ ] `ui/scripts/verify-dev-only-routes.mjs`
- [ ] `api/src/test/java/edu/bu/archive/application/authorization/viewaccessas/demo/` (3 files)
- [ ] `api/src/test/resources/viewaccessas-demo/`
- [ ] this document

Then edit:

- [ ] `ui/src/main.tsx` — remove the `ViewAccessAsPage` constant, the guarded
      `<Route>`, and the `Suspense`/`lazy` import if nothing else uses them
- [ ] `ui/package.json` — remove `src/features/viewAccessAs/*.test.mjs` from
      `test`, and the `verify:dev-only-routes` script

Then verify:

- [ ] `cd ui && npm run test && npm run lint && npm run build`
- [ ] `grep -ri viewaccessas ui/src api/src` returns nothing except PR #107's
      own harness (`api/src/test/.../viewaccessas/`, which is a separate
      deliverable and is **not** removed by this checklist)
- [ ] `cd api && mvn -o test -Dtest=ViewAccessAsAbsentFromJarTest` still passes

**Do not remove PR #107's harness** (`ViewAccessAsHarness`, `SyntheticProfile`,
`Verdict`, `DecisionTable`, `ViewAccessAsAbsentFromJarTest`) as part of this
checklist. That is a separate, independently useful test-scope deliverable with
its own lifecycle; only the `demo/` subpackage and the UI route belong to this
one.

## Known limits

- **The SQL is rendered, not executed.** There is no database behind the page,
  so the Subaward and Negotiation predicates are shown as
  `RecordScopeSqlBuilder` renders them. The
  `*RecordAuthorizationIntegrationTest` suites execute the same builder against
  a real PostgreSQL; this shows the text.
- **`researchStaffRoles` is provisional** — `[PI, MPI, COI]`, still unverified
  against real Kuali values, as PR #107 already records.
- **Dashboard and Global Search are not covered.** They scope through SQL
  against a seeded database, which this page does not have.
