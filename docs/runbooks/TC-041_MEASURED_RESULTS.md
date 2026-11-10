# TC-041 — measured results

Measured against DEV on **2026-11-10**, read-only, as `archive_api` over the
SSM tunnel, with 15 Bedrock embedding calls. `archive.search_embedding` holds
**26,425** rows.

Connection was opened read-only (bound at connect) and verified with
`SHOW default_transaction_read_only` before any query. No data was written, no
permission was changed, and the tunnel task was stopped afterwards.

**TC-041 is still open.** This records what the numbers say, and what they
rule out. The rule they support is a behaviour change to search and is not
implemented here.

## The headline: neither distance strategy separates the set

| Strategy | Relevant range | Nonsense range | Margin | Verdict |
|---|---|---|---|---|
| **Nearest distance** | 0.5196 – **0.7884** | **0.7883** – 0.8201 | **−0.0001** | **overlapping** |
| **Relative margin** (nearest ÷ median) | 0.6657 – **0.9751** | **0.9331** – 0.9887 | **−0.0420** | **overlapping** |

The worst relevant query by absolute distance is `heart attack` at 0.7884.
The best nonsense query is `xkcdqq` at 0.7883. They overlap — by one
ten-thousandth.

### The relative margin is refuted, and it was my recommendation

I proposed the relative margin before measuring, on the reasoning that a
meaningful query should have a nearest neighbour much closer than its median
while a meaningless one would not. **The data says otherwise.** `NIH` — plainly
relevant, 4,174 corpus rows mention it — has a ratio of 0.9751, *worse* than
every one of the four nonsense strings. Its neighbourhood is uniformly close,
so its best match does not stand out, and the ratio reads it as noise.

The relative margin overlaps by **420× more** than the absolute cutoff. It is
the worse of the two, not the better. Recorded plainly because the earlier
plan recommended it.

### The absolute cutoff "works" only by a hair

The best achievable absolute cutoff is `<= 0.7885`: **14/15 correct, 0 useful
results withheld, 1 nonsense kept** (`xkcdqq`).

That is a good score and a bad rule. It sits **0.0001** from withholding
`heart attack`, and the distances describe the corpus — they move when the
corpus is reloaded. Tuning a constant into a one-ten-thousandth window is
fitting to this snapshot, not choosing a rule.

## All four strategies, measured

| Strategy | Correct | Noise kept | Useful withheld |
|---|---|---|---|
| Absolute `<= 0.7885` (best possible) | 14/15 | 1 | **0** |
| Relative margin (best possible) | 11/15 | 4 | 0 |
| Hybrid — PR #27's rule | 9/15 | 0 | **6** |
| Query quality, shape only | 14/15 | 1 | **0** |
| **Query quality, corpus vocabulary** | **15/15** | **0** | **0** |

Hybrid withholds six useful queries, measured — which is what PR #28 reverted
it for, now as a number rather than an argument.

## What the data does support: a corpus-vocabulary gate

Asked of the embedded corpus itself — *does any token of this query appear as
a word in what we embedded?*

| Query token | Rows containing it as a word |
|---|---|
| `nih` | 4,174 |
| `heart` | 749 |
| `cancer` | 650 |
| `gene` | 177 |
| `climate` | 82 |
| `screening` | 75 |
| `quantum` | 72 |
| `pediatric` | 57 |
| `asthma` | 45 |
| **`nsf`** | **37** |
| `opioid` | 21 |
| `neurodegeneration` | 11 |
| `editing` | 5 |
| `attack` | 2 |
| `zzzznotfound123` | **0** |
| `aavvvlkkjhuipkknbhgkj` | **0** |
| `qzxwvnonsense987` | **0** |
| `xkcdqq` | **0** |

**Clean separation.** Every relevant query has a token in the corpus; every
nonsense string has none. 15/15, with no cutoff, no constant to tune, and no
embedding call for a query that cannot be helped.

It also answers the case that made TC-040 worth fixing: `nsf` appears in 37
embedded rows, so the gate keeps `NSF` — the query whose only route to Award
100527-00001 was the semantic leg.

### Recommendation

**A corpus-vocabulary query-quality gate, applied before embedding. No
distance threshold.**

Reasons, in order:

1. It is the only strategy that separates the measured set completely.
2. It withholds nothing useful — the error that matters most, since a result
   a searcher never saw is one they never learn about.
3. It needs no constant, so corpus drift cannot mis-tune it. The two distance
   strategies both depend on absolute values that move with the data.
4. It is the only one that saves the Bedrock call, so it is a latency and cost
   improvement as well as a relevance one.

### What it would cost, stated honestly

A genuinely novel term — a disease name the archive has never recorded —
would be rejected. That is a real limitation, and it is the *same* limitation
either way: if no embedded row contains the word, semantic search has nothing
relevant to return. The gate declines to spend a model call discovering that.

It needs the vocabulary available at query time: either a cached token set
built from `archive.search_embedding.source_text`, or an `EXISTS` against it.
Both are cheap; which one is a design decision, not a measurement.

## Not done here

Implementing the gate changes what the API returns and is its own change,
with its own tests and its own review. **TC-041 stays open until that ships
and is verified** — the measurement settles which rule to build, not that it
is built.

## Raw evidence

Distances are preserved at
`~/research-archive-evidence/semantic-relevance.json` — **outside the
repository**, 15 queries × 50 candidate distances. It contains no module, no
record id, no business number and no title: the measurement query selects the
distance and nothing else.
