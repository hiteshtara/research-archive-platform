# TC-040 and TC-041 — Global Search investigation

Reproduced against the live DEV environment on **2026-11-10**: API ECS
revision **87** (source commit `164b242`), UI Amplify job **186** (commit
`7450033`), signed-in session, read-only. Every figure below came from the
API responses the browser actually received; none is inferred from code
alone.

**Nothing was changed in DEV.** No authorization, grant, ECS, RDS or
Production change was made.

## Summary

| # | Observation | Verdict |
|---|---|---|
| 1 | 25 direct matches per entity type | **Intended** — a fixed performance bound |
| 2 | How the 25 are selected and ordered | **Intended**, but undocumented and differs per module |
| 3 | `totalResults` counts returned rows | **Defect** — fixed in this branch |
| 4 | Negotiation 6447 missing for WILSON WONG | **Not a defect** — position 26+ of 234; same in the module page |
| 5 | Award team-member matches do not explain the role | **Defect** — the label cannot see the column that matched |
| 6 | Negotiation cards lead with the document number | **Business decision** — consistent with the data, inconsistent with the detail page |
| 7 | NSF awards appear as related, not direct | **Defect, and the most serious one** — substring matching inverts relevance |
| TC-041 | Nonsense queries return five semantic results | **Unresolved** — no threshold exists; PR #28 fixed the presentation, not the ranking |

TC-040's functional pass stands: labels and routing are correct, which is
what the case tested. The findings above are separate from that pass.

---

## TC-040

### 1. Why 25 per entity type

`GlobalSearchService.PER_DOMAIN_LIMIT = 25`, a compile-time constant applied
to every module leg. It is a deliberate bound — Global Search fans out to
five module searches plus an embedding call concurrently, and
`docs/architecture/GLOBAL_SEARCH_PERFORMANCE_BUDGETS.md` is written against
it. Raising it is a budget decision, not a bug fix.

### 2. How those 25 are selected and ordered

Each leg calls **that module's own search**, page 0, size 25. There is no
cross-module relevance model, so "the first 25" means something different
per module:

| Module | Ordering of its 25 |
|---|---|
| Award | `ORDER BY award_number` — effectively oldest award number first, not relevance |
| Negotiation | exact `negotiation_id`, then exact `document_number`, then `source_update_timestamp DESC NULLS LAST`, then `negotiation_id DESC` |
| Subaward | its own `findPage` ordering |
| Proposal | `findFamilies` ordering |
| IRB | its own `search_rank` CASE |

The merged list is then sorted into cross-domain tiers (exact identifier,
exact number, substring, fallback, and semantic last) with a **stable** sort,
so each module's internal order survives inside its tier. A semantic result
can never outrank a structured one.

**The consequence worth stating plainly:** because the cap is applied *before*
any cross-module ranking, a highly relevant record that sorts 26th within its
own module is never seen by the ranking at all.

### 3. `totalResults` — misleading, now fixed

`totalResults` was `deduplicated.size()` — the number of rows returned.

Evidence, same query, two endpoints:

```
/api/global-search?query=NSF    totalResults 104
                                 = 25 AWARD + 25 NEGOTIATION
                                   + 25 SUBAWARD + 29 PROPOSAL
/api/negotiations?query=NSF      totalElements 234
```

104 is four page sizes added together. The Negotiation module alone matched
234. For `zzzznotfound123` the API reported `totalResults: 5` while the page
correctly displayed "0 results found" — the API and the UI disagreeing about
the same search.

**Fixed in this branch:** `moduleTotals` reports per module the rows
returned, the records matched where the module already counts them, and
whether the cap was hit. The page now reads "Showing 50 of 1434 matching
records" with a per-type breakdown. `totalResults` keeps its value for
compatibility and is documented as a row count.

### 4. Negotiation 6447 / document 760067 — not missing

Reproduced, and the premise does not hold.

```
/api/global-search?query=WILSON+WONG   totalResults 76
                                        25 NEGOTIATION rows, ids 9896-12724
                                        6447 ABSENT
/api/negotiations?query=WILSON+WONG     totalElements 234, 10 pages
                                        page 0 = the SAME 25 ids
                                        6447 ABSENT from page 1 too
/api/negotiations?principalInvestigator=WILSON+WONG
                                        totalElements 234, same first 25
                                        6447 ABSENT from page 1 too
/api/global-search?query=760067         totalResults 1
                                        -> NEGOTIATION 6447, matchedField
                                           "Document Number"
```

So:

- 6447 **does** match "WILSON WONG". The record's associated Award carries
  `principalInvestigatorName = "WILSON WONG"`, and the Negotiation search's
  `WHERE` includes that column.
- It is one of **234** matches and sorts outside the first 25 under
  `source_update_timestamp DESC, negotiation_id DESC` — its id is 6447 while
  the first page is 9896–12724.
- The Negotiations module page returns **the same 25** and also does not show
  it on page 1. Global Search is not dropping it; both surfaces agree.
- Global Search finds it immediately when the query is the document number,
  because exact `document_number` is the second ordering key.

**"Appearing first in Negotiations search" is not reproducible** on this
build for the generic query or the PI filter. The most likely origin is a
search for the document number `760067` itself, which the ordering promotes
to first by design. Worth confirming with Lalitha which box was typed into,
because the two observations have different causes.

What *is* a real problem here: all 25 rows came back with
`matchedField: "Negotiation"` — the fallback — and a null negotiator. The
match came from the associated Award's PI, a column the Negotiation summary
does not expose, so the card cannot say why it matched. That is finding 5.

### 5. Award team-member matches do not explain the role — defect

The Award search matches far more columns than the result card carries.
`AwardArchiveRepository` searches, among others:

- `av.title`, `av.sponsor_code`, `av.sponsor_name`, `av.lead_unit_number`,
  `av.lead_unit_name`, `av.modification_number`, `av.account_number`
- **`ap2.full_name`** — *any* `award_person` row, not just the PI
- `ext2.grant_number`

`GlobalSearchService.awardMatchedField` then *guesses* which field matched by
testing the query against the five fields present in the response DTO
(award number, title, sponsor, lead unit, PI). When the real match was a
non-PI team member — or a grant number, modification number or sponsor code —
none of those contain the query, and the method falls through to the literal
`"Award"`, rendered as "Matched on: Award (100527-00001)". True, and useless.

The same applies to Negotiation (`"Negotiation"`) and the other modules. The
heuristic is honest; it simply cannot see the column that matched. Both
methods carry a comment saying as much: *"findNegotiations' own SQL doesn't
expose which column matched"*.

**Proposed fix (not implemented — needs a decision):** have each search
report the matched column, by adding a `CASE` expression to the search SQL
that names the first matching column. This removes the guess entirely and
lets an Award say "Matched on: Team member (JANE DOE, Co-Investigator)".
It touches four domains' SQL and their row mappers, so it is its own change.

### 6. Negotiation cards lead with the document number — business decision

For a Negotiation, `toGlobalSearchItem` sets the card's `identifier` to
`documentNumber`. The Negotiation ID appears only inside the route:

```json
{"module":"NEGOTIATION","recordId":6447,"identifier":"760067",
 "title":"Material Transfer Agreement","route":"/negotiations/6447"}
```

This is consistent with the rest of the pipeline — the embedding ETL also
uses `document_number` as the Negotiation's `business_number` — but it is
inconsistent with the detail page, which is addressed as
`/negotiations/6447` and titled by Negotiation ID. A tester reading a card
has no way to get from `760067` to `6447`.

**Proposed:** show both, e.g. `Negotiation 6447 · document 760067`. This is a
labelling decision for the business, not a correctness fix.

### 7. NSF awards appear as related rather than direct — the serious one

This is the finding with the largest practical effect, and the cause is not
what it looks like.

Award `100527-00001` is:

```json
{"identifier":"100527-00001",
 "title":"NATIONAL SCIENCE FOUNDATION GRADUATE RESEARCH FELLOWSHIP PROGRAM",
 "subtitle":"National Science Foundation"}
```

Neither its title nor its sponsor contains the literal string `NSF`. Every
lexical leg matches with `ILIKE '%' || :query || '%'`, so a search for "NSF"
**cannot** match this award. It can only arrive through the semantic leg,
labelled "related".

Meanwhile, the awards that *do* match "NSF" directly, in order:

| Award | Title | Why it matched |
|---|---|---|
| 100533-00001/2 | "PROBING THE PRINCIPLES OF DYNAMICS AND ENERGY TRA**NSF**ER IN PROTEINS" | `nsf` inside **TRANSFER** |
| 100543-00001 | "PIRE: … TRA**NSF**ORMING A BIODIVERSITY HOTSPOT…" | `nsf` inside **TRANSFORMING** |

and all 25 Negotiation "NSF" matches were `Material Tra**nsf**er Agreement`.

**The relevance is exactly inverted.** Unanchored substring matching fills
the direct results with records containing the letters n-s-f inside ordinary
English words, while the genuinely NSF-funded award is demoted to a
suggestion. The 25-per-type cap then guarantees the false positives occupy
the page.

**Proposed fix (not implemented — changes what matches, so it needs
sign-off):** for short, purely alphabetic queries, require a word boundary
rather than a bare substring — in PostgreSQL, `~*` with `\mNSF\M`, or a
trigram/full-text index. Long and identifier-shaped queries keep substring
behaviour, which is what makes partial award numbers work. An acronym
expansion table (NSF → National Science Foundation) would additionally make
the sponsor match directly rather than semantically; that is a data decision.

This should be measured before it is chosen: the same change would stop
"transfer" matching on a search for "nsf" **and** stop a search for "ransfe"
finding anything, which some users may rely on.

---

## TC-041 — semantic relevance

### Both nonsense searches reproduced

```
/api/global-search?query=zzzznotfound123
  totalResults 5 — all NEGOTIATION, all matchType RELATED
  4 of the 5 share one negotiator; all 5 status "Abandoned"

/api/global-search?query=aavvvlkkjhuipkknbhgkj
  totalResults 5 — 4 AWARD + 1 PROPOSAL, all matchType RELATED
```

The UI handles both correctly: "0 results found", "No direct matches for
…", and the five hidden behind a **"Show 5 related results"** button. They
are never presented as answers.

### There is no similarity threshold, and the system does return the top five regardless of relevance

Confirmed in the deployed source, read at commit `164b242` rather than at
`main`:

> `SemanticSearchRepository`: *"No similarity threshold - the threshold
> experiment found no single global cutoff works; a hard LIMIT (Top-5 max…)
> is the only cap."*

The pipeline is: embed the query → `ORDER BY embedding <=> query LIMIT 50` →
dedupe per business record → keep the first 5. Cosine distance is computed,
used for ordering, and then discarded. **Nothing anywhere rejects a result
for being too far away.**

That the two nonsense queries returned entirely different modules (five
Negotiations vs four Awards and a Proposal) rules out a single degenerate
cluster: the nearest neighbours of a meaningless vector are arbitrary, not
fixed.

### PR #28 — deployment verified, and it did not add a threshold

| Check | Result |
|---|---|
| PR #28 merge commit | `4f076c6`, "separate direct and related results instead of hiding them (QA TC-041)" |
| In the deployed API source (`164b242`, rev 87)? | **Yes** (`git merge-base --is-ancestor`) |
| In the deployed UI (job 186, `7450033`)? | **Yes** |
| Added a similarity threshold? | **No** |

Your caution was right. PR #28's API-side diff **removed** a rejection rule
rather than adding one. PR #27 had dropped semantic results whenever nothing
matched lexically:

```java
-        boolean anyLexicalMatch = !irbResults.isEmpty() || … ;
-        if (!anyLexicalMatch) {
-            semanticResults = List.of();
-        }
```

PR #28 reverted that, on the stated grounds that *"relevance is a
presentation problem, not a filtering one"* and that withholding results
"silently threw away the one kind of result that helps a searcher who used a
different word than the archive does". It replaced filtering with the
direct/related separation now visible in the UI.

So TC-041's **presentation** complaint is fixed and deployed. Its
**relevance** finding is untouched and remains open.

### Recommending a rejection mechanism — and why not a number yet

A threshold cannot be chosen defensibly today, because the evidence does not
exist: the distances are discarded, and `GlobalSearchItemResponse`
deliberately documents that no score or distance ever leaves the backend.
Picking 0.4 or 0.5 now would be exactly the arbitrary cutoff to avoid, and
the earlier experiment already found no single global value worked.

**Step 1 — make it measurable (done in this branch).** Each semantic search
now logs the shape of its distance distribution under `SEMANTIC_RELEVANCE`:
nearest, furthest kept, candidate median, and the nearest-to-median ratio,
with the request's correlation id. No identifier, no business number, no
query text.

**Step 2 — measure a labelled set.** Run, against DEV, queries already known
to behave well and badly. The good ones are recorded in PR #28's own removed
comment, with their lexical and semantic counts measured on 2026-10-05:

| Query | Then measured as |
|---|---|
| "pediatric asthma" | 7 lexical + 3 semantic |
| "quantum" | 87 + 4 |
| "climate" | 86 + 2 |
| "neurodegeneration" | 88 + 1 |
| "opioid" | 86 + 1 |
| "zzzznotfound123" | 0 lexical, 5 semantic Negotiations |
| "qzxwvnonsense987" | 0 lexical, 5 semantic Awards |
| "aavvvlkkjhuipkknbhgkj" | 0 lexical, 5 semantic (this investigation) |

Add synonym cases that *should* pass with no lexical match — the whole reason
PR #27 was rejected — such as "NSF" against National Science Foundation
awards, and a general term where the archive uses a specific one.

**Step 3 — prefer a relative rule over a global cutoff.** The hypothesis the
logging is designed to test: for a meaningful query the nearest neighbour is
much closer than the candidate median; for a meaningless one they are nearly
equal, because the query vector is roughly equidistant from everything. A
rule of the form *"keep semantic results only when the nearest is materially
closer than the candidate-window median"* needs no absolute constant, adapts
to each query, and does not depend on module-specific text length — which is
the most likely reason a single global cutoff failed, since Negotiation's
embedded text is far shorter and more generic than Award's:

```
module: NEGOTIATION | business number: <document_number>
  | title: <agreement type> | PI/person: <negotiator> | status: <status>
```

A Negotiation's "title" is only its agreement type, with no sponsor and no
lead unit, so thousands of rows are near-identical. That is a plausible
mechanism for nonsense queries landing on Negotiations, and it is testable
with the data from step 2.

Only after step 2 should a value — or the margin rule's parameter — be
chosen, and it should be chosen by separation on the labelled set, not by
inspection.

### What stays true regardless

The UI already prevents the user-visible harm: nothing is presented as an
answer when nothing matched. A rejection rule would additionally stop the API
reporting `totalResults: 5` for a nonsense query, and stop the "Show 5
related results" button appearing when the five are worthless.

---

## Changed in this branch

| Change | Status |
|---|---|
| `moduleTotals` on the Global Search response; page shows "Showing N of M" | implemented, tested |
| `SEMANTIC_RELEVANCE` distance-shape logging | implemented, tested |
| Word-boundary matching for short alphabetic queries (finding 7) | **proposed** — changes what matches |
| SQL-reported matched column (finding 5) | **proposed** — four domains' SQL |
| Negotiation card shows ID and document number (finding 6) | **proposed** — labelling decision |
| Semantic rejection rule (TC-041) | **proposed** — measure first, via step 2 |

No ranking, matching or cap behaviour was changed.
