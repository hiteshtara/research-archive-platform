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
search-timing cid=3f9a21c4 stage=MVC_REQUEST_TOTAL ms=2011 status=200 outcome=completed
```

Group by `cid` to reconstruct one request.

- `MVC_REQUEST_TOTAL` spans the DispatcherServlet's dispatch —
  parameter validation, orchestration and response writing. It is **not
  the whole request**: filters, Spring Security, CORS, TLS and the load
  balancer all sit outside it, and a failure before handler selection
  never reaches it at all. Named accordingly so no one reads it as
  end-to-end.
- A request refused at validation is still timed here — the service
  never ran, so only the interceptor can see it.
- `SERVICE_TOTAL` is the orchestration alone. **`MVC_REQUEST_TOTAL`
  minus `SERVICE_TOTAL` is the part the service cannot see**, and is
  worth reading on its own.
- `status=` and `outcome=` are **separate fields and neither is derived
  from the other**. A handled error arrives as `status=500
  outcome=completed`, because an `@ExceptionHandler` turned it into a
  response and the dispatch finished normally.
- `SEMANTIC_EMBED` **includes AWS SDK retries**, which it performs
  without telling the caller. A per-attempt timeout does not bound it.
- Two outcome vocabularies, deliberately: a **stage** records
  `outcome=ok` or `outcome=error` (did the work throw), while the
  **dispatch** records `outcome=completed` or `outcome=exception`
  alongside its own `status=`. The exception message is never logged in
  either.

Never logged: query text, titles, identifiers, embeddings, credentials.

## 4. Sampling plan

**Fixed query set.** Use these exact terms, so runs are comparable and
nobody is tempted to pick a flattering one. Five word terms, where the
semantic path runs, and two identifier-shaped ones, where
`LikelyIdentifierDetector` skips it:

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

### How to report it

**Per query first, pooled second.** A word query and an identifier query
are **different workloads**, not two samples of one — they differ in
lexical selectivity as well as in whether the semantic path runs. Pooling
them hides that. So report:

1. **Per query term**, per surface, per stage: n, p50, p95, min, max.
2. **Pooled by cohort** (word / identifier) only as context, labelled as
   such, never as the headline.

Different terms within a cohort are still different workloads; the
cohort figure says "roughly this range", not "this is the number".

**Percentile calculation — state it, because methods disagree.** Use the
**nearest-rank** method on the sorted ascending sample: the p-th
percentile is the value at index `ceil(p/100 × n)`, 1-based, with no
interpolation. For n=50, p95 is the 48th value; for n=20, the 19th.
Whatever tool is used, print the method beside the numbers so two runs
can be compared.

### Where attribution actually comes from

**Within-request stage timings, not cohort comparisons.** Stages sharing
one `cid` are the same request, same workload, same moment — the
difference between `SEMANTIC_TOTAL` and `SERVICE_TOTAL` in a single
request is a real share of that request. Comparing a word cohort against
an identifier cohort is **context only**, and cannot attribute cost,
because the two differ in more than the semantic path.

### What these numbers do NOT guarantee

- **Neither n=50 nor n=20 guarantees the percentile properties often
  assumed of them.** These are small samples from a shared, variable
  environment; the percentile is an estimate from this run, not a
  property of the system.
- At n=50 the p95 is the 48th of 50 observations — two slow outliers
  determine it. At n=20 it is the 19th of 20, which is in practice the
  second-slowest value seen.
- Report every percentile with its **n and its method**, and treat p95
  as indicative. Do not set a threshold from this run.
- A target should not be set from this run at all. Use it to find
  **where the time goes**; size a confirmation run once the stage is
  known.

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

---

## 6. Deployment plan — prepared, NOT applied

A new task-definition revision **cloned from the running one**, changing
exactly two things: the image (to the reviewed build) and the timing
flag. Nothing else moves, and Terraform is not touched.

### Exact diff from `research-archive-platform-dev-api:75`

```diff
  family: research-archive-platform-dev-api
  cpu: 512
  memory: 1024
- image: .../research-archive-platform-dev-api:20261006T123638Z-8292260
+ image: .../research-archive-platform-dev-api:<tag built from the reviewed #38 head>

  environment:
    APP_EXPLORER_ENABLED        = "true"
    APP_CORS_ALLOWED_ORIGINS    = "http://localhost:5173,https://main.d288p9gmoteftb.amplifyapp.com"
    SPRING_PROFILES_ACTIVE      = "aws"
    APP_SEARCH_SEMANTIC_ENABLED = "true"
    COGNITO_ISSUER_URI          = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_VJ4ekQ27c"
    ARCHIVE_DOCUMENTS_BUCKET    = "research-archive-platform-dev-documents-770203350335"
    COGNITO_CLIENT_ID           = "seqgmc8sccr22sq8lcafqjcpb"
+   APP_SEARCH_TIMING_ENABLED   = "true"

  secrets: POSTGRES_DB, POSTGRES_HOST, POSTGRES_PASSWORD, POSTGRES_PORT,
           POSTGRES_USER                                    (unchanged)
  logConfiguration: /ecs/research-archive-platform-dev-api  (unchanged)
```

Two changed lines. CPU, memory, secrets, log configuration, roles,
networking and every other environment variable are carried over
verbatim from rev 75.

### Procedure

1. Build and push the image from a clean worktree at the reviewed #38
   head, via `ops/deploy-api.sh`'s existing path. **Record the tag.**
2. Fetch rev 75 as JSON, strip the read-only fields
   (`taskDefinitionArn`, `revision`, `status`, `requiresAttributes`,
   `compatibilities`, `registeredAt`, `registeredBy`), apply the two
   changes above, and register it. Note the new revision number.
3. Update the service to that revision and wait for it to stabilise.
4. Confirm before measuring: the service reports the new revision, one
   task running, and a `search-timing` line appears in
   `/ecs/research-archive-platform-dev-api` on the first Global Search.
   **No line means the flag did not take effect — stop, do not
   measure.**
5. Run section 4's plan.

### Rollback

```
aws ecs update-service \
  --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:75 \
  --force-new-deployment
```

Rev 75 is left registered and untouched, so rollback is this single
command — it returns both the image and the flag to exactly what is
running today. Wait for the service to stabilise and confirm the
revision; `search-timing` lines stop appearing once it has.

**Rollback to rev 75 is for a DEPLOYMENT FAILURE** - the new revision
will not stabilise, the API is unhealthy, something is wrong with the
image. It is not the way to switch the instrumentation off, because it
also discards the new image.

### Routine shutdown after the run

Register ANOTHER revision cloned from the measurement one, **keeping the
new image** and setting the flag off:

```diff
  image: .../research-archive-platform-dev-api:<the measurement image>   (unchanged)
- APP_SEARCH_TIMING_ENABLED = "true"
+ APP_SEARCH_TIMING_ENABLED = "false"
```

or drop the variable entirely, which defaults to false. Update the
service to it and confirm `search-timing` lines stop.

This keeps the deployed code moving forward - the measurement image is
the current main, and reverting to rev 75 would silently roll the API
back past everything merged since. Removing the instrumentation code is
then a separate PR, and the revision after that carries an image that no
longer contains it.

### What this plan deliberately avoids

- **Terraform is not touched.** `terraform/environments/dev/terraform.tfvars`
  is untracked and pins `api_image_tag` to `20260810T161549Z-47aceeb`
  while the service runs `20261006T123638Z-8292260`, so an apply would
  roll the API back about two months. That hazard blocks the Terraform
  route for this change; it does not block preparing it. Anyone fixing
  that pin should do it as its own piece of work, not as a side effect
  of a measurement.
- **No change to desiredCount, cpu or memory.** Changing the shape of
  the task would change what is being measured.
