# DEF-5 — DEV deployment verification (2026-11-12)

DEV only. Production untouched. No index, migration, or security
configuration was modified.

## 1. What was deployed

| Item | Value |
|---|---|
| Merge commit | `543ab22de9dd3bf65ba5e0bc47ca2664aa636330` |
| PR | #141, merged into `main` |
| PR head CI tested | `08ad054a116819bc4614655a40c95db68aefc196` |
| Image digest (linux/amd64) | `sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981` |
| ECR repository | `770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api` |
| New task definition | `research-archive-platform-dev-api:94` |
| Previous (rollback target) | `research-archive-platform-dev-api:93`, image `sha256:f08672c4f559…` |

### Build provenance

- Built in a **clean detached worktree** at exactly `543ab22`
  (`git status --porcelain` empty).
- AWS account verified **before** any mutating call:
  `770203350335` via `AWS_PROFILE=bu-nprd`. The shell's *default*
  credentials resolved to a **personal** account (`589744711110`) —
  caught and never used. This is the documented wrong-account hazard;
  every call was made with the explicit profile.
- Image built `--platform linux/amd64`.
- The jar **inside the image** was verified to contain the fix
  (`searchFilteredLight`, `moduleFacetsFilteredLight`,
  `requireLightEligible`, `documents_light`, `FILTER_WHERE_LIGHT`) —
  not just the host-built jar, because the Dockerfile builds its own
  via `COPY --from=build`.
- buildx produced an OCI **image index** with an attestation manifest
  (`os=unknown`). The task definition pins the **linux/amd64 child
  manifest**, not the index, so Fargate resolves no ambiguity.
- The task definition was derived from revision 93 and changed **only**
  the image — asserted programmatically (`onlyImageChanged: true`) by
  comparing the full normalized task definition.

### Condition 4 — unchanged configuration

Revision 94's container environment is byte-identical to 93's:

```
APP_EXPLORER_ENABLED=true   SPRING_PROFILES_ACTIVE=aws
APP_SEARCH_SEMANTIC_ENABLED=true   APP_SEARCH_TIMING_ENABLED=false
COGNITO_ISSUER_URI=…  COGNITO_CLIENT_ID=…  APP_CORS_ALLOWED_ORIGINS=…
ARCHIVE_DOCUMENTS_BUCKET=…
```

No authorization or auditing variable was added, removed or changed;
`taskCpu` 512 / `taskMemory` 1024, both IAM roles and all five secrets
are identical. No migration was run, so V089 remains unapplied by this
work and grant 2 was never touched (no database write was made at any
point).

## 2. Deployment health

| Check | Result |
|---|---|
| ECS rollout | `COMPLETED`, single PRIMARY deployment, revision 94 |
| Service | desired 1, running 1, pending 0, "has reached a steady state" |
| Old task | drained and stopped cleanly |
| Running task image digest | `sha256:1d440f251c…` — matches what was registered |
| ALB target group `rap-dev-api-tg` | target `10.30.2.192:8080` **healthy** |
| ALB health-check path | `/actuator/health`, matcher HTTP 200 |
| Authentication | request **without** a token → **401** (still enforced) |

`/actuator/health` could not be fetched from the browser (CORS — actuator
is not in the CORS allowlist). The ALB's own health check against that
exact path reports healthy, which is the authoritative signal; the
browser result is a CORS artifact, not an application finding.

### Normal archive access, authenticated (all HTTP 200 + JSON + verified content)

| Endpoint | Evidence |
|---|---|
| Award detail | `awardNumber = 212137-00001` |
| Dashboard | `awards=40926`, `subawards=3511` (business grain preserved) |
| Award search | 1 row for `212137-00001` |
| Negotiations list | real rows (negotiationId 12789, document 1153041) |
| Subawards list | real rows (subawardId 95015, code 4092) |
| Proposals search | real rows (proposal 01091593) |

## 3. Acceptance gates — timings

Both halves measured **in the same authenticated session, back to back**,
because run-to-run variance on this 0.5 vCPU host is large. Every sample
required HTTP 200 + `application/json` + a per-row content check.
**Zero samples discarded in either half.**

| Gate | Request | Before (n) | After (n) | Threshold | Verdict | Change |
|---|---|---:|---:|---:|:--:|---|
| 9 | `query=NSF` all modules | 38,514 (5) | **11,868** (5) | ≤ 15,000 | **PASS** | **3.25× faster** |
| 10 | `query=NSF&module=AWARD` | 11,209 (5) | **6,174** (5) | ≤ 9,000 | **PASS** | 1.82× faster |
| 11 | `query=NSF&module=PROPOSAL` | 1,930 (5) | **1,144** (5) | ≤ 2,200 | **PASS** | 1.69× faster |
| 12 | unfiltered default page | 5,988 (5) | **6,062** (5) | ≤ 8,000 | **PASS** | 1% slower (noise) |
| 13 | `personName=SMITH` (heavy control) | 38,878 (3) | **24,765** (3) | ≤ 48,598 | **PASS** | 1.57× faster |
| 14 | award detail baseline | 56 (5) | **93** (5) | ≤ 500 | **PASS** | within noise |

Raw samples after deployment:

- gate 9: 13,739 / 12,777 / 11,349 / 10,578 / 11,868
- gate 10: 6,310 / 6,174 / 6,242 / 5,724 / 5,885
- gate 11: 1,663 / 1,301 / 1,144 / 1,069 / 1,021
- gate 12: 7,638 / 5,552 / 6,540 / 6,062 / 5,483
- gate 13: 27,757 / 24,372 / 24,765
- gate 14: 207 / 55 / 93 / 162 / 56

### Gate 13 improved, and the arithmetic explains why

Gate 13 was the **no-regression control**: a `personName` filter stays on
the heavy CTE, so it was expected to be unchanged. It got **1.57×
faster**, which is worth explaining rather than banking.

Change (a) removed the separate `COUNT(*)` from **every** filtered
request, heavy ones included — three evaluations of the union become
two. That predicts a ⅓ reduction: 38,878 × ⅔ = **25,919 ms**, against a
measured **24,765 ms**. The two agree to within 4%.

So the heavy path is faster for a reason that was designed, and the
light-path routing is not what moved it. Gate 14's "66% slower" median
(56 → 93 ms) is noise: both halves span 51–207 ms and both sit far under
the 500 ms threshold.

## 4. Acceptance gates — correctness (15–18)

Identity key per row:
`module | documentNumber | businessRecordNumber | versionOrSequence | targetRoute`.
Digest = first 32 hex of SHA-256 over the newline-joined keys.

**A stability control was run first:** all seven digests were
re-captured from the **unchanged** revision 93 and reproduced the
earlier recorded values exactly. So the comparator was proven stable
before being used to judge a change — a digest difference afterwards
would be real signal, not noise.

| Gate | Subject | sorted | ordered | totalElements | moduleFacets |
|---|---|:--:|:--:|:--:|:--:|
| 15/16/18 | `query=NSF` page 0 ×100 | ✅ | ✅ | ✅ 3,382 | ✅ |
| 16 | `query=NSF&module=SUBAWARD`, **all 287 rows** | ✅ | — | ✅ 287 | ✅ |
| 15/18 | unfiltered page 0 ×100 | ✅ | ✅ | ✅ 491,685 | ✅ |
| 17 | `personName=SMITH` page 0 ×100 | ✅ | ✅ | ✅ 1,040 | ✅ |

**14 of 14 checks PASS.** Digests unchanged:

```
query=NSF        sorted 8b21c2799ca969abdaf166b6e3bfefa0
                 ordered 9700e4c4924a4974187084b4beeac754
subaward (287)   sorted df2c20ac5a4851f8d7e0f2a5f7b1d2ce
unfiltered       sorted 524857d9be9f0643224b0c85769371dd
                 ordered cdedee3c27032483665eb5da1343bcac
personName=SMITH sorted 85bd5c31faf4235be1b062883ef57bfe
                 ordered 0782bd9224c20742ea42f84b40bfaa60
```

The **ordered** digests match too, not only the sorted ones — so row
order is identical as well as row membership. The §6.2 tie-breaker
caveat remains theoretically correct but is not triggered by any of
these result sets (zero duplicate `(module, documentNumber)` pairs in
all four, zero duplicate rows across the full Subaward multiset).

Rollback was therefore **not** triggered: no digest differed, no
authorization behaviour changed, no required gate failed.

## 5. Global Search — unchanged, as predicted

PR #141 touches no Global Search code.

| Row | Before | After | Results |
|---|---:|---:|---:|
| `NSF` | 20,401 | 17,116 | 83 → 83 |
| identifier | 4,527 | 12,384 | 1 → 1 |
| `NSF` AWARD leg | 11,316 | 10,966 | 25 → 25 |

**Result counts are identical**, which is the correctness statement.
The timings are noise-dominated at n=3: the identifier row's samples
(4,385 / 12,384 / 18,480) overlap its pre-deploy samples
(3,856 / 4,527 / 9,091), so the apparent 2.7× median move is not a
measurable regression and no Global Search code changed. Global Search
remains an open, separately-caused finding —
`DEF5_GLOBAL_SEARCH_SEPARATE_CAUSE.md`.

## 6. Actual improvement, stated plainly

The headline DEF-5 case — Documents with a text query across all
modules — went from a **38,514 ms** median to **11,868 ms**: a
**3.25× reduction**, about **26.6 seconds** off every such search.

That lands inside the projected 8–12 s band published before
deployment, and it is **not** sub-5 s, exactly as projected. The
remaining ~11.9 s is the light union's own scan plus a sort spanning
four physically distinct tables, on 0.5 vCPU. Sub-second filtered text
search would still need a different design (a maintained
document-index table), which this change deliberately did not attempt.

Unexpected bonus: the heavy path also improved 1.57× for the reason
derived in §3.

## 7. Rollback procedure (not used)

Revision 93 remains registered and is the immediate rollback target:

```
aws ecs update-service --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:93
aws ecs wait services-stable --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api
```

Run with `AWS_PROFILE=bu-nprd` and confirm account `770203350335` first.
