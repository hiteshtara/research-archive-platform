# Research Archive Platform — Client Presentation

*Companion documents: `AWARD_RAG_LIVE_DEMO_RUNBOOK.md` (the click-by-click
script), `AWARD_RAG_DEMO_DATASET.md` (the real numbers behind every claim
here), `AWARD_RAG_DEPLOYMENT_READINESS.md` (what's live vs. planned).*

---

## Opening statement

> The Research Archive Platform provides authenticated access to historical
> research administration records across the complete archived dataset.
> Users can search by known identifiers or concepts, inspect the full
> history of an Award, follow related records, access archived attachments,
> and use AI-supported summaries and evidence retrieval with traceable
> sources.

---

## Slide 1 — Research Archive Platform purpose

- A read-only, historical archive of Boston University's Kuali Research
  Administration data — preserved after the legacy Kuali system's
  retirement.
- Not a system of record; never writes back to source data.
- One authenticated web application covering Awards, Proposals,
  Negotiations, Subawards, and IRB records.

## Slide 2 — Full archived dataset coverage

- 8,773 distinct Award families, 49,827 archived Award version records.
- 57,023 investigator/role records, 198,937 amount records, 716,655
  sponsor and reporting term records, 199,129 comment records.
- 2,223 Award-linked negotiations, 720,428 attachment reference records
  spanning 37,777 unique physical files, all uploaded and retrievable.
- *(Every number sourced from a live, timestamped database query — see the
  dataset document, Section 1.)*

## Slide 3 — Search options available to users

- Search by Award number, title, sponsor, or investigator name — one
  search box, full-archive scope.
- Semantic (concept) search runs automatically alongside keyword search for
  natural-language queries.
- *(Two capabilities not yet available: filtering results by record type,
  and search by account number specifically — noted here for
  completeness, not part of the live demo.)*

## Slide 4 — Keyword versus semantic search

- Keyword search: exact and partial matches on identifiers, titles,
  sponsors, and investigator names — fast, precise, works today across the
  full archive.
- Semantic search: understands the *concept* behind a query, not just its
  exact words — runs automatically when a query doesn't look like a known
  identifier.
- The two run together and are merged into one ranked result list, with
  exact identifier matches always surfacing first.

## Slide 5 — Complete Award-family history

- Every archived version of an Award, in order, with the fields that
  changed between versions.
- Investigators and their roles, amount and funding history, sponsor and
  reporting terms, and Award comments — all drawn directly from the
  archived record.

## Slide 6 — Structured Award evidence

- A new, additive indexing layer (built this phase) that breaks each
  Award down into deterministic, source-cited pieces of evidence: its
  versions, people, amounts, terms, comments, and related records — each
  one traceable back to a specific archived database row, never to free
  text a model invented.
- **PLANNED / PHASE 3:** structured evidence retrieval. The indexing
  pipeline itself is built and tested; it has not been run against real
  data, and — separately and more importantly — no API or UI code exists
  yet to retrieve, filter, or cite the evidence it would produce. Running
  the indexer alone would not make this visible to a user.

## Slide 7 — Related proposals, negotiations, and subawards

- An Award rarely stands alone — this shows how it connects to the
  proposal that led to it, any negotiations tied to it, and any subawards
  funded through it.
- Each related record links back to its own full archived detail.
- Related proposals and related subawards are live today. Related
  negotiations is fixed and tested in this codebase but **not yet
  deployed** — do not present it as live until deployment is confirmed
  (see `AWARD_RAG_DEPLOYMENT_READINESS.md`).

## Slide 8 — Archived attachments

> Attachments are archived and available through the Award record. The
> current RAG implementation does not search inside attachment contents.
> Attachment-content retrieval is a separate future phase.

- Every attachment's metadata — filename, description, content type,
  Award version, and archival status — is preserved and browsable.
- Uploaded attachments can be opened or downloaded directly through the
  application's secured workflow.

## Slide 9 — AI summaries, retrieval, and citations

- The archive already generates full-dataset semantic search embeddings
  for every Award, Proposal, Negotiation, and Subaward — enabling
  concept-based discovery across the entire archive today, using the
  8,597 `AWARD_SUMMARY` embeddings already populated.
- **PLANNED / PHASE 3 (not currently available):** evidence-level
  retrieval, evidence-type filtering in the API/UI, evidence citations in
  the API/UI, and questions answered from newly populated evidence rows.
  The indexing pipeline that *produces* the underlying evidence rows is
  built and tested — but no API or UI code exists yet to retrieve, filter,
  or cite them, and running the indexer alone would not change that.
- AI Award Summary and AI Questions exist in the codebase but are not
  reachable today: the dev feature flag is off, and the UI panels are not
  wired into any page. Not part of this presentation's live demo — shown
  only as a labeled example of intended output, not a live claim.

## Slide 10 — Current capabilities and future phases

**Live now:** full-dataset keyword search; existing semantic Award-family
search using `AWARD_SUMMARY` embeddings; Award history, versions, people,
amounts, terms, comments; related proposals; related subawards; attachment
metadata; the secured attachment download path; Cognito authentication.

**Implemented but not currently reachable:** AI Award Summary; AI
Questions. The dev feature flag is off, and the UI panels are not wired
into a page. Related Negotiations: the completing backend code is written
and tested in this codebase, but is **not yet deployed** — do not claim it
is live until its commit is deployed and verified.

**Phase 3, not currently available:** evidence-level retrieval;
evidence-type filtering in the API/UI; evidence citations in the API/UI;
questions answered from newly populated evidence rows. Attachment-content
retrieval is a separate, later future phase, distinct from Phase 3.

---

## Required statements (verbatim, must appear as shown)

**Attachment statement:**
> Attachments are archived and available through the Award record. The
> current RAG implementation does not search inside attachment contents.
> Attachment-content retrieval is a separate future phase.

**Opening statement:** see top of this document.
