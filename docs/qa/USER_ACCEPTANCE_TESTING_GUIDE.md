# Research Archive Platform — User Acceptance Testing guide

Proposed 2026-11-11, for approval. **No invitation has been sent.** No identity
or grant is provisioned by this document, and nothing in Thursday's security
test, the frozen runbook, or any deployment configuration is changed.

Record numbers in this guide were opened on the development website on
2026-11-11 and are real. Test cases marked TC-0xx already exist and are reused;
only the genuinely missing ones are new.

---

## 1. What RAP is, and what a successful test looks like

**The Research Archive Platform is a read-only historical record.** When BU
retired the legacy Kuali Research system, the data did not go away — it was
copied here so that staff can still look things up. RAP is where you go to
answer "what did this award say in 2019?"

Three things follow, and they explain most of what can seem odd at first:

- **Nothing can be edited.** There are no Save buttons, no forms, no approvals.
  If you find a way to change data, that is a serious defect — report it
  immediately.
- **It is not a system of record.** It never writes back to Kuali or to SAP.
  Figures here support lookup and history, not reconciliation against finance.
- **Old versions are the point.** An award that changed eleven times has
  eleven archived versions, and all eleven are preserved deliberately. Seeing
  several similar rows is usually correct, not duplication.

### What a successful test looks like

Not "I clicked around and nothing crashed." A successful test is one where you
can say, for a specific record:

> "I looked for **X**, I found it, and what it showed me matched what I
> expected to be true about that record."

The most valuable thing a business tester brings is **knowing what the data
should say.** An engineer can confirm a page loads; only you can confirm that
the sponsor on award 212137-00001 is the sponsor it ought to be. So the
scenarios below ask you to judge *content*, not just behaviour.

**"I don't know what this should say" is a valid, useful result.** Record it as
QUESTION. It usually means a label is unclear, which is itself a finding.

---

## 2. Functional UAT is not security authorization testing

These are two different exercises and must not be mixed. Keeping them apart is
the single biggest improvement to this process.

| | Functional UAT (this guide) | Security authorization testing |
|---|---|---|
| Question | "Is the information correct, complete and usable?" | "Can people see only what they are entitled to see?" |
| Who | Lalitha, Shari, Dean, Warren, John | One designated tester, in a controlled window |
| When | Any time; no special window | Thursday 12 November, 16:00–17:30 ET only |
| State | Record enforcement **OFF** | Record enforcement **ON** |
| Test cases | TC-0xx in this guide | The 13 technical cases and the 24-row acceptance matrix |

**Why this matters to you as a tester.** During functional UAT record
enforcement is off, which means **everyone sees every record.** That is the
approved configuration for DEV today. So do **not** try to test "can I see
someone else's department's award?" during UAT — the answer will be yes, and
that is expected, not a defect.

### There are three separate permission systems. Only one is being changed Thursday

Knowing this will save you from reporting expected behaviour as a bug:

1. **Record authorization** — grants per department, award or account. **Off
   today**; being tested Thursday only.
2. **Administrator membership** — who may manage access. Not part of UAT.
3. **Attachment viewing** — a separate group, `ArchiveAttachmentViewer`, which
   is **active right now, regardless of the other two.**

Number 3 is the one you will actually meet. Verified on 2026-11-11: opening
attachments without that group membership returns

> "Attachment access requires membership in the ArchiveAttachmentViewer group"

That message is **correct behaviour**, not a defect. Report it only if you
believe you *should* have attachment access, or if the message appears where no
attachment was involved.

---

## 3. What already exists — reuse, not reinvention

There is an existing QA matrix, authored by **John**: **51 test cases**
(TC-001…TC-052), of which 44 passed, 2 are blocked, 3 await a business decision
and 2 need further evidence. It is held in
`ui/src/features/qa/qaSnapshot.json` and shown on the `/qa-status` page.

Existing coverage per module, and what is missing:

| Module | Existing cases | Gap |
|---|---|---|
| Dashboard | 3 | adequate |
| Awards (search, detail, hierarchy, historical) | 22 | adequate |
| Proposals (incl. search) | 5 | thin but workable |
| Global Search | 3 | adequate; TC-041 still open |
| Negotiations | 5 | adequate |
| Navigation / Non-Functional | 10 | adequate |
| **Subawards** | **1** | **under-tested** |
| **Documents** | **0** | **no coverage at all** |

So this guide **adds new cases for Subawards and Documents only**, and reuses
the rest. New cases are numbered from **TC-056** upward: TC-053 to TC-055 are
already taken on an unmerged branch, and reusing them would collide.

---

## 4. Tester assignments

Assigned from documented responsibilities only. Where a person's
responsibilities are not on record, **no assignment is invented** — see Warren
below.

| Tester | Documented basis | Proposed focus | Confidence |
|---|---|---|---|
| **Lalitha Sudhireddy** | Filed the TC-040 and TC-041 Global Search findings — the most active tester on record | **Global Search** and cross-module search quality; re-check TC-040/TC-041 | **High** — her own prior findings |
| **John Nickerson** | **Authored the QA matrix** these cases come from | **Regression ownership**: Dashboard, Navigation, Non-Functional; adjudicating whether a reported problem is already a known case | **High** — he wrote the corpus |
| **Shari Huval** | Project correspondence on **BU data migration** | **Data accuracy and completeness** in Awards and Proposals — does the archived content match what the source system held | **Medium** — inferred from migration involvement |
| **Dean Haywood** | Authored the **Grants & Agreements solution architecture proposal** | **Cross-module coherence**: Award → Proposal → Negotiation → Subaward navigation, and whether relationships are represented correctly | **Medium** — inferred from architecture role |
| **Warren Hennemuth** | Invited to test on 2026-10-23. **No further documented responsibility** | **Not assigned.** Ask Warren which area he works with before allocating | **None — deliberately blank** |

Suggested options to offer Warren, rather than assuming: Negotiations,
Subawards, or Documents — the three least-covered modules. He should pick.

**Nobody is assigned security authorization testing.** That stays with the one
designated tester in Thursday's window.

---

## 5. Guided checklists

How to read a scenario: **Go** is where to click, **Expect** is what should
happen, **PASS/FAIL** is how to judge it. If the result is neither clearly pass
nor clearly fail, record it as QUESTION and move on — do not spend more than a
few minutes on any single step.

All record numbers were verified openable on 2026-11-11.

### 5.1 Dashboard — reuse TC-004, TC-005, TC-006

The Dashboard is the landing page and shows how much is in the archive.

**D-1 — The counts mean what they say.**
- **Go:** open the site root.
- **Expect:** seven counts, each labelled. "Awards" counts distinct award
  numbers; "Historical Award Records" counts every archived version, so it is
  legitimately much larger.
- **PASS:** every count has a label you can explain to a colleague, and
  Historical Award Records exceeds Awards.
- **FAIL:** any count is blank, zero, shows a loading spinner that never
  resolves, or a label you cannot interpret.

**D-2 — The counts are stable.**
- **Go:** note the seven numbers, reload the page.
- **Expect:** identical numbers.
- **PASS:** unchanged. **FAIL:** any number changes between reloads.

### 5.2 Awards — reuse TC-007…TC-029

Awards are the core of the archive. An *award* has an award number; each change
to it is an archived *version* with a sequence number.

**A-1 — Find a known award.**
- **Go:** Awards → search `212137-00001`.
- **Expect:** one award family found.
- **PASS:** it appears and opens. **FAIL:** no result, or an error.

**A-2 — Version history is complete and ordered.**
- **Go:** open `212137-00001`, then its history.
- **Expect:** **exactly 3 versions**, sequences 3, 2, 1. Sequence 3 is current.
- **PASS:** three versions, highest marked current.
- **FAIL:** a different count, or no version marked current.
- *Verified 2026-11-11: award IDs 3798004 (seq 3), 3787291 (seq 2), 3785806 (seq 1).*

**A-3 — A longer history.**
- **Go:** open `114471-00001` and its history.
- **Expect:** **7 versions**, sequence 7 current, status "Closed".
- **PASS:** seven versions and a closed status. **FAIL:** anything else.

**A-4 — Business content is right.** *(the judgement only you can make)*
- **Go:** open `212137-00001`.
- **Expect:** sponsor, lead unit, PI and dates are populated and plausible.
  Lead unit should read as a College of General Studies unit.
- **PASS:** the content matches what you know to be true.
- **FAIL:** a field is empty where it should be filled, or contradicts the
  source system. **QUESTION:** you cannot tell.

**A-5 — Attachments (expect a refusal today).**
- **Go:** open an award and try to view attachments.
- **Expect:** either the attachment list, or the `ArchiveAttachmentViewer`
  message from §2.
- **PASS:** either outcome, provided the message is clear and names no record
  you were denied.
- **FAIL:** a raw error, a blank page, or a message exposing details of
  something you cannot see.
- *This is the negative half of blocked cases TC-023 and TC-027 — see §9.*

### 5.3 Proposals — reuse TC-030…TC-034

**P-1 — Find proposals by sponsor.**
- **Go:** Proposals → search `NSF`.
- **Expect:** National Science Foundation proposals, matching on whole words.
- **PASS:** results are NSF-related. **FAIL:** unrelated records appear, or
  obvious NSF proposals are missing.

**P-2 — Open a known proposal.**
- **Go:** search `01091593`.
- **Expect:** an ACTIVE proposal, sponsor National Science Foundation.
- **PASS:** opens with that sponsor. **FAIL:** not found, or a different sponsor.

**P-3 — A second sponsor, to confirm it is not NSF-only.**
- **Go:** search `01091619`, then `01091620`.
- **Expect:** both ACTIVE, sponsor Boston Medical Center Corporation.
- **PASS:** both open correctly. **FAIL:** either fails.

### 5.4 Global Search — reuse TC-040, TC-041, TC-042

Searches Awards, Proposals, Negotiations and Subawards together.

**G-1 — Whole-word matching.**
- **Go:** Global Search → `NSF`.
- **Expect:** each result relates to NSF. `NSF` must **not** match inside an
  unrelated word such as "transfer".
- **PASS:** no mid-word matches. **FAIL:** any result where NSF appears only
  inside a longer word.

**G-2 — Counts are honest about being partial.**
- **Go:** same search; read the heading above the results.
- **Expect:** wording that distinguishes what is shown from what matched, e.g.
  "Showing 25 of 3,736", with a link to see all of a record type.
- **PASS:** you can tell shown-vs-total apart. **FAIL:** a single number
  implying it is everything.

**G-3 — A search with no good answer says so (TC-041, open).**
- **Go:** search something that should match nothing, e.g. `zzzqqq`.
- **Expect:** a clear "no direct matches" message. Loosely-related suggestions,
  if any, must sit behind a button, not be presented as answers.
- **PASS:** no-match stated plainly. **FAIL:** unrelated records presented as
  results.
- *TC-041 is a known open finding: the engine still returns its nearest
  matches however unrelated. Confirming it is useful; it is not a new defect.*

**G-4 — Response time (TC-042, decision open).**
- **Go:** time a Global Search.
- **Expect:** no agreed target exists yet. Recorded median was about
  **11.5 seconds**.
- **Record the time.** Do not pass or fail it — this is awaiting a business
  decision on an acceptable target. Your opinion on what is tolerable is the
  input needed.

### 5.5 Negotiations — reuse TC-035…TC-039

A negotiation has **two** identifiers: a Negotiation ID (how the archive
addresses it) and a document number (what people quote from Kuali). Both should
be visible.

**N-1 — Open by ID.**
- **Go:** Negotiations → `12789`.
- **Expect:** opens; shows document number `1153041`.
- **PASS:** both identifiers visible and labelled.
- **FAIL:** only one shown, or the same number printed twice.

**N-2 — Find by document number.**
- **Go:** search `1140144`.
- **Expect:** negotiation `12559`.
- **PASS:** found via the document number. **FAIL:** not found.

**N-3 — Search result cards name both identifiers.**
- **Go:** Global Search → `1152903`.
- **Expect:** a card reading like "Negotiation 12788 · Document 1152903".
- **PASS:** both present, neither duplicated. **FAIL:** the Negotiation ID is
  missing.

### 5.6 Subawards — **new cases TC-056 to TC-059** (only 1 existed)

A subaward is money passed to another organisation.

**TC-056 — Open a known subaward.**
- **Go:** Subawards → `95015`.
- **Expect:** opens; subaward code `4092`, status "07. Executed".
- **PASS:** opens with that code and status. **FAIL:** anything else.

**TC-057 — Status values are human-readable.**
- **Go:** open `95014` (code `4188`) and `95013` (code `4390`).
- **Expect:** "02. Subaward RA initial review" and "10. Cancelled".
- **PASS:** statuses read as sentences, not raw codes.
- **FAIL:** a bare code or blank status.
- *Business judgement wanted: is the numeric prefix helpful or noise?*

**TC-058 — Search by subaward code.**
- **Go:** Subawards → search `4092`.
- **Expect:** the matching subaward.
- **PASS:** found. **FAIL:** not found, or unrelated results.

**TC-059 — Subaward relates to its award.**
- **Go:** open any subaward; look for the funding award.
- **Expect:** a named related award, navigable.
- **PASS:** the relationship is shown and the link works.
- **FAIL:** no relationship shown, or a link that fails.
- *Dean's cross-module focus.*

### 5.7 Documents — **new cases TC-060 to TC-063** (no coverage existed)

The Documents module finds an archived document across every module.
Verified 2026-11-11: a search for `NSF` matches **491,685** documents.

**TC-060 — Document search returns results.**
- **Go:** Documents → search `NSF`.
- **Expect:** many results, each naming its module (AWARD, PROPOSAL, …), its
  document number and its business record number.
- **PASS:** results appear and each names its module and record.
- **FAIL:** no results, an error, or rows you cannot attribute to a record.

**TC-061 — A document row opens its record.**
- **Go:** click a result.
- **Expect:** the underlying record opens. For example document number `1`
  belongs to award `100004-00001`.
- **PASS:** you land on the right record. **FAIL:** a dead link or the wrong
  record.

**TC-062 — The result count is honest.**
- **Go:** read the count on a broad search.
- **Expect:** a total, with clear indication that only a page is displayed.
- **PASS:** shown-vs-total distinguishable. **FAIL:** a page size presented as
  a total.

**TC-063 — Document numbers are not confused with record numbers.**
- **Go:** compare `documentNumber` and `businessRecordNumber` on several rows.
- **Expect:** two different values, separately labelled.
- **PASS:** you can tell which is which from the labels alone.
- **FAIL:** the labels are ambiguous or the values are conflated.
- *This is the single most likely source of confusion in this module.*

---

## 6. How to report a problem

One route, one form, always linked to a test case. Free-text email is what made
the previous round hard to act on.

### Required fields

| Field | Why |
|---|---|
| Test case ID | e.g. TC-056, or the scenario label such as A-2. **"General browsing" is not acceptable** — if no case fits, that itself is the finding |
| Your name | who observed it |
| Date and time | so it can be matched to server logs |
| Record number | the exact award, proposal, negotiation, subaward or document |
| What you did | the steps, briefly |
| What you expected | the business expectation |
| What happened | what the screen actually showed |
| Result | **PASS / FAIL / QUESTION / BLOCKED** |
| Severity | Blocks my work / Wrong information / Confusing / Cosmetic |
| Screenshot | optional, encouraged |

### The four results, and when to use each

- **PASS** — it did what you expected.
- **FAIL** — it did not. Say what you expected.
- **QUESTION** — it worked, but you cannot tell whether the content is right.
  **This is a valid and valuable result.**
- **BLOCKED** — you could not run the test. Say why. A permission refusal is
  usually BLOCKED, not FAIL.

### Never include

Passwords, screenshots of a sign-in page, or anything from another person's
session. If a defect seems to expose data you should not see, **do not
screenshot it** — report the record number and say what you saw.

---

## 7. The QA results dashboard

What it must show, drawn from your five requirements:

| Panel | Content | Source |
|---|---|---|
| Who tested what | tester × module, with last-tested date | submitted reports |
| Passed and failed | per module and per case | reports + `qaSnapshot.json` |
| Untested functionality | cases never attempted, **and modules with no cases at all** | the gap table in §3 |
| Reported defects | open findings, severity, owner | reports + `qaOpenFindings.json` |
| Outstanding business decisions | the items in §9 | `qaSnapshot.json` status `decision` |

The "untested" panel matters most and is the one usually missing: an empty
module reads as "nothing wrong" when it means "nobody looked". Documents had
zero cases until this guide.

### Two ways to build it — your choice

**Option A — extend the existing `/qa-status` page.** It already reads
`qaSnapshot.json` and `qaOpenFindings.json` and already renders cases and
findings. Adding tester-attribution and an untested panel is a contained
change. Keeps everything in BU infrastructure and in version control.
*Cost: application code, so it needs a release. Note `/qa-status` is currently
documented as temporary QA scaffolding.*

**Option B — a private hosted dashboard with a linked feedback form.** Faster,
no release needed, and the form can write straight into the dashboard so
results appear as they are submitted. *Consideration for you to weigh: BU staff
feedback would be collected and stored outside BU infrastructure, and a
BU-branded QA portal should not be published without your decision on that.*

**Recommendation:** Option A for the dashboard, because the data already lives
there and belongs in version control. For the form, start with whatever your
team already has — a SharePoint list or Microsoft Form writing to a list is
sufficient and keeps the data inside BU. The form's only hard requirement is
that **test case ID is mandatory**.

I have not built either. Say which you want.

---

## 8. Running order

1. Approve this guide, the assignments, and the dashboard option.
2. Ask Warren which area he works with; assign then.
3. Stand up the feedback form; confirm the case-ID field is mandatory.
4. Send invitations — **not before your approval.**
5. Testers work their assigned modules. No fixed window; UAT needs none.
6. John triages incoming reports against TC-001…TC-052 to separate new
   findings from known ones.
7. Review: what passed, what failed, what nobody tested.

Thursday's security window runs independently and is unaffected.

---

## 9. Outstanding business decisions

These are not defects. They are questions only the business can answer, and
they are already recorded against existing cases.

| Case | Question | Who |
|---|---|---|
| TC-019 | Should search hint tags stay informational, or be clickable filters? | business |
| TC-029 | Is a specific-sequence selector needed on Historical Awards, beyond All/Current/Historical? | business |
| TC-042 | What is an acceptable Global Search response time? Recorded median ~11.5s | business |
| TC-041 | Accept that unrelated near-matches may appear, or withhold them and risk hiding real searches? No filter yet tested does both | business |
| TC-020, TC-045 | Real-phone and screen-reader verification still outstanding — is emulated-width evidence enough? | business |
| TC-023, TC-027 | Blocked on an attachment-permission tester. **Partly unblocked** — see below | business + QA |

### TC-023 and TC-027 may now be partly testable

Both were blocked waiting for "an authorized tester without attachment-viewing
permission, signed in with their own account." Verified on 2026-11-11: the
designated test account **is** such a tester — attachment requests return
`403 ATTACHMENT_ACCESS_DENIED`, "requires membership in the
ArchiveAttachmentViewer group".

So the **negative** half of both cases is testable now, with no provisioning
(scenario A-5). The **positive** half — a tester who *does* hold the group —
still needs group membership, which is provisioning and is **not authorized**.
Recommendation: close the negative half on this evidence, and keep the positive
half open pending a decision on who should hold attachment access.

---

## 10. Not authorized by this document

No invitation sent. No identity, grant or group membership provisioned. No
change to Thursday's security test, the frozen runbook, deployment
configuration, CI, or application code. No new Cognito account or group.
Production untouched.
