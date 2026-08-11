# Award RAG Live Demo Runbook

10–15 minute script. No credentials, tokens, private URLs, or database
connection strings appear in this document — log in with a real
authorized test account at meeting time, using whatever the team's normal
sign-in process is.

**Read `AWARD_RAG_DEPLOYMENT_READINESS.md` §3 before presenting**: the
Related Negotiations tab is a confirmed blocker in the currently deployed
environment. Step 8 below has a mandatory pre-check and a backup script —
do not skip that check.

Legend for the "Status" column: **LIVE** = demo this for real. **PREP
REQUIRED** = only demoable after the deployment sequence in
`AWARD_RAG_DEPLOYMENT_READINESS.md` §4 has been completed and verified.
**DESCRIBE ONLY** = do not click through this; describe it from the slide.

---

## Step 0 — Sign in

- **Page/route**: application root (`/`)
- **Control**: normal authenticated sign-in
- **Expected result**: lands on the Dashboard page
- **Explain**: "Every user must authenticate — this is not an open,
  public archive."
- **Status**: LIVE
- **Backup**: if sign-in fails, fall back to the presentation slides only
  and narrate from `AWARD_RAG_CLIENT_PRESENTATION.md`.

## Step 1 — Find CARB-X by Award number

- **Page/route**: `/search`
- **Search phrase**: `204713-00133`
- **Control**: the Global Search box
- **Expected result**: an exact Award match for CARB-X ranks first
- **Explain**: "Typing a known Award number goes straight to the exact
  record — no scrolling through unrelated results."
- **Status**: LIVE
- **Backup**: if search is slow/unavailable, navigate directly via
  `/awards/search` and use the Award Search page's own form instead.
- **Transition**: click the result card to open the Award.

## Step 2 — CARB-X: version history

- **Page/route**: `/awards/{id}` (reached by clicking through from Step 1
  — do not type a raw Award ID)
- **Control**: **Versions** tab
- **Expected result**: the full list of archived versions for this Award
  (125 versions for this specific Award record)
- **Explain**: "Every change to this Award over its lifetime is preserved,
  not overwritten — this is a historical archive, not a live editable
  system."
- **Status**: LIVE
- **Backup**: if the Versions tab is empty/errors, fall back to the
  **Summary** tab, which shows the current version's key facts.

## Step 3 — CARB-X: investigators, amounts, terms, comments

- **Page/route**: same Award page
- **Controls**: **People and Units**, **Amounts**, **Terms**, **Comments
  and Notepad** tabs, in that order
- **Expected result**: real investigator (PI Michael Kevin Outterson),
  259 amount records, 2,500 sponsor/reporting term records, 125 comment
  records
- **Explain**: "Every structured fact about this Award — who's on it, how
  much funding, its terms, its comments — is browsable directly, not
  buried in an attachment."
- **Status**: LIVE
- **Backup**: if any one tab is slow with this much history, note the
  real count out loud ("2,500 term records — this is a long-running,
  heavily amended Award") rather than waiting on the render.

## Step 4 — CARB-X's related proposal and attachments (switch Award)

- **Page/route**: `/search`, search phrase `204713-00001`
- **Control**: Global Search box, then click through
- **Expected result**: a sibling Award in the same CARB-X program
- **Explain**: "CARB-X spans many related Award numbers under one program
  — this sibling Award is where the originating proposal and the archived
  attachments live."
- **Status**: LIVE
- **Transition**: open **Funding Proposal** tab (proposal `01128961`,
  "CARB-X"), then **Attachments** tab.

## Step 5 — Attachments: metadata and download

- **Page/route**: same Award page (`204713-00001`), **Attachments** tab
- **Control**: attachment list, then the download icon on one row
- **Expected result**: filename, description, content type, and archival
  status for real archived files (836 unique files, all uploaded); a
  successful download of one file when clicked
- **Explain**: "Attachments are preserved and retrievable — but their
  *content* is not what our AI search reads. That's a deliberate,
  important distinction: [read the required attachment statement from the
  presentation]."
- **Status**: LIVE for metadata; **verify the download click live before
  the meeting** — not yet confirmed with a real authenticated click this
  session (see Deployment Readiness §5). If unverified, downgrade this
  specific click to DESCRIBE ONLY and show the metadata list instead.
- **Backup**: if download fails, stay on the metadata list — filename,
  size, and status are still real, useful content to show.
- **Known caveat**: don't read the `content_type` field aloud if it looks
  garbled — describe the filename instead.

## Step 6 — Keyword vs. semantic search comparison

- **Page/route**: `/search`
- **Search phrase 1 (keyword)**: `204713-00133`
- **Search phrase 2 (semantic)**: a concept phrase, e.g. `antibiotic
  resistance research funding` (not an exact title/number)
- **Expected result**: phrase 1 returns the exact Award first; phrase 2
  returns conceptually related Awards ranked below any exact matches,
  each still traceable to a real archived record
- **Explain**: "Keyword search finds what you already know the name of.
  Semantic search finds what you're trying to describe — both run
  together, and exact matches always win."
- **Status**: LIVE (`app.search.semantic.enabled=true` confirmed active in
  dev; 8,597 real summary embeddings already populated)
- **Backup**: if the semantic phrase returns nothing meaningfully
  different, that's expected for some phrasings — pick a second concept
  phrase live rather than forcing the point.

## Step 7 — Switch Award: connected records (Awards 3 & 4)

- **Page/route**: `/search`, search phrase `101929-00001` ("NSF
  Engineering Research Center for Smart Lighting")
- **Expected result**: a second, clearly different research domain — 58
  versions, 1,243 uploaded attachments, a working related proposal
- **Explain**: "This isn't a handful of curated demo records — the same
  experience works the same way across the full 8,773-Award archive."
- **Status**: LIVE
- **Transition**: optionally repeat with `103162-00001` ("Behavioral
  Surveillance of Acetaminophen Users and Non-Users") if time allows —
  this one has 6 real Award-linked negotiations, useful if Step 8's
  primary negotiation example is unavailable.

## Step 8 — Related negotiations and subaward (104949-00002)

**Status update (Checkpoint A)**: the backend chain for this tab is now
written, tested (7 new passing tests — 4 service, 3 controller, using
this exact Award's real data), and committed locally. **It has not been
pushed or deployed.** Until a deploy happens and is confirmed, treat this
exactly as before: do not discover its live status in front of the
client.

**Mandatory pre-check, before the meeting**: after the completing commit
has been pushed and deployed, open this Award's Negotiations tab yourself
first, logged in, and confirm it renders correctly. Per
`AWARD_RAG_DEPLOYMENT_READINESS.md` §3, do not rely on this being live
until that check passes.

- **Page/route**: `/search` → `104949-00002` → Award page
- **Control**: **Negotiations** tab, then **Subawards** tab
- **Expected result** (once deployed and verified): two negotiations —
  `1060608` (negotiation 11241, "Data Use Agreement," Fully Executed) and
  `1074016` (negotiation 11471, "Data Use Agreement," Abandoned); subaward
  `1008`, status "07. Executed"
- **Explain**: "An Award connects outward to the negotiation that governed
  it and any subawards funded through it — this Award has both, including
  one negotiation that didn't complete, a useful real contrast."
- **Status**: **PREP REQUIRED (deployment)** — fixed and tested locally;
  do not click through live until independently re-verified deployed.
- **Backup script (use this if not yet deployed by meeting time)**: skip
  the live click. Say: "This connection exists in the archive today —
  negotiation 1060608, a Data Use Agreement, Fully Executed — the screen
  that surfaces it is built, tested, and ready to deploy." Show the fact
  from this document rather than the live UI. Optionally pivot to Award
  `103162-00001` (6 real Award-linked negotiations, same deployment
  dependency) as a second example once deployed, or its Terms/Comments
  tabs as a same-Award fallback that doesn't depend on this fix.

## Step 9 — Evidence indexing (Phase 3, planned) and AI (describe, do not click through)

- **Page/route**: n/a — no live UI page exists to click through
- **Expected result**: n/a
- **Explain**: "We've built the indexing layer that breaks each Award down
  into deterministic, source-cited pieces of evidence — its versions,
  people, amounts, terms, comments, and related records. That pipeline is
  fully built and tested. What doesn't exist yet is any way to retrieve,
  filter, or cite that evidence — no API endpoint, no UI screen. That's
  Phase 3: a planned, well-scoped next step, not something running
  indexing today would unlock by itself." If asked about full-dataset
  semantic search: "That's different and already live — every Award,
  Proposal, Negotiation, and Subaward has a searchable concept-level
  summary today." If asked about AI Award summaries: "That capability
  exists in the codebase but isn't reachable today — its feature flag is
  off in this environment, and the screen that would show it isn't wired
  into any page yet. Not part of today's live demo — happy to walk
  through the design separately."
- **Status**: DESCRIBE ONLY (evidence retrieval: PLANNED / PHASE 3; AI
  summary/questions: implemented but not currently reachable)
- **Do not**: open a browser tab and try to demo evidence retrieval or AI
  summaries live — there is nothing to click for either.

## Step 10 — Close

- **Page/route**: n/a
- **Control**: n/a
- **Expected result**: n/a
- **Explain**: recap using Slide 10 ("Current capabilities and future
  phases") — what's live today, what's built-but-not-connected, what's
  explicitly planned. End on the required attachment statement if it
  wasn't already used in Step 5.
- **Status**: n/a
