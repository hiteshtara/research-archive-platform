# V078 Award backfill — final reconciliation

**Status: COMPLETE and ACCEPTED, 2026-09-23.**

The V078 Award Kuali-summary backfill finished with `remaining families = 0`
and passed all fourteen reconciliation checks. This is the authoritative
record of that reconciliation.

Evidence: dev RDS via a read-only one-off ECS task; Oracle **staging** via
the read-only Keychain runner. Production Oracle was not used for this
work.

## Completion

```
=== BACKFILL COMPLETE: remaining families = 0 ===
batch 3681  families=4,718  versions=14,408  exit=0  GATE=PASS
progress: completed=40,926  remaining=0  total=40,926
```

## The fourteen checks

| # | Check | Result |
|---|---|---|
| 1 | Total families | **40,926** ✅ |
| 2 | Incomplete V078 families | **0** ✅ |
| 3 | `account_type` | **267,386 / 267,386** ✅ |
| 4 | `activity_type` | **267,386 / 267,386** ✅ |
| 5 | `award_type` | **267,386 / 267,386** ✅ |
| 6 | FAIN population | **137,906** ✅ |
| 7 | NSF Science Code population | **95,692** ✅ |
| 8 | `current_fund_effective_date` | **828,153 / 884,201** ✅ |
| 9 | FK constraints / orphans | **75 / 0** ✅ |
| 10 | Stale-complete detector | **empty** ✅ |
| 11 | Fixture 105698-00001 | **9/9 PASS** ✅ |
| 12 | Grant Number `50105698` | → **105698-00001**, seq 16–20 ✅ |
| 13 | Award 204713-00133 seq 125 | Project Start **2016-08-01**, Obligation Start **2020-08-01** ✅ |
| 14 | Random family reconciliation | **5 / 5 identical** ✅ |

## Source-vs-archive equality (checks 6, 7, 8)

These three fields are sparse. They are sparse **because the source is
sparse**, not because the ETL lost anything — archive and Oracle staging
agree exactly, row for row:

| Field | Oracle staging | Archive | Match |
|---|---|---|---|
| `AWARD` rows | 267,386 | 267,386 | ✅ |
| `FAIN_ID` | 137,906 | 137,906 | ✅ |
| `NSF_SEQUENCE_NUMBER` → `nsf_science_code` | 95,692 | 95,692 | ✅ |
| `ACCOUNT_TYPE_CODE` | 267,386 | 267,386 | ✅ |
| `ACTIVITY_TYPE_CODE` | 267,386 | 267,386 | ✅ |
| `AWARD_TYPE_CODE` | 267,386 | 267,386 | ✅ |
| `AWARD_AMOUNT_INFO` rows | 884,201 | 884,201 | ✅ |
| `CURRENT_FUND_EFFECTIVE_DATE` | 828,153 | 828,153 | ✅ |

This distinction matters for anyone later reading "FAIN is only 52%
populated" and concluding the load is incomplete. It is not.

## Check 13 — the mapping this whole effort turned on

Award **204713-00133** sequence 125, confirmed identical in Oracle staging
and the archive:

```
Project Start Date     = 2016-08-01   (AWARD.AWARD_EFFECTIVE_DATE)
Obligation Start Date  = 2020-08-01   (AWARD_AMOUNT_INFO.CURRENT_FUND_EFFECTIVE_DATE,
                                       selected by MAX(AWARD_AMOUNT_INFO_ID))
```

Project Start Date is **AWARD_EFFECTIVE_DATE, never BEGIN_DATE** —
`BEGIN_DATE` is populated in 2 of 267,386 Oracle rows and would render
blank on virtually every record. Obligation Start Date uses Kuali's own
current-row rule, `MAX(AWARD_AMOUNT_INFO_ID)`, never `source_version_number`.

## Check 14 — random families

Drawn by `md5(award_number)` ordering, then compared against Oracle
staging. Version counts and per-field population matched exactly,
including the partial-FAIN case:

| Family | Versions | acct | actv | awt | fain |
|---|---|---|---|---|---|
| 200902-00001 | 7 | 7 | 7 | 7 | **5** |
| 202874-00004 | 5 | 5 | 5 | 5 | 5 |
| 203671-00002 | 5 | 5 | 5 | 5 | 5 |
| 207505-00003 | 8 | 8 | 8 | 8 | 8 |
| 209062-00009 | 1 | 1 | 1 | 1 | 1 |

`200902-00001` carrying FAIN on 5 of 7 versions in **both** systems is the
useful one — it shows the per-version fidelity, not just totals.

## Operational notes carried forward

- Resource usage, batch 3677: `CpuUtilized` **742.40 CPU units** (~72.5% of
  the 1,024-unit / 1 vCPU task reservation), `MemoryUtilized` **1,182 MiB**
  (~57.7% of 2,048 MiB). These are CPU units and MiB, **not percentages**,
  and the reservation comes from the `run-task` override, not the task
  definition — see `docs/runbooks/UNATTENDED_FARGATE_ETL_LOADS.md`.
- The run halted once on a false `TASK_STATUS_UNKNOWN` while batch 3679 was
  still healthy; 3679 completed and later passed its gate. The waiter now
  tolerates a bounded number of consecutive missing `describe-tasks`
  responses without ever inferring completion.

## What this does NOT cover

V079 (`archive.sponsor`) and V080
(`archive.negotiation_search_attribute`) are **not** applied to dev and are
unrelated to this reconciliation. They remain blocked on the Negotiation
attribute-precedence question — see
`docs/archive/investigations/negotiation/NEGOTIATION_FIELD_AND_FILTER_INVESTIGATION.md`
section B.1.
