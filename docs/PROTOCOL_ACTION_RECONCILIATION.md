# Protocol Action Reconciliation

Run `sql/verify/protocol_action_reconciliation.sql` after loading.

Expected production results:

- total archived rows: 903,796;
- resolved tuple parents: 903,796;
- missing tuple parents: 0;
- ambiguous tuple parents: 0;
- source direct-ID differences: 773,324;
- duplicate source identifiers: 0; and
- archive orphan rows: 0.

Promotion requires all expected counts to match. Any missing or ambiguous
tuple aborts the loader. Direct-ID differences are expected audit evidence,
not a fallback condition. `submission_id_fk` is archived as metadata only.
