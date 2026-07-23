# Protocol Submission Reconciliation

Run `oracle/protocol/validate_protocol_submission_parent.sql` locally before
promotion, then run `sql/verify/protocol_submission_reconciliation.sql`
after loading.

The Oracle validation reports total rows, direct-parent matches and
mismatches, missing direct parents, duplicate source IDs, and exact
number/sequence coverage. Repository evidence currently records 221,519
rows and zero direct-ID mismatches; fresh execution remains the promotion
gate.

The PostgreSQL report covers archived totals, resolved and missing parents,
duplicates, source/direct-ID mismatches, archive orphans, and null counts for
key workflow fields. Value mismatch comparisons require the approved Oracle
export or CSV to be staged alongside archive results; the loader guarantees
that every archived value is mapped from that shared source model.

Promotion requires:

- source and archive row counts to match;
- zero missing parents, duplicate source identifiers, direct-ID mismatches,
  and archive orphans; and
- matching source/archive counts for submission number, type, status, date,
  and their null populations.
