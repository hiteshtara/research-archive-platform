# QA verification protocols — restricted account, real handset, screen reader

Three checks that cannot be done from this machine, written so whoever
runs them can do so without further instruction and record a result that
closes the case.

Covers the four cases still open for evidence or access:

| Protocol | Cases | Why it can't be automated here |
|---|---|---|
| A — Restricted account | TC-023, TC-027 | Needs a real signed-in user who lacks one Cognito group |
| B — Real handset | TC-020, TC-045 | Emulated widths are not a phone |
| C — Screen reader | TC-020, TC-045 | No assistive technology in the harness |

**Environment for all three:** the development website,
`https://main.d288p9gmoteftb.amplifyapp.com`. Record the UI build and
API revision at the time of testing — they release independently, so the
site can carry a new UI against an older API, and a result that doesn't
name both cannot be reproduced.

## Ground rules

These are not negotiable and apply to every protocol below.

- **Testers sign in with their own account.** No account is created,
  modified or borrowed for this testing.
- **No passwords are requested, shared, typed by anyone else, displayed
  or recorded.** Not in a ticket, not in a screenshot, not in chat.
- **No bearer tokens are copied anywhere.** None of these protocols
  needs one; if a step seems to, it is being done wrong — stop and ask.
- **Screenshots must contain no personal data.** Capture the message or
  the layout, not the signed-in user's name, email or avatar. Crop
  before attaching.
- **Record what you saw, not what you expected.** A partial or
  surprising result is more useful than a tidy one, and "the message
  differed slightly" is a finding worth having.

---

## Protocol A — Restricted-account access control

**Cases:** TC-023 (Attachments access control, High), TC-027 (Download
Report + Attachments without access, Medium).

**Who runs it.** Any existing user who can sign in to the development
site and is **not** a member of the `ArchiveAttachmentViewer` Cognito
group. Ordinary read-only archive access is all that is needed.

### A0 — Confirm the precondition (administrator, before testing)

The tester cannot establish their own group membership, and should not
try. An administrator confirms it out of band against the user pool and
records only the outcome:

- Pool: `us-east-1_VJ4ekQ27c`
- Expected: `ArchiveAttachmentViewer` **absent** from the account's groups

Record: *"precondition confirmed — group absent"*, plus the date and who
confirmed it. Do not paste the group listing, which names other users.

If this cannot be confirmed, stop. A pass recorded against an account
whose membership is unknown proves nothing.

### A1 — TC-023 · Attachments section shows a clear denial

1. Sign in and open any Award detail page.
2. Open the **Attachments** section from the left-hand section nav.

**Expected.** The section renders a denial message and no attachment
data:

> Access denied. Viewing Award attachments requires membership in the
> ArchiveAttachmentViewer group - contact an administrator if you
> believe this is wrong.

**Also check — this is the half that matters most.** No attachment
information of any kind appears: no filenames, no file sizes, no dates,
no count, not even "0 attachments". The denial replaces the content
rather than sitting above an empty list.

**Optional confirmation, if you are comfortable with browser devtools.**
Open the Network tab *before* clicking the section, then click it. There
should be **no request at all** to
`/api/v1/awards/{awardId}/attachments`. The listing is never requested
when access has not resolved, so nothing to leak ever reaches the
browser. If you see that request, say so — it would be a real finding.

**Record:** the message as it actually appeared (cropped screenshot),
whether any attachment detail was visible, and the network observation
if you made it.

### A2 — TC-027 · The report-with-attachments action is not offered

1. On the same Award detail page, look at the download controls in the
   header area.

**Expected.**

- **"Download Report + Attachments" is absent.** Not greyed out, not
  present-then-failing — not rendered at all. That action embeds
  attachment file content, so it is withheld rather than offered.
- **"Download Award Report" is present and works.** Click it. It should
  download. That report carries no attachment content, so it is
  unaffected by the group.

**Note a wording question for the decision record, don't resolve it
here.** TC-027's original expectation was *"either omitting attachments
with a note or showing a clear error, not a silent failure."* What
actually happens is a third thing: the action is **not offered**, with no
note explaining why. That is arguably better than an error — there is
nothing to fail — but it is not literally either branch of the
expectation. Record what you saw and flag it; whether "not offered"
satisfies the case is a decision, not a tester's call.

**Record:** whether the combined-download button was absent, whether the
report-only download succeeded, and whether anything explained the
absence.

### A3 — What is already covered, so you don't need to test it

The server-side rule is covered by automated tests and needs no manual
repetition. In `AwardV1ControllerDownloadSecurityTest`:

- `authenticatedUserWithoutAttachmentGroupIsRejected` — 403 on an
  attachment download
- `listWithoutAttachmentGroupIsRejectedAndLeaksNoMetadata` — 403 on the
  listing, asserting no metadata in the body
- `consolidatedReportIsForbiddenWithoutTheAttachmentGroup` — 403 on
  `report-with-attachments.pdf`
- `consolidatedReportIsForbiddenForAnUnrelatedGroup` — 403 for a user in
  a *different* group, not merely a user in none
- `theReportWithoutAttachmentsStaysAvailableWithoutTheAttachmentGroup` —
  `report.pdf` still returns 200

**The limitation these leave, and what A1/A2 are really for.** Those
tests synthesise a JWT with explicit authorities. They prove the
authorization rule is right; they do **not** prove that a real Cognito
token, for a real restricted user, carries the authorities the rule
expects. A1 and A2 close exactly that gap for the paths the UI
exercises, which is why they are worth a human's time and the 403 checks
are not.

---

## Protocol B — Real handset

**Cases:** TC-020 (Node click loads without reload, Medium), TC-045
(Responsive layout, Low). Both currently sit at *evidence* — verified at
emulated widths in Chromium, with real-device checks outstanding.

**Devices.** At least one of each, physical hardware, not a simulator:

- iOS, Safari — the engine differs from Chromium in ways emulation does
  not reproduce (viewport units against the dynamic toolbar, momentum
  scrolling, tap-target handling)
- Android, Chrome

Record make, model, OS version and browser version. "A phone" is not a
reproducible environment.

### B1 — TC-020 · Hierarchy node opens without a full reload

1. Search for an Award with a hierarchy (one with parents or children)
   and open its hierarchy view.
2. Tap a **different** Award node in the tree.

**Expected.** The dashboard updates in place. No full page reload — no
white flash, no app-wide spinner, no return to the top-level loading
state, and the browser's reload indicator does not spin.

**Then check, on the device, what emulation couldn't tell us:**

- The hierarchy remains scrollable with a finger, both directions, with
  no trapped scroll and no rubber-banding that hides content.
- Nothing is clipped at the screen edge; a deep tree does not run off
  the side.
- Tap targets are reachable and distinguishable — nodes should not be so
  tightly packed that the wrong one activates.
- After the tap, the content you landed on is what's in view. You should
  not have to scroll up to find out where you are.

### B2 — TC-045 · Responsive layout on real hardware

Visit each of: Awards search, a search results list, an Award hierarchy,
an Award dashboard, Global Search, and the Attachments section. On each,
in **both portrait and landscape**:

- **No horizontal page scroll.** The page body must not slide sideways.
  Wide tables and diagrams may scroll inside their own container — that
  is intended; the page itself scrolling is not.
- **No overlapping elements**, and nothing cut off at either edge.
- **The navigation sidebar collapses** and can be opened and closed.
  Confirm it does not sit permanently over the content.
- **Text is legible without pinch-zoom**, and pinch-zoom still works if
  you want it.
- **The on-screen keyboard** does not cover the search input while
  typing into it.

Record per page and per orientation. A single "responsive: OK" cannot be
acted on; "Award dashboard, landscape, iPhone 14, header overlaps the
breadcrumb" can.

---

## Protocol C — Screen reader

**Cases:** TC-020, TC-045 (the accessibility half of each).

**Tools.** One desktop and one mobile reader, each with its usual
browser pairing:

- VoiceOver with Safari (macOS, and iOS for the mobile pass)
- NVDA with Firefox or Chrome (Windows)

Record reader and version. Behaviour differs enough between
reader/browser pairings that an unnamed one is not reproducible.

### C1 — Breadcrumb and hierarchy navigation

1. On an Award dashboard, navigate to the breadcrumb.

**Expected.** It is announced as a navigation landmark named *"Award
hierarchy breadcrumb"*. The current Award is announced as the current
item (it carries `aria-current="page"`); the ancestors are announced as
activatable controls.

2. Activate an ancestor with the keyboard alone — no mouse, no touch.

**Expected.** It activates, the dashboard changes, and focus lands
somewhere sensible in the new content rather than being dropped to the
top of the document or lost entirely.

**This is the known soft spot, so test it deliberately.** The dashboard
updates without a page load, which means a reader is not told anything
changed unless the page says so. Note explicitly: after activation, was
there *any* announcement that new content had loaded, or did it change
silently? Either answer is a useful result.

### C2 — Search box and its guidance

1. Move to the search input on the Awards search page.

**Expected.** It is announced with its own label ("Search Awards"), not
merely as "edit text".

2. With the box empty, and again with an over-long query (more than 200
   characters, which TC-018 added a counter and a refusal for).

**Expected.** The guidance below the box is reachable and announced, and
the over-length refusal is announced rather than only shown — the input
is marked invalid when over the limit, so a reader should learn the
search will not submit and roughly why.

### C3 — Page structure

On Awards search, an Award dashboard, and Global Search:

- Landmarks exist and are navigable — a main region, a navigation
  region.
- Heading order is sensible: one page heading, sections beneath it, no
  level skipped.
- Section tabs in the dashboard's left nav are announced as controls,
  with the active one distinguishable.
- Result cards are announced as links with enough text to tell them
  apart. "Link" repeated twenty times is a finding.

---

## Recording results

For each case, record against the standing dashboard policy: a case is
marked Passed only once its criterion is verified in the required
environment, and the entry carries the date, the environment, and what
was actually observed.

| Case | Protocol | Result | Environment | Observation |
|---|---|---|---|---|
| TC-023 | A1 | | UI build / API rev | |
| TC-027 | A2 | | UI build / API rev | |
| TC-020 | B1, C1 | | device / reader | |
| TC-045 | B2, C3 | | device / reader | |

**Where results go.** Into the QA dashboard entry for each case, and —
for anything naming a person, an account, or a specific allow/deny
fixture — into restricted test documentation rather than the general
status page.

**If a protocol can't be completed,** record why and stop there. A case
left honestly blocked is worth more than a case marked passed on a
partial run; TC-023 and TC-027 have been held open for exactly this
reason rather than closed on automated evidence alone.
