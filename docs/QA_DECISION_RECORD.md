# QA decision record — the seven decision-needed cases

Round: John's QA checklist, `QA_MATRIX_2026-09-29.xlsx` (47 cases in
scope; TC-022 excluded by owner direction and never shown, counted or
marked passed).

Decisions taken 2026-10-06. This record exists because a QA case that
turns out to be a *design question* must not be quietly rewritten into a
pass. For each case it keeps three things apart:

- **Original expectation** — exactly as the tester wrote it. Never edited.
- **What the code actually does** — verified against merged `main`, with
  the file that decides it.
- **Revised acceptance criterion** — what we agreed to judge it against,
  and why it differs.

None of these seven is marked Passed by this record. A case becomes
Passed only when its revised criterion has been verified in the required
environment, per the standing dashboard policy. Two of them will never
be Passed: one is deferred scope, one needs evidence we have not
gathered.

Related: [`QA_STATUS_PAGE.md`](QA_STATUS_PAGE.md) (the temporary
dashboard and its removal checklist),
[`QA_VERIFICATION_PROTOCOLS.md`](QA_VERIFICATION_PROTOCOLS.md) (the
restricted-account, handset and screen-reader protocols).

---

## Status after these decisions

| Case | Decision | Resulting status | Becomes Passed when |
|---|---|---|---|
| TC-009 Wildcard search | Keep behaviour, correct the guidance | Fixed in code, awaiting release | Revised guidance verified on dev |
| TC-015 Empty search | Add the initial hint, keep API behaviour | Fixed in code, awaiting release | Hint verified on dev |
| TC-019 Hint tags non-interactive | Ratify informational | Awaiting re-verification | Chips confirmed non-interactive on dev |
| TC-021 Breadcrumb navigation | Keep hierarchy nav, add explicit return | Fixed in code, awaiting release | Return link verified on dev |
| TC-026 Download confirmation | No prompt; criterion revised | Open — needs integrity evidence | Content and filename verified separately |
| TC-029 Specific sequence filter | Defer the feature | **Not implemented / deferred** | Never, under current scope |
| TC-042 Search response time | Measure before optimising | Open — needs measurement | A target is approved and met |

Three of the four "fixed in code" entries land in one commit,
`889b579` — see [the UI change](#the-ui-change) below. Nothing in this
record has been deployed.

### A dashboard change this implies

TC-029's resulting status, *not implemented / deferred*, has no
corresponding label in `ui/src/features/qa/qaSnapshot.json` today —
the statuses it carries are `passed`, `decision`, `evidence` and
`blocked`. Calling it `passed` would assert that an absent capability
works; leaving it `decision` would imply the decision is still open.
Adding a `deferred` label is a dashboard change and is **not** made
here; it needs separate approval, and until then TC-029 should stay
`decision` with this record as its reference rather than being
mislabelled.

---

## TC-009 · Wildcard search · Awards Search · Medium

**Original expectation.** *Enter a wildcard query such as `*105698*`.
Results include all awards matching the wildcard pattern.*

**What the code actually does.**
`api/.../application/award/AwardSearchPattern.java` escapes literal
`%`, `_` and `\`, translates `*` to `%`, and — only when the user typed
no `*` — wraps the whole term in `%…%`. So:

| Typed | Pattern | Meaning |
|---|---|---|
| `105698` | `%105698%` | contains |
| `*105698*` | `%105698%` | contains — identical to the above |
| `105698*` | `105698%` | starts with |
| `*105698` | `%105698` | ends with |

The expectation is therefore met — and was already met before this
round. The reason it could not be confirmed is that the page advertised
`*text*`, the one form that changes nothing, so a tester following the
hint saw no difference and could not tell whether wildcards worked at
all.

**Where wildcards exist, verified before changing any copy.** Translation
lives in exactly two places: `AwardSearchPattern` (Awards, current and
historical) and `DocumentSearchPattern` (Archived File Finder).
Proposal, Negotiation and Subaward build their pattern in SQL as
`'%' || :query || '%'` with the raw term bound, and never translate `*`
— on those pages `*105698*` searches for those literal characters and
returns nothing. Global Search likewise.

Two consequences worth recording. The guidance must **not** be shared
across pages, because it would be false on four of them; this was the
specific risk the decision asked us to check, and it is real. And those
three modules also do not escape a literal `%` or `_`, so a term
containing one behaves differently there than on Awards — a
pre-existing inconsistency, read-only and benign, noted here rather
than fixed in a guidance change.

Each page already declares its own `SEARCH_DIMENSIONS`; only the
`HintChips` component is shared. So no shared copy was touched.

**Revised acceptance criterion.** Behaviour unchanged. On Awards and
Historical Awards the guidance states: matches anywhere in the field by
default, `105698*` for starts-with, `*105698` for ends-with. A tester
can confirm each of the three forms behaves as described. No wildcard
guidance appears on Proposal, Negotiation, Subaward or Global Search.

**Why it differs.** The original expectation describes a capability that
works; what failed was the explanation of it. Testing the explanation is
the only way this case can discriminate.

---

## TC-015 · Empty search submission · Awards Search · Medium

**Original expectation.** *Submit the search with an empty input field.
App shows an appropriate prompt/validation instead of erroring or
showing all records.*

**What the code actually does.** `ui/src/hooks/useFilteredSearch.ts:187`
computes `hasCriteria = appliedQuery.trim().length > 0 || appliedCount > 0`.
With an empty box and no filters it is false, the query is `enabled:
false`, **no request is made**, and `resolveSearchState` returns
`"initial"`, which `SearchStates` renders as nothing at all — a
deliberate choice, so that a module does not put 10,775 rows on screen
merely because the data exists.

So neither failure mode in the expectation occurs: nothing errors, and
no records are shown. **The premise of the case is wrong**, and that is
recorded here rather than silently corrected.

What *was* missing is the other half: there was no acknowledgement of
any kind, so the page read as broken rather than as waiting.
`InitialSearchHint` already existed for exactly this purpose and no page
had ever used it.

**Review point resolved.** Changing Versions *after* a valid search must
still apply the selection, and an untouched default must stay
distinguishable from an explicit choice. Confirmed: `activeFilters()`
omits a value equal to its field's default, so `versionFilter` is absent
when untouched and present when someone chose `current` or `historical`.
A present selection travels to the API and enters the query key, so the
search refetches with it. The predicate was extracted from the page as
`startsVersionSearch` and now carries six tests covering both states,
including the review case and an explicit "All versions" collapsing back
to the default.

**Revised acceptance criterion.** An empty submit with no filters makes
no request, shows no records, shows no error, and displays *"Enter a
search term, or apply a filter, to see results."* Filter-only searching
continues to work — an empty box with one filter applied is a real
search. Global Search keeps its 2-character minimum unchanged.

Historical Awards carries one extra sentence, *"The Versions choice
narrows a search; it does not start one."* Its `hasSearched`
deliberately ignores `versionFilter`, which defaults to `all` and would
otherwise make the page look permanently searched — so selecting only a
Versions value submits nothing, and the agreed sentence alone would be
misleading there. The agreed sentence is kept verbatim and the caveat
appended.

**API behaviour, decided and unchanged.** The module endpoints still
accept an empty `q` and list everything, paginated. This is intentional:
browse-all is legitimate for a read-only archive, it is reachable only
by typing a URL rather than through the UI, and it is already length-
and control-character-guarded by the TC-018 and TC-017 work.

---

## TC-019 · Hint tags are non-interactive · Awards Search · Low

**Original expectation.** *Click each hint tag below the search box
(Award Number, PI, Sponsor, etc.). Clicking tags has no unintended
effect since they are informational labels only.*

**What the code actually does.**
`ui/src/components/common/search/HintChips.tsx` attaches `onClick` and
`clickable` only when an `onHintClick` handler is supplied. All four
call sites — Awards, Proposals, Subawards, Global Search — supply none.
The chips are therefore inert: no handler, no `clickable`, no pointer
cursor, no ripple. They are not presented as interactive controls.

**Decision.** Keep them informational. These chips label what the
free-text box covers; active, removable filter chips are `FilterPanel`
and `FilterChips`' job. Making labels clickable would blur two
affordances the codebase deliberately separates.

**Revised acceptance criterion.** The expectation stands, with the
wording corrected from "has no unintended effect" to the positive form:
the chips are not rendered as interactive controls — no pointer cursor,
no hover or press affordance, no keyboard focus stop — and clicking one
changes nothing.

**Re-verification is required even though no decision changed it.** The
Awards chip list itself changed in `889b579`: the entry
`"Wildcard (*text*)"` was removed, because it described a syntax the
revised TC-009 guidance now explains in prose instead. So the chips a
tester sees on Awards are not the chips that were verified on dev
earlier in this round.

---

## TC-021 · Breadcrumb navigation · Award Hierarchy · Medium

**Original expectation.** *From an award detail page, click the
breadcrumb back to hierarchy/search. User is returned to the correct
prior page/state.*

**What the code actually does.**
`ui/src/components/award/AwardBreadcrumb.tsx` renders the *hierarchy*
lineage — root to current, from `selectedAwardPath` — and each ancestor
is a button that opens that Award. Its own comment states the intent:
to let someone move between related Awards *"without returning to
search."* There was no return-to-results affordance, and the breadcrumb
does not render at all for an Award with no hierarchy.

The expectation conflates two different questions — "where am I in this
family?" and "how do I get back to my results?" — which is why it read
as a failure. The breadcrumb answers the first and was never built to
answer the second.

**Decision.** Keep hierarchy navigation exactly as it is. Add a
separate, explicitly labelled return link, shown only when search
context exists.

**Revised acceptance criterion.** Two distinct controls:

1. The breadcrumb continues to move between Awards within the
   hierarchy. Unchanged.
2. When — and only when — the reader arrived from a search, a
   *"← Back to search results"* link appears above the breadcrumb and
   returns them to **the exact result list**: same query text, same
   structured filters, same sort, same page number.

The return target must survive the whole journey: search → hierarchy →
dashboard, and any number of later breadcrumb hops between Awards.

**Explicitly out of bounds.** The destination is never inferred from
browser history. `navigate(-1)` lands wherever the reader happened to
be, which after a few breadcrumb hops is another Award, not the search.

### Where the context survives — measured, not asserted

Router state is **not** part of a link's URL, so this does not preserve
context across every route into a detail page. Raised in review, and
measured in a browser driving the real helper module through the real
navigation chain rather than reasoned about:

| Path | Result |
|---|---|
| Ordinary click: search → hierarchy → dashboard | **Preserved** — `/awards/search?q=Orsmond&sponsor=NIH&page=3` |
| Breadcrumb hop between Awards, twice in a row | **Preserved** |
| Reload of the dashboard (F5) | **Preserved** |
| Middle-click a result card into a new tab | **Lost** |
| Cmd-click a result card into a new tab | **Lost** |
| Pasted or bookmarked link to the dashboard | **Lost** |
| Copied result link contains the search | **No** — href is `/awards/hierarchy/105698` |

Reload survives because router state is kept in the history entry
(`window.history.state`), which the browser retains across a reload and
React Router reads back on start. This corrects an earlier claim in this
record that a refresh loses the context; it does not.

A new tab is a new history and starts empty — which is the same property
that keeps a copied link free of someone else's search terms.

**What the losses mean.** For a pasted or bookmarked link, absence is
simply correct: the reader did not arrive from a search. For a new tab
it is a **real limitation** — the reader did come from a search, in the
tab they left behind — and the link is absent there. We are not claiming
preservation across all navigation.

Two properties that are part of the criterion either way: result cards
remain real anchors, so new-tab and copy-link keep working; and the
context travels as router state, so a copied Award link carries no
search terms.

### Proposal for durable preservation (not implemented, needs a decision)

To cover the new-tab paths the target has to be in the URL. The
straightforward form is a query parameter on the detail route:

```
/awards/1833767?from=%2Fawards%2Fsearch%3Fq%3DOrsmond%26sponsor%3DNIH%26page%3D3
```

- **Gains:** survives new tab, middle-click, Cmd-click, copy-link,
  bookmark and reload — every row of the table above.
- **Costs:** the Award URL now carries the search it was opened from, so
  a link copied and sent to a colleague carries the sender's search
  terms with it. Those terms can include a PI or investigator name, which
  makes this a disclosure question and not only a tidiness one. URLs also
  get long, and the parameter has to be propagated through the
  hierarchy hop and every breadcrumb navigation.
- **Validation is unchanged either way:** the value is untrusted input
  in both designs and must be refused unless it is a plain
  site-internal path. `readSearchReturn` already does this and would be
  reused as-is.

A middle option was considered and rejected: a short token in the URL
with the real target in `sessionStorage`. `sessionStorage` is per-tab,
so a new tab would find nothing — it fails in exactly the case it is
meant to fix.

**Recommendation.** Ship the router-state version as it stands and leave
the new-tab path without a link. It is the common journey that matters,
the limitation is invisible rather than misleading, and it keeps search
terms out of shared links. Revisit if testers report the new-tab case
as a real friction — at which point the query-parameter version is a
small change on top of this one, because the validation and the capture
already exist.

**How to reproduce the measurements.** A throwaway harness mounted
`buildSearchReturn` / `forwardSearchReturn` / `readSearchReturn` behind
three routes mirroring search → hierarchy → dashboard, served by Vite,
driven through each path with Playwright, asserting on the rendered
link's `href` and on `window.history.state.usr`. The harness is not
committed; the module under test is the real one.

**Not in scope.** Historical Awards results open a version detail route
rather than the Award dashboard and do not get a return link in this
change. Recorded as a follow-up, not a defect.

---

## TC-026 · Download Award Report · Downloads · Medium

**Original expectation.** *Click Download Award Report on an award
detail page. A confirmation prompt appears before download; resulting
file is correctly named and not corrupted.*

**What the code actually does.** `AwardDashboardPage.tsx` wires the
button straight to the download handler. There is no `Dialog` and no
`window.confirm` anywhere in the page. Feedback is in-button: the label
becomes *"Preparing report…"*, the button is `disabled`, and `aria-busy`
is set.

**Decision.** No confirmation prompt. This is a read-only archive; a
report download is non-destructive and idempotent, so a prompt would add
a click to every download and prevent nothing. Confirmations are
reserved for destructive or irreversible actions, of which this archive
has none.

**Revised acceptance criterion — split into two independent parts.**

1. *No confirmation prompt.* Clicking downloads immediately. While the
   report is being prepared the control shows a busy state and cannot be
   double-submitted. **This part is about responsiveness only.**
2. *The file is correctly named and not corrupted.* Verified separately
   and directly: open the downloaded PDF and confirm it renders, covers
   the expected Award, and is not truncated; and confirm the filename
   against the `Content-Disposition` the API actually sent.

**Why the split matters.** The busy state and `parseDownloadFilename`
prove neither half of part 2. A busy state says a request is in flight,
not that the bytes that arrived form a valid PDF. `parseDownloadFilename`
parses the name the server supplied — it cannot establish that the name
is *right*, only that it was read correctly. Treating either as evidence
of download integrity would mark a case passed on the strength of
something that cannot fail for the reason the case is testing.

**Resulting status: open.** Part 1 is satisfied by current behaviour.
Part 2 needs evidence that has not been gathered. TC-026 is not Passed.

---

## TC-029 · Version dropdown filter · Historical Awards · Medium

**Original expectation.** *Select a specific version from the Version
dropdown and search. Results are filtered to only that version.*

**What the code actually does.**
`ui/src/features/search/searchFilterFields.mjs:57` offers three options:
All versions, Current only, Historical only. There is also an
`awardId` (exact) filter and a sequence-number sort. The API has **no
`sequenceNumber` request parameter** on the version-search endpoint, so
no per-sequence filtering exists at any layer.

**Decision.** Defer the feature. A specific-sequence selector would need
a request parameter, a repository predicate, controller validation and a
UI field — not a dropdown change.

There is also a design question that must be answered *before* any
implementation, not during it: multiple rows may legitimately share
`award_number` **and** `sequence_number` when `award_id` differs (the
Award grain rule in `CLAUDE.md`). A "specific sequence" selector would
therefore not always return a single row, and what it should return when
the sequence is ambiguous is undecided.

**Revised acceptance criterion — split in two.**

1. *The version filter that exists* passes on its own criteria: All
   versions, Current only and Historical only each filter the result set
   correctly. This is a real, testable capability and should be verified
   and recorded as such.
2. *Per-sequence filtering* is **not implemented and is deferred.** It
   is not tested, and not claimed.

**Resulting status: not implemented / deferred.** Explicitly not Passed.
Recording an absent capability as Passed would assert that selecting a
specific version works, which is false. If a concrete workflow later
justifies it, it becomes its own ticket, and the ambiguity question
above is its first task.

---

## TC-042 · Search response time · Global Search · Low

**Original expectation.** *Submit several searches and time the loading
spinner duration. Results return within an acceptable time frame without
excessive delay.*

**Recorded observation.** Medians of about **11.5s** for Global Search
and **3.3s** for Subaward FRN search. Measured at UI Amplify #93
(`79b792a`) with **API ECS rev 71**.

**Why that number cannot set a target.** Dev now runs **rev 75**, four
revisions later, and the TC-041 work changed this exact code path — it
removed the lexical gate and added two set-based enrichment queries for
Negotiation and Subaward titles. The figure could have moved in either
direction, and nothing has re-measured it.

**What is known about the code, and what is only hypothesis.**

Known: `GlobalSearchService` fans the five modules out concurrently with
`CompletableFuture`, so elapsed time is governed by the slowest leg
rather than their sum. It already contains per-module instrumentation
in a `timed(...)` helper — logged at `log.debug`, so on a default `INFO`
configuration it is not being emitted.

Hypothesis, not finding: that the dominant cost is leading-wildcard
`ILIKE '%term%'` scans, which cannot use a btree index. This is
consistent with the 3.3s single-module figure but is **not established**.
It needs query plans and per-module timings to support it.

Also explicitly **not** established: that the semantic leg is bounded to
roughly its 2000 ms Bedrock timeout. That timeout bounds one embedding
call. It says nothing about retries, connection acquisition, the pgvector
nearest-neighbour query, or the enrichment queries that follow — all of
which sit outside it. An earlier version of this analysis treated the
timeout as bounding the whole semantic path; that was wrong, and the
correction is recorded here so the measurement step is not skipped on the
strength of it.

**Decision.** Measure the current release before choosing any
optimisation.

**Provisional targets, not acceptance thresholds.** p95 ≤ 3s for a
single-module search, p95 ≤ 6s for Global Search. These are a starting
point for the conversation after measurement. They are **not approved**
and no case passes or fails against them.

**Revised acceptance criterion.** Deliberately not final. The next step
is measurement, in this order:

1. Make per-module timings observable on dev (raise that logger, or
   enable debug for the class) and re-measure at the current release, so
   the time is **attributed** rather than guessed.
2. With real per-module figures, agree a p95 per surface — Global Search
   and a single-module search are different shapes and should not share
   one number.
3. Only then decide whether trigram indexing or full-text search is
   justified. Note that such a change arrives as a schema migration
   applied by the ETL, not by an API deploy.

**Resulting status: open, pending measurement.**

---

## The UI change

Commit `889b579` on `ui/qa-search-guidance-and-return-nav`, covering
TC-009, TC-015 and TC-021. UI only — no API, ETL or schema change.

| File | Why |
|---|---|
| `features/common/searchPresentation.mjs` | `INITIAL_SEARCH_HINT`, the one definition of the agreed sentence |
| `features/award/searchReturnContext.mjs` | Capture, validate and forward the return target |
| `components/common/search/ResultCard.tsx` | Optional router `state`, so a card can hand the destination its search |
| `pages/award/AwardSearchPage.tsx` | Revised wildcard guidance, initial hint, context on result cards |
| `pages/award/AwardVersionSearchPage.tsx` | Initial hint with the Versions caveat; uses the extracted predicate |
| `features/award/awardVersionSearchPresentation.mjs` | `startsVersionSearch`, extracted so both Versions states are tested |
| `pages/ProposalFamiliesPage.tsx`, `pages/SubawardFamiliesPage.tsx` | Initial hint |
| `pages/award/AwardHierarchyPage.tsx` | Forward the context to the dashboard |
| `pages/award/AwardDashboardPage.tsx` | Render the return link; forward across breadcrumb hops |

Pages deliberately **not** changed: Negotiation already shows its own
initial guidance (*"Search by …, or open Filters to combine criteria"*),
which is more specific than the shared sentence; replacing it would be a
regression. Global Search keeps its own 2-character-minimum messaging.

**Verification.** UI 629/629 `node:test` — 11 new covering the return
context, 6 covering the two Versions states, 2 pinning the agreed copy;
`tsc -b` exit 0; `oxlint` 0 warnings; `vite build` exit 0. The new tests were confirmed to actually
execute by name, because `ui/package.json`'s test script enumerates
feature directories explicitly and a file in an unlisted directory
silently never runs.

**Not verified, and not claimable from the above.** No rendered-component
test setup exists in this project, so none of these tests prove what a
reader sees. The link's appearance, the hint's placement and the
three wildcard forms all need checking in a browser on dev after
release. Until then these three cases are *fixed in code*, not Passed.
