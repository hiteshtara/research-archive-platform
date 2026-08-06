# Research Object Model — Design and Implementation Record

## Purpose

Record the presentation shape now shared by all four implemented research
objects (Award, Proposal, Negotiation, Subaward), now that Subaward has
reached the same quality bar as the other three. This is a different concern
from two existing kinds of doc in this repo, and doesn't replace either:

- Root [`CLAUDE.md`](../../CLAUDE.md)'s "Research object model and business
  grain" section records **counting/grain rules** (e.g. Award's business
  grain is `COUNT(DISTINCT award_number)`, distinct from the historical
  version-row count) — this doc does not restate those rules.
- Each domain's `*_ARCHIVE_COVERAGE.md`
  ([`KUALI_ARCHIVE_COVERAGE.md`](KUALI_ARCHIVE_COVERAGE.md),
  [`PROPOSAL_ARCHIVE_COVERAGE.md`](PROPOSAL_ARCHIVE_COVERAGE.md),
  [`NEGOTIATION_ARCHIVE_COVERAGE.md`](NEGOTIATION_ARCHIVE_COVERAGE.md),
  [`SUBAWARD_ARCHIVE_COVERAGE.md`](SUBAWARD_ARCHIVE_COVERAGE.md)) records
  **data completeness** — which Kuali tables/columns are archived. This doc
  does not restate those checklists either.

This doc answers a third question: once the data is archived, what shape
does a user-facing workspace page take, and why does every module converge
on the same shape despite having genuinely different business content.

## Scope

The four implemented research-object workspace pages
(`ui/src/pages/award/AwardDashboardPage.tsx`,
`ui/src/pages/proposal/ProposalDashboardPage.tsx`,
`ui/src/pages/NegotiationWorkspacePage.tsx`,
`ui/src/pages/SubawardWorkspacePage.tsx`). Does not cover IRB, which is
explicitly a separate, self-contained domain outside the Proposal → Award →
Funding → Negotiation → Investigator chain (see `CLAUDE.md`).

## The shared shape

**Header → Relationships → Business History → Supporting Data.**

A user opening any of these four pages should be able to answer, in that
order: what is this record (Header), what else does it connect to
(Relationships), how did it change over time (Business History), and what
detail backs it up (Supporting Data). Each module instantiates the same four
concerns with genuinely different content — the shape is shared, the
business meaning is not.

### Award

- **Header**: `award_number` (the business identifier, not `award_id`) —
  see [`AWARD_IDENTIFIER_MODEL.md`](AWARD_IDENTIFIER_MODEL.md).
- **Relationships**: the `fundingProposals` and `fundingSubawards` sections
  on `AwardDashboardPage.tsx` (`SECTIONS`, lines 34-45) — reverse-linked
  Proposals and Subawards, each rendered as co-equal cards with an honest
  "Not currently archived" fallback, never hidden.
- **Business History**: Award Versions — `archive.award_version`, resolved
  via `is_primary_current`; see
  [`AWARD_IDENTIFIER_MODEL.md`](AWARD_IDENTIFIER_MODEL.md) and
  [Award Versions](../kuali-business-rules/Award%20Versions.md).
- **Supporting Data**: People and Units, Amounts, Budget, Time & Money,
  Terms, Comments and Notepad, SAP Transmission History, Attachments — the
  remaining `SECTIONS` entries.

### Proposal

- **Header**: the stable institutional Proposal business identifier (not
  `archive.award_funding_proposal`'s row — see `CLAUDE.md`'s Proposal grain
  note).
- **Relationships**: "Funded Awards" (`ProposalDashboardPage.tsx`
  `SECTIONS`, line 30) — the forward link Proposal → Award, mirrored by
  Award's own reverse-linked Funding Proposal section.
- **Business History**: Versions (`SECTIONS`, line 29).
- **Supporting Data**: People and Units, Attachments, Comments, Sponsor and
  Program, Custom Data.

### Negotiation

- **Header**: Summary tab (`NegotiationWorkspacePage.tsx` `tabs`, line 47).
- **Relationships**: "Associated Record" — a single polymorphic link to
  whichever of Award/Proposal/Subaward this Negotiation is tied to
  (`ASSOCIATION_ROUTE`/`ASSOCIATION_LABEL`, lines 56-65).
- **Business History**: "Activity Timeline" — the module's defining tab,
  same role as Subaward's History of Changes.
- **Supporting Data**: Attachments, Custom Data, Notifications.

### Subaward

- **Header**: Subaward Code (`current.subawardCode`,
  `SubawardWorkspacePage.tsx`) — promoted over the internal
  `subawardId`/`subawardCloseoutId`-style identifiers this session, matching
  how Award/Proposal lead with their business numbers, not surrogate keys.
- **Relationships**: "Associated Award(s)" (Funding tab) — plural-aware,
  co-equal cards (`resolveAssociatedAwardsSectionLabel`,
  `subawardFundingPresentation.mjs`), since a Subaward can legitimately have
  multiple concurrent Award funding sources with no primary/current flag in
  Kuali's own schema or business rules. Reverse-linked from Award's own
  Subawards section (`AwardFundingSubawardsSection.tsx`), completing the
  same bidirectional pattern Proposal↔Award already has.
- **Business History**: "History of Changes" (renamed from "Amounts" this
  session to make it the defining, self-describing tab) — per-amendment
  timeline cards (`buildAmendmentTimeline`,
  `subawardAmountsPresentation.mjs`) built entirely from already-archived
  `archive.subaward_amount` rows, with the original grid preserved as an
  optional "Technical View" toggle rather than replaced.
- **Supporting Data**: Contacts (business identity — Name/Role/
  Organization/Phone/Email, resolved through `CONTACT_TYPE` and either
  `archive.rolodex` or `archive.person`, raw IDs demoted to secondary),
  Attachments (grouped by business type via `groupAttachmentsByType`,
  `subawardAttachmentsPresentation.mjs`), Custom Data, Closeout.

  **Template Info, Reports, Notepad, and Notifications are archived and
  fully reachable via their existing API endpoints, but intentionally not
  in the primary tab bar** — a v1 scope decision, not a data gap. Reports
  and Notepad were judged low business value relative to the rest of
  Subaward (Reports is a small, mostly-cosmetic lookup checklist; Notepad
  duplicates simple note functionality); Template Info and Notifications
  were cut from primary nav in the same pass to keep the tab bar to seven
  entries. If reintroduced, the proposed home is a secondary "More" menu
  (`More → Template Info / Notifications / Reports / Notes`) — not built in
  this pass. No backend/ETL/API work is needed to add that menu later.

## Why Business History is the recurring hard problem

In every module, Business History is where the interesting design work
happened, because the underlying archive data is row-level and technical
(a version table, a modification-type code, an amount-change column) while
the business question is narrative ("what happened to this record over
time, in plain terms"). Award Versions, Negotiation's Activity Timeline, and
Subaward's History of Changes are three independently-arrived-at solutions
to the same problem: present already-archived rows as a chronological,
business-labeled sequence rather than a raw grid. Subaward's specifically
started from "the current Amounts tab exposes the database rows correctly,
but it misses the business concept shown in Kuali" — the same complaint
would apply to any module's Business History tab if it were left as a bare
table.

## v2-parked, not forgotten

The following are documented but deliberately not implemented, and this doc
does not change that:

- `SubAwardComment`, `SubAwardTemplateAttachment`, `SubAwardFfataReporting`,
  and `SubAwardAmountReleased` — see
  [`SUBAWARD_ARCHIVE_COVERAGE.md`](SUBAWARD_ARCHIVE_COVERAGE.md)'s coverage
  matrix.
- FDP Agreement reconstruction — see
  [`SUBAWARD_FDP_RECONSTRUCTION.md`](../kuali-business-rules/SUBAWARD_FDP_RECONSTRUCTION.md).
- Subaward's Template Info/Reports/Notepad/Notifications primary-nav
  removal, per the Subaward section above.

## Decisions

- **New file, not an edit.** No `docs/architecture/RESEARCH_OBJECT_MODEL.md`
  existed before this pass — confirmed via `find docs -iname
  "*object*model*"` returning nothing. It is not a rename or continuation of
  `CLAUDE.md`'s grain-rules section, which covers a different concern (see
  Purpose).
- **Subaward's Closeout tab keeps its raw `closeoutTypeCode`** rather than a
  resolved description, pending live verification that `CLOSEOUT_TYPE`
  exists in BU's actual Oracle instance — it is absent from
  `reference/kuali/subaward-oracle-columns.txt`, this project's verified
  column inventory, even though Kuali's own Java/OJB source (`repository-
  subAward.xml`, `SubAwardCloseout.xml`'s `ExtendedPersistableBusinessObjectValuesFinder`
  wiring) proves BU KC's runtime code queries a live `CLOSEOUT_TYPE` table
  with no hardcoded fallback. This doc records Closeout as "kept, high
  business value" per the v1 scope decision, independent of how that
  specific lookup question resolves.
