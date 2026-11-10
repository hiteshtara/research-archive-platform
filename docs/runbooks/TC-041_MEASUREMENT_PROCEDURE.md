# TC-041 — the controlled DEV measurement

How to produce the similarity-score evidence, read-only, and what it costs.

**Not yet run.** This is the procedure, prepared so that running it is a
decision rather than an improvisation. TC-041 stays open until the numbers
exist, and no relevance cutoff should be chosen before then.

The strategy comparison and the labelled query set are in
[`TC-041_SEMANTIC_RELEVANCE_PLAN.md`](TC-041_SEMANTIC_RELEVANCE_PLAN.md).

## Permissions required: none new

This was the surprise, and it is worth stating first. The measurement needs
no grant that does not already exist.

| Needs | Already granted by | Evidence |
|---|---|---|
| `SELECT` on `archive.search_embedding` | the `archive_api` role | `GRANT SELECT ON ALL TABLES IN SCHEMA archive TO archive_api` — see `ARCHIVE_API_DATABASE_ROLE.md`. `search_embedding` is in `archive`, so it is already covered |
| `bedrock:InvokeModel` on the embedding model | the ETL task role | `terraform/modules/ecs/main.tf`, `task_bedrock` — scoped to exactly `arn:aws:bedrock:<region>::foundation-model/amazon.titan-embed-text-v2:0` |
| Network path to Bedrock | the bedrock-runtime VPC interface endpoint | already present, VPC-wide |
| Postgres credentials | `POSTGRES_SECRET_ID` on the loader task | the same secret every loader resolves at runtime |

So the approval being asked for is **permission to run it**, not permission to
widen anyone's access. Nothing in `terraform/`, no IAM policy and no `GRANT`
has to change.

> If a reviewer would rather it ran as a dedicated least-privilege role, that
> is a reasonable ask and it is a bigger change: a new role, a new secret, and
> a new task definition. It would reduce the blast radius from "can read the
> archive schema" to "can read one table". Worth deciding deliberately rather
> than assuming either way.

## It cannot write, and that is checked

| Guarantee | How |
|---|---|
| Read-only connection | `default_transaction_read_only=on` passed in `connect_args`, so it binds **at connect**. Setting it on a connection already in use silently does nothing — that has caught this project before |
| Verified, not assumed | `SHOW default_transaction_read_only` is run and the script **exits** unless it is `on` |
| No write statements | asserted against the source: every SQL literal is parsed out and checked for `INSERT`/`UPDATE`/`DELETE`/`TRUNCATE`/`DROP`/`ALTER`/`CREATE`/`GRANT`/`REVOKE`/`COPY` |
| One table only | the parsed SQL's `FROM` clauses must be exactly `{archive.search_embedding}` |
| No record content can leave | the query selects **only** the distance — not module, not record id, not business number. `QueryMeasurement` has nowhere to put a title, and a test asserts its fields are exactly `query`, `candidate_distances`, `lexical_hits` |
| Production refused | `prod` / `production` / `prd` exit immediately |
| Not a default | refuses to run without `--confirm-read-only` |

Eleven tests in `test_semantic_relevance_harness_is_read_only.py`, plus one
asserting there is SQL to check at all — otherwise the rest would pass by
finding nothing.

## Cost

15 embedding calls. The two identifier-shaped queries are skipped, because
the API skips the semantic leg for those and embedding one would measure a
code path nobody runs.

```
15 queries × roughly 3–10 tokens each  ≈  100–200 input tokens total
```

Titan Text Embeddings V2 is billed per million input tokens, so at any
plausible published rate this is a **fraction of one cent** — the arithmetic
is shown rather than a figure asserted, so the current unit price can be
substituted without re-deriving it. Confirm the rate against Bedrock's
pricing page at the time; nothing about the conclusion is sensitive to it.

Each re-run costs the same. There is no stored state, so re-running after a
corpus reload is cheap and is the right thing to do — the distances describe
the corpus, and the corpus changes.

## Execution

Two routes. Both are read-only; the difference is only where the output
lands.

### Route A — ECS one-off task (the established path)

Consistent with every other database job on this project: cluster
`research-archive-platform-dev-etl`, task family
`research-archive-platform-dev-loader`, which already carries the Bedrock
permission and the Postgres secret.

```
--environment=dev --confirm-read-only --output=/tmp/semantic-relevance.json
```

The JSON is written inside the container and lost when it exits, which is
fine: the harness **prints** each query's nearest, median and ratio to
stdout, and that reaches CloudWatch at
`/ecs/research-archive-platform-dev-loader`. Those three numbers per query
are what the comparison needs. No identifier and no query content is logged
beyond the query text itself, which is a labelled constant in the repository
rather than anyone's real search.

### Route B — from the Mac over the SSM tunnel

Use when the JSON file itself is wanted for the comparison step. Keep the
output **outside the repository** — it is evidence, not source.

```
uv run python measure_semantic_relevance.py \
    --environment=dev \
    --confirm-read-only \
    --output=~/research-archive-evidence/semantic-relevance.json
```

Then:

```
uv run python compare_semantic_relevance.py \
    --input=~/research-archive-evidence/semantic-relevance.json
```

## What to do with the result

1. Record the table the comparison prints — the sweeps, and the
   false-positive / false-negative counts per strategy.
2. **Check the pessimistic case first.** If the best absolute cutoff still
   withholds a useful result, the absolute strategy is wrong rather than
   badly tuned, and the relative margin is the one to pursue.
3. Only then choose a rule, and write down which errors were accepted.
4. Add the chosen rule's regression tests, and re-run the labelled set.
5. **Then** TC-041 can be closed — and not before.

## Known gap in what will be measured

The harness records distances but **not lexical hit counts**, which the
hybrid strategy needs. Three synonym queries currently assume zero, and for
`NSF` that assumption is demonstrably wrong: "NSF" matched 104 rows lexically
before the TC-040 fix, almost all of them through "TRANSFER".

PR #124's `moduleTotals` reports exactly the number required, so the fix is to
capture it in the same run by calling Global Search for each labelled query
alongside the embedding. Until that is done, **treat every hybrid figure as
provisional** — the other three strategies are unaffected.
