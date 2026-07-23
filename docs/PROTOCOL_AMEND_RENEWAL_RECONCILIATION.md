# Protocol Amendment/Renewal Reconciliation

Run `sql/verify/protocol_amend_renewal_reconciliation.sql` after loading.

Expected production results:

- total archived rows: 16,569;
- resolved tuple parents: 16,569;
- missing tuple parents: 0;
- ambiguous tuple parents: 0;
- direct-ID differences: 16,569;
- duplicate source identifiers: 0;
- archive orphan count: 0; and
- business-value mismatches: 0.

The loader aborts before upsert on any missing or ambiguous tuple. Direct-ID
differences are expected evidence and never trigger fallback behavior.
Business values flow from the shared Oracle/CSV source model into the same
idempotent upsert mapping.
