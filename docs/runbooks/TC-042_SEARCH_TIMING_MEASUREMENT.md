# TC-042 — measuring Global Search, one run

Temporary runbook for the instrumentation added in PR #38. It exists to
turn one question into evidence: **where do Global Search's ~11 seconds
go?** Delete it, and the instrumentation, once a target is agreed and
met.

## What is already known, and what is not

Browser timings against dev on API rev 75 (3 samples each, sequential):

| Surface | Median |
|---|---|
| Negotiations | ~376 ms |
| Proposals | ~498 ms |
| Subawards | ~1.43 s |
| Awards | ~1.91 s |
| Historical Awards | ~6.24 s |
| **Global Search** | **~10.8 s** |

The semantic leg is the **leading suspect, not an attribution.** The
comparison that pointed at it — a word query against an
identifier-shaped one — changes the lexical workload at the same time as
it skips the semantic path, so the two effects are confounded. Three
samples establish no p95, and say nothing about whether TC-041 changed
performance in either direction. This run replaces that guess.

---

## 1. Enabling it

The property is `app.search.timing.enabled`, bound from
`APP_SEARCH_TIMING_ENABLED`, default **false**.

### The gap you will hit first

`APP_SEARCH_TIMING_ENABLED` **is not in the deployed task definition**.
Today rev 75 carries only:

```
APP_EXPLORER_ENABLED, APP_CORS_ALLOWED_ORIGINS, SPRING_PROFILES_ACTIVE,
APP_SEARCH_SEMANTIC_ENABLED, COGNITO_ISSUER_URI,
ARCHIVE_DOCUMENTS_BUCKET, COGNITO_CLIENT_ID
```

So deploying PR #38 alone changes nothing and **measures nothing**. The
variable has to be added to the task definition as well.

### Option A — out of band (recommended for this run)

Register a new task-definition revision carrying the extra variable and
point the service at it, the same way `ops/deploy-api.sh` already
deploys images. Nothing in Terraform changes, and reverting is one more
revision.

Recommended because this is scaffolding that will be removed in days,
and because of the hazard in Option B.

### Option B — Terraform

```hcl
# terraform/environments/dev/terraform.tfvars
additional_api_environment_variables = {
  APP_SEARCH_SEMANTIC_ENABLED = "true"
  APP_EXPLORER_ENABLED        = "true"
  APP_SEARCH_TIMING_ENABLED   = "true"   # TC-042, temporary
}
```

**Read this before applying.** That file is **untracked**, and its
`api_image_tag` is pinned to `20260810T161549Z-47aceeb`, while the
service actually runs `20261006T123638Z-8292260`. A `terraform apply`
would **roll the API back roughly two months**. If you use Option B,
re-pin `api_image_tag` to the running image *first*, and confirm it in
the plan output.

## 2. Disabling it — three routes, only one of which stops the work

| Route | Stops logging | Stops the timing work |
|---|---|---|
| `app.search.timing.enabled=false` (or drop the variable) | yes | **yes** — no timestamps are taken |
| Raise logger `edu.bu.archive.search.timing` above INFO | yes | **no** — timestamps still taken, durations still computed, then discarded |
| Remove `SearchTimingLog`, `SearchRequestTimingInterceptor` and their call sites | yes | yes |

Use the flag to switch it off. Logger suppression is for quietening a
noisy log during a run, not for disabling the instrumentation — it
leaves the overhead in place.

**End state:** route 3. This is temporary scaffolding.

## 3. What a run produces

One line per stage, under logger `edu.bu.archive.search.timing`, in
CloudWatch log group `/ecs/research-archive-platform-dev-api`:

```
search-timing cid=3f9a21c4 stage=SEMANTIC_EMBED ms=842 outcome=ok
search-timing cid=3f9a21c4 stage=SEMANTIC_VECTOR_QUERY ms=120 count=50 outcome=ok
search-timing cid=3f9a21c4 stage=SEMANTIC_ENRICH_AWARD ms=61 count=4 outcome=ok
search-timing cid=3f9a21c4 stage=SEMANTIC_TOTAL ms=1104 count=5 outcome=ok
search-timing cid=3f9a21c4 stage=LEG_AWARD ms=1890 count=25 outcome=ok
search-timing cid=3f9a21c4 stage=SERVICE_TOTAL ms=1996 count=30 outcome=ok
search-timing cid=3f9a21c4 stage=REQUEST_TOTAL ms=2011 outcome=ok
```

Group by `cid` to reconstruct one request.

- `REQUEST_TOTAL` spans **validation, orchestration and response
  writing** (a servlet interceptor), so a stage can be read as a share
  of it. A request refused at validation is still timed — the service
  never ran, so only the interceptor can see it.
- `SERVICE_TOTAL` is the orchestration alone. **`REQUEST_TOTAL` minus
  `SERVICE_TOTAL` is the part the service cannot see**, and is worth
  reading on its own.
- `SEMANTIC_EMBED` **includes AWS SDK retries**, which it performs
  without telling the caller. A per-attempt timeout does not bound it.
- `outcome=error` marks a failed stage. The exception message is never
  logged.

Never logged: query text, titles, identifiers, embeddings, credentials.

## 4. Sampling plan

**Fixed query set.** Use these exact terms, so runs are comparable and
nobody is tempted to pick a flattering one. Two word queries (semantic
runs) and one identifier-shaped (semantic is skipped by
`LikelyIdentifierDetector`), which is what separates the two paths on
the *same* lexical surface:

| Class | Terms |
|---|---|
| Word | `autism`, `cancer`, `neuroscience`, `genomics`, `imaging` |
| Identifier | `105698`, `100004` |

**Surfaces.** Global Search, Awards, Historical Awards, Proposals,
Negotiations, Subawards. The single-module ones are the control: if they
move between runs, the environment moved, not the code.

**Warm-up, discarded.** 5 requests per surface before recording
anything, and they are **thrown away, not averaged in**. The first
request after a deploy pays JIT, connection-pool and Bedrock-client
setup that no user pays twice. Record that the warm-up happened and how
many.

**Concurrency.** Run **sequentially, concurrency = 1**, and record that
figure with the results. One slow request behind another is a different
measurement from one slow request alone, and the service runs a single
task (`desiredCount: 1`), so concurrent load would measure queueing
rather than the stages. If a concurrent run is wanted later it is a
separate, separately labelled experiment.

**Observations.** 10 repetitions per term per surface:

- Word: 5 terms × 10 = **50 per surface**
- Identifier: 2 terms × 10 = **20 per surface**

**Report** per surface and per stage: **n, p50, p95, min, max**, with n
always printed beside the percentile.

### What these numbers can and cannot support

- **p50 at n=50 is solid.**
- **p95 at n=50 is indicative, not stable.** At n=50 the 95th percentile
  sits between the 2nd and 3rd slowest observations, so one slow
  outlier moves it materially. Report it as an estimate with its n, and
  do not quote it as a threshold without a larger run.
- **p95 at n=20** (the identifier class) is close to "the slowest one
  seen". Treat it as a maximum observed, not a percentile.
- A target should not be set from this run alone. Use it to find **where
  the time goes**; size a confirmation run once the stage is known.

Record alongside the numbers: date and time, API task-definition
revision and image tag, UI build, whether anything else was using the
environment, and the warm-up count. Without those the run is not
repeatable.

## 5. After the run

1. Attribute the time to stages from the `cid`-grouped lines.
2. Choose a fix **from the measured stages**, not from this document's
   suspicions.
3. Re-measure the same way, same query set, same concurrency.
4. Agree a target with the evidence in hand.
5. **Remove the instrumentation** — `SearchTimingLog`,
   `SearchRequestTimingInterceptor`, their registration, the config
   block, and this runbook.
