# Research Archive Platform — UI

React + TypeScript + Vite frontend for the Research Archive Platform. See the
[repository root README](../README.md) for the overall system and data flow.

## Development

```
npm install
npm run dev      # start the Vite dev server
npm run build     # type-check and build for production
npm run test      # run presentation-helper unit tests
npm run lint      # oxlint
```

Configuration is read from `VITE_*` environment variables in `.env.local` /
`.env.development` (see `src/api/client.ts` and `src/pages/AwardHistoryPage.tsx`
for the variables currently in use).

## Visual regression: screenshot comparison rule

A pixel comparison is valid **only when the baseline and the candidate are
rendered through the same dev-server / runtime process.**

Two separate Vite processes serving byte-identical source can produce
slightly different output. Measured 2026-09-22: the same
`AwardSearchPage.tsx` (identical file hash) rendered at 1440x900 through
two different `npm run dev` processes differed by 14 of 1,296,000 pixels
(0.0011%) - scattered single pixels on one rounded chip edge, with no
layout shift or text reflow. Rendering both sides through one process
gives 0.

So:

- Capture the baseline, swap only the code under test, and capture the
  candidate **without restarting the dev server**.
- Do not compare a screenshot taken from one process against one taken
  from another and report the difference as an application regression.
- A handful of +/-1 greyscale pixels with no reflow is a rendering
  artifact. A real regression shows up as contiguous bands of differing
  rows where text or layout moved.

When a comparison is genuinely cross-process, say so and treat the
result as inconclusive rather than quoting the pixel count as a finding.

## Local screenshots and authentication

`AuthGate` requires a real Cognito session and has no dev bypass, so the
UI cannot render authenticated pages locally without signing in. A
temporary local stub of `accessToken()` is the only offline route to a
screenshot; revert it before committing (oxlint's `no-unreachable` will
flag it if you forget).

**A stubbed token does not grant group membership.** Endpoints guarded by
`ArchiveAttachmentViewer` - the Archived File Finder's results and every
attachment download - correctly return "Access denied" against a stub.
That is the authorization boundary working, not a bug, and it must not
be weakened or bypassed to obtain a screenshot. Anything behind it needs
an authenticated smoke test in an authorized environment instead; see
`docs/runbooks/deployment/` for the pre-deployment checks that currently
require one.
