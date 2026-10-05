# QA status page

**Temporary.** This page exists for the current round of testing and is
expected to be deleted afterwards. "How to remove it" at the bottom lists
everything it touches; keep that list correct if the page grows.

The signed-in UI serves it at `/qa-status`, from a "QA status" entry at the
end of the sidebar. It reports a curated snapshot of recorded test results -
not current service health - in two separate sections.

## 1. Functional testing

Source: `QA_MATRIX_2026-09-29.xlsx`, in the owner's local
`research-archive-review/qa-2026-09-29` directory. The adjacent CSV predates
the workbook and must not replace its results.

The page carries 47 of the 48 cases. TC-022 is excluded at the owner's
direction: it is not shown, not counted, and not marked fixed or passed. Its
finding is unchanged in the source workbook, and the page's shipped JSON does
not describe its subject matter.

Statuses: `passed`, `evidence` (passed, more checks outstanding), `issue`,
`decision`, `blocked`, `notTested`. Every total on the page is computed from
the cases by `qaStatusPresentation.mjs` - never maintained alongside them.

Evidence scope is per case and deliberately distinct: `Development website`,
`Candidate build`, `Local test environment`. **A merge, a green CI run or a
local pass is not deployed-dev verification.** A case whose fix exists only on
a branch keeps the status the deployed site actually has - TC-017 is the
current example.

## 2. Access and security requirements

Source: the Security Requirements tab of *Kuali Archive Enhancements and
Requirements* (2026-10-01), held outside this repository.

All seven requirements appear as `SEC-001`..`SEC-007`, keeping the source
document's own wording verbatim in `requirement`. They are a separate
acceptance plan: they are summarised on their own, are never added to the
functional totals, and must not inherit a pass from any functional case.

Record-level access is **off** on dev, so every requirement reads
`Not verified`, and `implementation` may only say `In draft, not merged` or
`Not started` - draft pull requests and green CI do not establish live
enforcement. Where the source is ambiguous or conflicts with a recorded
decision, the conflict is stated on the requirement rather than resolved
silently (currently SEC-001, SEC-002 and SEC-006).

## Updating the snapshot

Edit `ui/src/features/qa/qaSnapshot.json` (cases) or
`ui/src/features/qa/securityRequirements.json` (requirements) after reviewing
the source and the evidence for what changed. Preserve IDs and the checklist's
own expected results. Write a concise, tester-facing note.

Do not put into either file - they are delivered to the browser:
raw logs, credentials or tokens, named test identities, the specific records a
named person may or may not open, internal filesystem paths, or the details of
an unresolved data-handling issue. `qaStatusPresentation.test.mjs` asserts
these absences; keep those lists current.

Record the tested build, environment and date on each case, and update
`evidenceUpdated` when evidence actually changes - not merely because the page
was rebuilt.

## Validating a change

```
cd ui
npm run test     # includes src/features/qa/*.test.mjs
npm run lint
npx tsc -b
npx vite build
```

Then exercise it in a browser: status totals against the filter buttons,
combined area + status + text filters, the no-results and reset behaviour,
keyboard access to a case (Tab to a case heading, Enter to open it), and the
page at phone width.

`qa-preview.html` + `src/qaPreview.tsx` mount the page on its own, without
`AuthGate`, so it can be driven locally (`npx vite`, then `/qa-preview.html`)
without a Cognito session. They are dev-server only and are not part of the
production build; confirm with `ls dist` after a build.

## How to remove it

- `ui/src/pages/QaStatusPage.tsx`
- `ui/src/features/qa/` (snapshot, requirements, presentation module, types, tests)
- `ui/qa-preview.html` and `ui/src/qaPreview.tsx`
- the `qa-status` route in `ui/src/App.tsx`
- the `qaStatus` entry in `ui/src/features/navigation/navigationPresentation.mjs`
- the `qaStatus` icon and its `FactCheckOutlined` import in `ui/src/layout/AppLayout.tsx`
- `src/features/qa/*.test.mjs` from the `test` script in `ui/package.json`
- in `ui/src/features/archivedFiles/archivedFileFinderPresentation.test.mjs`,
  restore the single exact-list navigation assertion in place of the two that
  replaced it
- this document
