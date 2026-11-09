# Activity audit — partition maintenance and retention

**Nothing in this runbook is scheduled or automated, and none of it should be
until BU approves the retention policy.** `authz.activity_audit` does not exist
in any shared environment yet (V089 is unapplied) and collection is off.

This document exists so that the day it is switched on, the operational side is
already decided rather than improvised.

## 1. Why partitions at all

Retention on a high-volume audit table is a choice between:

- `DELETE FROM … WHERE occurred_at < …` — writes a dead tuple per row, bloats
  the table and the indexes, competes with live inserts, and needs a vacuum
  afterwards; and
- `DROP TABLE` on a whole month — a catalog operation, effectively instant,
  reclaiming the space immediately.

The second also has a property that matters here: **a partition drop does not
fire row triggers**, so it does not fight `tr_activity_audit_append_only`. If
retention were a `DELETE`, the only way to run it would be to disable the
append-only trigger — and an append-only guarantee you switch off on a schedule
is not a guarantee.

## 2. The partitions that exist

V089 creates:

| Partition | Range |
|---|---|
| `activity_audit_2026_11` | 2026-11-01 → 2026-12-01 |
| `activity_audit_2026_12` | 2026-12-01 → 2027-01-01 |
| `activity_audit_2027_01` | 2027-01-01 → 2027-02-01 |
| `activity_audit_overflow` | `DEFAULT` — everything else |

## 3. The DEFAULT partition is a signal, not a dumping ground

An `INSERT` with no matching partition **fails**. For an audit table that is
precisely the wrong failure: under `FAIL_CLOSED` it would turn a missed monthly
rollover into refused downloads, and under `FAIL_OPEN` into a silent audit gap.

So a `DEFAULT` partition catches anything no month claims. **Rows landing there
mean partition maintenance has stopped.** Check it:

```sql
SELECT count(*), min(occurred_at), max(occurred_at)
  FROM authz.activity_audit_overflow;
```

Non-zero is an alert, not a curiosity. Create the missing month, then move the
rows (see §5).

## 4. Adding next month's partition

Run **before** the month begins. Monthly, as part of ordinary operations:

```sql
CREATE TABLE IF NOT EXISTS authz.activity_audit_2027_02
    PARTITION OF authz.activity_audit
    FOR VALUES FROM ('2027-02-01 00:00:00+00') TO ('2027-03-01 00:00:00+00');
```

No grant is needed on the new partition: partitions inherit the parent's
privileges. Do **not** add per-partition grants — they would have to be
repeated every month and would be forgotten.

## 5. Rescuing rows from the DEFAULT partition

A new partition cannot be attached while the `DEFAULT` partition holds rows
that would belong to it. The sequence:

```sql
BEGIN;
ALTER TABLE authz.activity_audit DETACH PARTITION authz.activity_audit_overflow;

CREATE TABLE authz.activity_audit_2027_02
    PARTITION OF authz.activity_audit
    FOR VALUES FROM ('2027-02-01 00:00:00+00') TO ('2027-03-01 00:00:00+00');

INSERT INTO authz.activity_audit
     SELECT * FROM authz.activity_audit_overflow
      WHERE occurred_at >= '2027-02-01 00:00:00+00'
        AND occurred_at <  '2027-03-01 00:00:00+00';

DELETE FROM authz.activity_audit_overflow
      WHERE occurred_at >= '2027-02-01 00:00:00+00'
        AND occurred_at <  '2027-03-01 00:00:00+00';

ALTER TABLE authz.activity_audit ATTACH PARTITION authz.activity_audit_overflow DEFAULT;
COMMIT;
```

The `DELETE` is on the **detached** table, so the append-only trigger on the
parent does not apply — the rows are being relocated, not removed, and the
`INSERT` above has already copied them.

## 6. Retention — PROPOSED, NOT APPROVED

**Proposed: 90 days.** This is a number for review. It is **not** an approved
BU retention policy, and **automatic deletion is not implemented or
scheduled.**

When approved, retention is one statement per expired month:

```sql
-- Only after BU approves the retention period.
DROP TABLE authz.activity_audit_2026_11;
```

Before anything is dropped, decide and write down:

1. **The period.** 90 days is the proposal.
2. **Whether denials are kept longer.** `RECORD_ACCESS_DENIED` rows are the
   ones an investigation is most likely to want, and they are a small fraction
   of the volume. A different period for them is a live option, and is why
   `ix_activity_audit_denied` exists.
3. **Whether anything is exported before the drop.** Once a partition is
   dropped the rows are gone; there is no undo.
4. **Who may authorise a drop.** It is the one operation that removes audit
   evidence.

Until all four are answered, **do not schedule this.**

## 7. What is deliberately absent

- No cron, no EventBridge schedule, no `pg_cron`.
- No automatic partition creation. Monthly creation is a small manual act with
  a visible failure mode (the DEFAULT partition filling up); an automatic one
  fails silently when it stops working.
- No retention job of any kind.
