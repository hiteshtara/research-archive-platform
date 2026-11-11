# Response to QA — TC-040 and TC-041

*Written for Lalitha Sudhireddy (QA). Draft for review before sending.*

---

Hi Lalitha,

Thank you for these — they were specific enough to reproduce exactly, and two
of them found real problems I would not have caught otherwise. I re-ran
everything on the current development site (API revision 87, UI build 186)
and here is what each one turned out to be.

## Where you were right

**The result count is misleading.** You were right that the number is not the
number of matching records. Searching "NSF" reported **104 results**, but
that was four separate page-sizes added together — each record type returns at
most 25. The Negotiations module on its own matched **234**. I have changed
this: the page now says "Showing 50 of 1434 matching records", lists the
per-type breakdown, and says plainly that each type returns at most 25. Where
a record type cannot count its matches, it now says "at least N" rather than
inventing a total.

**The NSF result was the most valuable thing you reported**, and the cause
was not what I assumed either. Award 100527-00001 is titled "NATIONAL
SCIENCE FOUNDATION GRADUATE RESEARCH FELLOWSHIP PROGRAM", sponsored by the
National Science Foundation — and the letters "NSF" appear nowhere in it.
Search matches on letter sequences, so "NSF" cannot match that award
directly. What it *does* match is the "nsf" inside ordinary words:

- "ENERGY TRA**NSF**ER IN PROTEINS"
- "TRA**NSF**ORMING A BIODIVERSITY HOTSPOT"
- every "Material Tra**nsf**er Agreement" negotiation

So the direct results fill up with words containing n-s-f, while the award
that really is NSF-funded is pushed into "related". The relevance is
backwards. Fixing it properly means changing what counts as a match, so it
changes what counts as a match, so it is in review separately rather than
bundled with the count fix. The short version of the fix: a short alphabetic
term now has to match the START of a word, so "NSF" stops matching inside
"transfer" — while "cardio" still finds "cardiology", because only the start
of the word is anchored. And because award 100527 contains no "NSF" at all,
it is now reached through its sponsor's recorded acronym.

**Award team-member matches genuinely do not explain themselves.** A search
can match a team member, a grant number or a sponsor code, but the result
card can only show the award number, title, sponsor, lead unit and PI. When
the match was none of those, the card falls back to "Matched on: Award",
which tells you nothing. I have a fix in review that has the search itself
report which field matched, so a card can say "Matched on: Team member
(name, role)" instead of guessing.

## Where the behaviour is intended

**25 per record type** is a deliberate limit, not a bug. Global Search runs
five searches at once and the limit keeps it fast. Use a record type's own
page to page through all of its matches.

**Negotiation cards showing the document number** is consistent with how the
data is stored, but I agree it is confusing when the detail page is addressed
by Negotiation ID. I have a fix in review that shows both, each labelled —
"Negotiation 6447 · Document 760067".

## One I could not reproduce — could you check?

**Negotiation 6447 / document 760067 is not actually missing.** It does match
"WILSON WONG" (through its associated award's PI), but it is one of **234**
matches and sorts well past the first 25. Importantly, the **Negotiations
page shows the same 25 and also does not list it on page 1** — I tried both
the main search box and the PI filter. Global Search is not dropping it.

Searching its document number `760067` finds it immediately, as the top
result.

So could you tell me which box you typed into when it "appeared first in
Negotiations search"? If you searched `760067`, that explains it — an exact
document-number match is deliberately promoted to the top. I would like to be
sure before I close it.

## TC-041 — still open, and you were right to keep questioning it

Both nonsense searches still return five "related" results:

- `zzzznotfound123` → five Negotiations
- `aavvvlkkjhuipkknbhgkj` → four Awards and a Proposal

The site handles this correctly now — it says "0 results found" and "No
direct matches", and the five sit behind a "Show 5 related results" button,
so they are not presented as answers. That part is fixed and deployed.

**But the underlying behaviour is unchanged:** the system still returns its
five nearest matches no matter how far away they are. There is no minimum
relevance anywhere in it. I checked the change you may have seen credited
with fixing this (PR #28): it is deployed, and it changed how the results are
*presented* — it did not add any relevance filter. An earlier attempt did
hide them, and was deliberately reversed, because it also hid genuinely
useful results for people who search using a different word than the archive
uses.

So I am treating TC-041 as **not resolved**, and keeping it separate from
TC-040's pass.

What I will not do is pick a relevance cutoff out of the air — an earlier
experiment already found that no single number worked across record types,
and I can see why: a Negotiation's searchable text is just a document number,
an agreement type, a negotiator and a status, so thousands of them look
nearly identical to the system. I have started recording how far away each
result actually is, so the next step is to measure a set of good and bad
searches and choose a rule on evidence. Your two nonsense strings are now
part of that set.

## Summary

| Your observation | Outcome |
|---|---|
| Result count misleading | Fixed |
| NSF awards shown as "related" | Confirmed defect; fix in review |
| Team-member matches unexplained | Confirmed defect; fix in review |
| 25 per record type | Intended |
| Negotiation cards show document number | Improvement in review |
| Negotiation 6447 missing | Not missing — please confirm which search box |
| TC-041 nonsense results | Presentation fixed; relevance still open |

Nothing is released yet. The count fix and the matching fix are in separate
reviews, so they can be released independently.

Thanks again. The NSF one in particular was a genuinely good catch.

---

## Acceptance cases for the completed NSF fix (P1-P9)

Added 2026-11-11, when the fix was extended to Proposal and Subaward. The
first release anchored Award and Negotiation only; measured against the
running build, 59 of the first 100 Proposal rows and 70 of the first 100
Subaward rows for "NSF" still contained no NSF at all.

Each case is executed by
`ProposalSubawardLexicalRelevanceIntegrationTest.AcronymAcceptance` against a
real PostgreSQL, driving the real repositories rather than a copy of their
SQL. All nine pass.

| # | Case | Expected |
|---|---|---|
| P1 | An NSF-sponsored Proposal with no "NSF" in any text field | returned |
| P2 | The same Proposal with the sponsor master emptied | **not** returned, proving the acronym is the path |
| P3 | `TRANSFORMATION`, `methyltransferase`, `TRANSFORM DBS`, `Chemical Transformations` | **not** returned for "NSF" |
| P4 | A Proposal whose sponsor acronym is `SOS`, searched as "NSF" | **not** returned; searching "SOS" **does** return it |
| P5 | Global Search's Proposal leg vs the Proposal module page | identical Proposals, for five different terms |
| P6 | The Proposal module page vs its own total | identical counts, for five different terms |
| P7 | Award, searched as "NSF" | `100527-00001` returned by acronym; `100543-00001` still excluded |
| P8 | Negotiation, searched as "NSF" | nothing; searching "transfer" still finds the Transfer Agreements |
| P9 | Subaward, searched as "NIH" | **not** returned — see the limitation below |

### One thing testers should know before they start

**Subaward has no sponsor-acronym path, and will not gain one here.**
`archive.subaward` carries only the denormalised sponsor names
(`award_sponsor_name`, `award_prime_sponsor_name`) and no sponsor code or
Award link, so there is nothing to join to the sponsor master.

An acronym therefore reaches a Subaward **only when the acronym happens to be
written into the stored sponsor name itself.** Corrected 2026-11-11 against
live DEV data, because the first version of this note got the example
backwards:

| Searched | Stored sponsor name on DEV | Reaches Subawards? |
|---|---|---|
| `NIH` | `NIH/National Institute of …` | **yes** — the letters are right there at a word start (6,230 rows) |
| `NSF` | `National Science Foundation` | **no** — the string contains no "NSF" at all |

That asymmetry is the whole reason NSF Subawards fell from 287 to 162 while
NIH Subawards were unaffected: the 162 that remain are the ones whose *title*
literally says NSF, such as "NSF PCL-Test Bed". It is luck of the data, not a
capability — do not rely on it for any other acronym without checking the
stored name first.

This is not a regression: those records were never reachable by the acronym.
Before the fix they matched "NSF" only through letter coincidences like
"phosphoglycosyl tra**nsf**erase", which is not the same as being findable.
Giving Subaward a real acronym path needs a verified extraction mapping and is
a separate change.

### What the sponsor master is

`archive.sponsor` is Kuali's shared sponsor master, loaded by
`sql/extract/reference/17_sponsor.sql` and referenced by Award, Negotiation
and Proposal alike - not a lookup table invented for search. That it carries
acronym "NSF" against the NSF sponsor code is already proven on the running
build by the Award leg: a live search for "NSF" returned 25 Awards captioned
"Matched on: Sponsor (NSF)", and that caption is read from
`archive.sponsor.acronym`. Proposal joins the same master on the same column,
so the same 3,746 NSF-sponsored Proposals become reachable.
