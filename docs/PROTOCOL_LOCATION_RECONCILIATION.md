# Protocol Location Reconciliation

Run `sql/verify/protocol_location_reconciliation.sql` after loading.

The report covers total rows, resolution-method counts, distinct placeholder
direct IDs, missing archive direct parents, rejected rows,
resolved/missing/ambiguous parents, source-ID differences, resolved-parent
mismatches, rows lacking all location reference values, duplicate source
identifiers, and archive orphans.

Expected counts are 38,812 `NUMBER_SEQUENCE` rows, 1,524
`DIRECT_ID_PLACEHOLDER` rows, 1,518 distinct placeholder direct IDs, zero
missing archive direct parents, and zero rejected rows. Promotion requires
zero missing, ambiguous, mismatched, duplicate, or orphan archive
relationships.
