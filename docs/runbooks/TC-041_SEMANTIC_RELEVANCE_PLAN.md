# TC-041 — semantic relevance: measurement plan and recommendation

**TC-041 stays open.** Nothing here closes it. This records what the behaviour
actually is, what has to be measured before a rule can be chosen, the tooling
to measure it, and a recommendation that is conditional on those numbers.

## What the behaviour is

Confirmed in the deployed source at commit `164b242` (API revision 87), and
reproduced on DEV on 2026-11-10:

- There is **no similarity threshold** anywhere in the pipeline. The query is
  embedded, pgvector returns the 50 nearest rows, duplicates per business
  record are collapsed, and the first 5 survive. Cosine distance is computed,
  used for ordering, and discarded.
- So yes — the system returns its top five nearest vectors **regardless of
  relevance**.
- `zzzznotfound123` returns five Negotiations; `aavvvlkkjhuipkknbhgkj`
  returns four Awards and a Proposal. Different modules, so this is not one
  degenerate cluster.
- PR #28 is deployed in both the API and the UI and **did not add a
  threshold** — its API diff removed one. PR #27 had dropped semantic results
  when nothing matched lexically; #28 reverted that, on the grounds that
  relevance is a presentation problem.

The UI mitigation works: "0 results found", "No direct matches", and the five
behind a "Show 5 related results" button. Nothing is presented as an answer.
What remains is that the API reports `totalResults: 5` for a nonsense query,
and that the button appears at all when the five are worthless.

## Why a cutoff cannot be chosen yet

The distances are not observable. They are computed inside the API, used for
ordering and thrown away, and `GlobalSearchItemResponse` documents
deliberately that no score or distance ever leaves the backend — a reasonable
rule for a client-facing DTO.

A prior experiment already found that no single global cutoff worked. Picking
0.4 or 0.5 now would reproduce that mistake with fewer examples.

There is also a plausible mechanism for *why* a global cutoff failed, which
can be tested: the embedded text differs sharply in richness per module.

```
module: NEGOTIATION | business number: <document_number>
  | title: <agreement type> | PI/person: <negotiator> | status: <status>
```

A Negotiation's "title" is only its agreement type, with no sponsor and no
lead unit, so thousands of rows are near-identical — while an Award carries a
real title, a sponsor and a unit. One distance scale across both is unlikely
to behave.

## The labelled query set

17 queries, in `archive_etl/semantic_relevance/labelled_queries.py`, each with
a recorded reason. 11 should return semantic results, 6 should not.

| Kind | Examples | Why they are in the set |
|---|---|---|
| Nonsense | `zzzznotfound123`, `aavvvlkkjhuipkknbhgkj`, `qzxwvnonsense987`, `xkcdqq` | Lalitha's two, plus one from PR #28's measurements, plus a SHORT one - a rule tuned on long strings may not hold for a short one |
| Abbreviation | `NSF`, `NIH` | NSF is the TC-040 case: before the lexical fix, the semantic leg was the **only** route to Award 100527-00001. A rule that rejects short queries would have removed the single useful answer |
| Expanded name | `National Science Foundation` | Measured beside `NSF`, it shows whether the distribution depends on query **length** rather than relevance |
| Scientific terms | `pediatric asthma`, `quantum`, `climate`, `neurodegeneration`, `opioid` | Real subject matter with lexical/semantic counts already measured on 2026-10-05 |
| Synonyms | `heart attack`, `gene editing`, `cancer screening in underserved populations` | The case PR #27 broke and #28 restored: a searcher using a different word from the archive |
| Identifier-shaped | `100527-00001`, `760067` | Semantic search is skipped for these today; included so a change to that detector shows up as a measurable regression |

## The four strategies, and what each costs

Implemented in `archive_etl/semantic_relevance/evaluate.py` so they are
compared on one body of evidence rather than argued about.

| Strategy | Rule | Known or expected cost |
|---|---|---|
| **Absolute** | reject when nearest distance > X | The rule that already failed. Cannot adapt to per-module text richness |
| **Relative margin** | reject when nearest is not much closer than the candidate median | Needs no constant, so corpus drift cannot mis-tune it. Keeps a genuinely useful query whose whole neighbourhood is distant - a rare subject - which an absolute cutoff rejects |
| **Hybrid** (PR #27) | keep semantic only when something matched lexically | **Withholds the synonym cases.** This is why it was reverted |
| **Query quality** | reject on the query's own shape, before embedding | Cheapest, and the only one that saves the Bedrock call. Must let short acronyms through, or it rejects `NSF` |

**False negatives are weighted above false positives**, and `evaluate.best`
encodes that: noise a searcher can ignore, but a useful result they never saw,
they never learn about. A strategy that withholds a useful answer at its
**best** setting is the wrong strategy, not a badly tuned one.

## Recommendation, conditional on the measurement

1. **Measure first.** Run `measure_semantic_relevance.py` against DEV. It
   embeds each labelled query with the same model the API uses, asks for the
   same 50-row window, and records the distances.
2. **Prefer the relative margin** if the measurement supports it. The
   hypothesis it tests: a meaningful query has a nearest neighbour much closer
   than its candidate median; a meaningless one has them nearly equal, because
   the query vector is nowhere near anything. That needs no absolute constant
   and is indifferent to per-module text richness - the most likely reason a
   global cutoff failed.
3. **Add query-quality rejection regardless**, if it holds up. It is the only
   strategy that avoids the Bedrock call entirely for a string like
   `aavvvlkkjhuipkknbhgkj`, which is a latency and cost saving as well as a
   relevance one. It must consult a corpus vocabulary so real abbreviations
   survive.
4. **Do not adopt the hybrid rule.** It is already known to withhold the
   synonym case, and it was already reverted for exactly that.
5. **Whatever is chosen, keep the presentation separation.** The direct/related
   split is doing real work and should survive any filtering change.

## What the tooling does and does not record

`measure_semantic_relevance.py`:

- reads `archive.search_embedding` **only**, one `SELECT` per query, no
  `INSERT`/`UPDATE`/`DELETE`/DDL and no migrations;
- opens the connection **read-only, bound at connect**, and verifies
  `default_transaction_read_only` before any query - a guard that is set and
  never checked has failed silently on this project before;
- refuses `prod`/`production`/`prd` outright, and refuses to run at all
  without `--confirm-read-only`;
- selects **no identifying column** - not the module, not the record id, not
  the business number. Only the distance. `QueryMeasurement` has nowhere to
  put a title or an identifier, and a test asserts that its fields are exactly
  `query`, `candidate_distances` and `lexical_hits`;
- writes to a path given on the command line, intended to be **outside the
  repository**.

So the raw score evidence is preserved, and record content cannot travel with
it even by accident.

## Known gap in the measurement

The harness records distances but **not lexical hit counts**, and the hybrid
strategy needs them. Three of the synonym queries currently assume zero
lexical hits, which is an assumption and not a measurement - and for `NSF` it
is demonstrably wrong, because "NSF" matched 104 rows lexically before the
TC-040 fix, almost all of them through "TRANSFER".

The lexical counts should be captured in the same run, from Global Search's
own response. PR #124 added `moduleTotals`, which reports per module how many
records matched - exactly the number needed. Until that is wired in, treat
every hybrid figure as provisional.

## Approval needed

Running the harness needs:

1. **Read access to `archive.search_embedding` on DEV RDS** - a read-only
   SELECT, via the established ECS/tunnel route.
2. **`bedrock:InvokeModel`** for ~15 embedding calls (the identifier-shaped
   queries are skipped because the API skips them too).

Neither has been requested or performed. Until both are approved and the
numbers are in, TC-041 remains unresolved, and no cutoff should be committed.
